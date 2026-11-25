package app.fayaz.otgmaster.zip

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.Charset

/**
 * The index of a ZIP archive read from its central directory (APPNOTE.TXT 6.3), without
 * decompressing anything: entry names, methods, sizes, CRCs and where each entry's local header
 * is. ZIP64 sizes and offsets are read from the ZIP64 end record and each entry's 0x0001 extra
 * field. Multi-disk (spanned) archives are refused.
 *
 * Names are untrusted (architecture proposal, "Containment"): an entry whose path has an empty,
 * "." or ".." component, starts at the root or holds a NUL is left out and counted in
 * [skippedUnsafe]; two entries that name the same path, or a file where another entry needs a
 * folder, make the archive [ZipFormatException] ambiguous, since either answer could hide
 * content. Entry count and name bytes are bounded.
 */
class ZipArchive private constructor(
    val channel: FileChannel,
    val length: Long,
    val entries: List<Entry>,
    val skippedUnsafe: Int,
) {
    class Entry(
        /** Path components, checked; empty for the root (never an entry itself). */
        val path: List<String>,
        val isDirectory: Boolean,
        val method: Int,
        val encrypted: Boolean,
        val crc32: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        /** Milliseconds since the epoch from the DOS date and time, local time; 0 if invalid. */
        val lastModified: Long,
    )

    class ZipFormatException(message: String) : IOException(message)

    /** Reads [len] bytes at [pos] fully, or throws. */
    internal fun readFully(pos: Long, len: Int): ByteBuffer {
        val b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        var p = pos
        while (b.hasRemaining()) {
            val n = channel.read(b, p)
            if (n < 0) throw EOFException("the archive ended at byte $p")
            p += n
        }
        return b.flip() as ByteBuffer
    }

    companion object {
        const val MAX_ENTRIES = 200_000
        const val MAX_NAME_BYTES = 64L shl 20
        private const val EOCD = 0x06054b50
        private const val ZIP64_LOCATOR = 0x07064b50
        private const val ZIP64_EOCD = 0x06064b50
        private const val CENTRAL = 0x02014b50
        private const val U32 = 0xFFFFFFFFL
        private const val U16 = 0xFFFF

        private val cp437: Charset = runCatching { Charset.forName("IBM437") }.getOrDefault(Charsets.ISO_8859_1)

        fun open(channel: FileChannel, length: Long = channel.size()): ZipArchive {
            if (length < 22) throw ZipFormatException("too short to be a ZIP archive")
            val a = ZipArchive(channel, length, emptyList(), 0)
            // The end record is in the last 22 + 65,535 bytes (its comment is at most 65,535).
            val tailLen = minOf(length, 22L + 65_535).toInt()
            val tail = a.readFully(length - tailLen, tailLen)
            var eocd = -1
            for (i in tailLen - 22 downTo 0) if (tail.getInt(i) == EOCD) { eocd = i; break }
            if (eocd < 0) throw ZipFormatException("no end of central directory record")
            val eocdPos = length - tailLen + eocd
            val disk = tail.getShort(eocd + 4).toInt() and U16
            val cdDisk = tail.getShort(eocd + 6).toInt() and U16
            var count = (tail.getShort(eocd + 10).toInt() and U16).toLong()
            var cdSize = tail.getInt(eocd + 12).toLong() and U32
            var cdOffset = tail.getInt(eocd + 16).toLong() and U32
            if (count == U16.toLong() || cdSize == U32 || cdOffset == U32 || disk == U16) {
                if (eocdPos < 20) throw ZipFormatException("ZIP64 locator missing")
                val loc = a.readFully(eocdPos - 20, 20)
                if (loc.getInt(0) != ZIP64_LOCATOR) throw ZipFormatException("ZIP64 locator missing")
                val z = a.readFully(loc.getLong(8), 56)
                if (z.getInt(0) != ZIP64_EOCD) throw ZipFormatException("ZIP64 end record missing")
                if (z.getInt(16) != 0 || z.getInt(20) != 0) throw ZipFormatException("spanned (multi-disk) archives are not supported")
                count = z.getLong(32); cdSize = z.getLong(40); cdOffset = z.getLong(48)
            } else if (disk != 0 || cdDisk != 0) throw ZipFormatException("spanned (multi-disk) archives are not supported")
            if (count > MAX_ENTRIES) throw ZipFormatException("$count entries, more than the $MAX_ENTRIES this reader opens")
            if (cdOffset < 0 || cdSize < 0 || cdOffset + cdSize > length || cdSize > Int.MAX_VALUE)
                throw ZipFormatException("the central directory lies outside the file")
            val cd = a.readFully(cdOffset, cdSize.toInt())

            val raw = ArrayList<Entry>()
            var skipped = 0
            var nameBytes = 0L
            var p = 0
            for (i in 0 until count) {
                if (p + 46 > cd.limit() || cd.getInt(p) != CENTRAL) throw ZipFormatException("central directory entry $i is damaged")
                val flags = cd.getShort(p + 8).toInt() and U16
                val method = cd.getShort(p + 10).toInt() and U16
                val time = cd.getShort(p + 12).toInt() and U16
                val date = cd.getShort(p + 14).toInt() and U16
                val crc = cd.getInt(p + 16).toLong() and U32
                var csize = cd.getInt(p + 20).toLong() and U32
                var usize = cd.getInt(p + 24).toLong() and U32
                val nLen = cd.getShort(p + 28).toInt() and U16
                val xLen = cd.getShort(p + 30).toInt() and U16
                val cLen = cd.getShort(p + 32).toInt() and U16
                var offset = cd.getInt(p + 42).toLong() and U32
                if (p + 46 + nLen + xLen + cLen > cd.limit()) throw ZipFormatException("central directory entry $i is damaged")
                nameBytes += nLen
                if (nameBytes > MAX_NAME_BYTES) throw ZipFormatException("entry names exceed ${MAX_NAME_BYTES shr 20} MiB")
                val nameRaw = ByteArray(nLen).also { cd.position(p + 46); cd.get(it) }
                // ZIP64 extra field: the 8-byte values present are those set to all ones above.
                var x = p + 46 + nLen
                val xEnd = x + xLen
                while (x + 4 <= xEnd) {
                    val id = cd.getShort(x).toInt() and U16
                    val sz = cd.getShort(x + 2).toInt() and U16
                    if (id == 0x0001) {
                        var q = x + 4
                        if (usize == U32 && q + 8 <= x + 4 + sz) { usize = cd.getLong(q); q += 8 }
                        if (csize == U32 && q + 8 <= x + 4 + sz) { csize = cd.getLong(q); q += 8 }
                        if (offset == U32 && q + 8 <= x + 4 + sz) { offset = cd.getLong(q) }
                    }
                    x += 4 + sz
                }
                p += 46 + nLen + xLen + cLen
                val name = String(nameRaw, if (flags and 0x0800 != 0) Charsets.UTF_8 else cp437)
                val isDir = name.endsWith("/")
                val parts = name.trimEnd('/').split('/')
                val unsafe = name.isEmpty() || name.startsWith("/") || name.contains('\u0000') ||
                    parts.any { it.isEmpty() || it == "." || it == ".." }
                if (unsafe) { skipped++; continue }
                if (usize < 0 || csize < 0 || offset < 0 || offset > length) throw ZipFormatException("entry ${parts.last()} has impossible sizes")
                raw += Entry(parts, isDir, method, flags and 1 != 0, crc, csize, usize, offset, dosTime(date, time))
            }

            // One meaning per path: a duplicate, or a file that another entry needs as a folder,
            // is ambiguous, and either reading could hide content.
            val files = HashSet<List<String>>(); val dirs = HashSet<List<String>>()
            for (e in raw) {
                if (e.isDirectory) dirs += e.path else if (!files.add(e.path)) throw ZipFormatException("two entries are named ${e.path.joinToString("/")}")
                for (k in 1 until e.path.size) dirs += e.path.subList(0, k)
            }
            for (d in dirs) if (d in files) throw ZipFormatException("${d.joinToString("/")} is both a file and a folder")
            return ZipArchive(channel, length, raw.filter { !it.isDirectory } + dirs.map { d ->
                raw.firstOrNull { it.isDirectory && it.path == d }
                    ?: Entry(d, true, 0, false, 0, 0, 0, 0, 0)
            }, skipped)
        }

        private fun dosTime(date: Int, time: Int): Long {
            if (date == 0) return 0
            return runCatching {
                java.util.Calendar.getInstance().apply {
                    clear()
                    set(1980 + (date shr 9), ((date shr 5) and 15) - 1, date and 31,
                        time shr 11, (time shr 5) and 63, (time and 31) * 2)
                }.timeInMillis
            }.getOrDefault(0)
        }
    }
}

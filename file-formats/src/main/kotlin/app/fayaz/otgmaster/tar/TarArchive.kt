package app.fayaz.otgmaster.tar

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * The index of a TAR archive (POSIX ustar, with GNU long names and PAX extended headers), read
 * header by header without keeping any file's contents: entry names, sizes, times, and where each
 * file's data starts in the uncompressed stream.
 *
 * Read from any [InputStream] of the uncompressed archive, so the same index serves a plain .tar
 * (where a data offset is a position in the file) and a .tar.gz (a position in what the gzip
 * stream inflates to). [TarDocumentSource] reads the data accordingly.
 *
 * Names are untrusted, as for ZIP (architecture proposal, "Containment"): a path with an empty,
 * "." or ".." component, an absolute one or one holding a NUL is left out and counted in
 * [skippedUnsafe]. Links and device nodes are left out and counted in [skippedOther]: a link's
 * target is a path the archive does not contain, or one it does, read twice under two names.
 * A later entry for the same path replaces an earlier one, which is what `tar -x` leaves on disk.
 * Every header's checksum is checked; entry count and name lengths are bounded.
 */
class TarArchive private constructor(
    val entries: List<Entry>,
    val skippedUnsafe: Int,
    val skippedOther: Int,
) {
    class Entry(
        /** Path components, checked; never empty. */
        val path: List<String>,
        val isDirectory: Boolean,
        val size: Long,
        /** Where the file's bytes start in the uncompressed archive; 0 for a folder. */
        val dataOffset: Long,
        /** Milliseconds since the epoch; 0 if the header has none. */
        val lastModified: Long,
    )

    class TarFormatException(message: String) : IOException(message)

    companion object {
        const val BLOCK = 512
        const val MAX_ENTRIES = 1_000_000
        private const val MAX_NAME_BYTES = 64 * 1024

        /**
         * Whether [block], a file's first 512 bytes uncompressed, is a TAR header: "ustar" at 257,
         * or a v7 header whose checksum holds over a non-empty name.
         */
        fun looksLikeTar(block: ByteArray): Boolean {
            if (block.size < BLOCK || block[0].toInt() == 0) return false
            if (String(block, 257, 5, Charsets.US_ASCII) == "ustar") return true
            return runCatching { checkSum(block, 0) }.isSuccess
        }

        /** Whether [head], a file's first bytes, is a gzip stream whose first 512 bytes inflate to a TAR header. */
        fun looksLikeTarGz(head: ByteArray): Boolean {
            if (head.size < 18 || head[0] != 0x1F.toByte() || head[1] != 0x8B.toByte()) return false
            val out = ByteArray(BLOCK)
            var n = 0
            runCatching {
                java.util.zip.GZIPInputStream(head.inputStream()).use { g ->
                    while (n < BLOCK) { val r = g.read(out, n, BLOCK - n); if (r < 0) break; n += r }
                }
            }
            return n == BLOCK && looksLikeTar(out)
        }

        /** Reads the index from [input], the uncompressed archive from its first byte. */
        fun index(input: InputStream): TarArchive {
            val r = Reader(input)
            val byPath = LinkedHashMap<List<String>, Entry>()
            var unsafe = 0
            var other = 0
            var longName: String? = null
            var pax = emptyMap<String, String>()
            var zeroBlocks = 0
            while (true) {
                val h = r.block() ?: break   // an archive cut at a block boundary: what was read stands
                if (h.all { it.toInt() == 0 }) {
                    // Two zero blocks end the archive; one alone is tolerated, as GNU tar does.
                    if (++zeroBlocks == 2) break else continue
                }
                zeroBlocks = 0
                checkSum(h, r.position - BLOCK)
                val type = h[156].toInt().toChar()
                val size = number(h, 124, 12, r.position - BLOCK)
                if (size < 0) throw TarFormatException("a negative size at byte ${r.position - BLOCK}")
                val dataStart = r.position
                when (type) {
                    'L' -> { longName = r.text(size); r.skipPadded(0); continue }   // GNU long name for the next entry
                    'x' -> { pax = paxRecords(r.text(size)); r.skipPadded(0); continue }
                    'g' -> { r.skipPadded(size); continue }                          // PAX global header: nothing we use
                    'K' -> { r.skipPadded(size); continue }                          // GNU long link name
                }
                val rawName = pax["path"] ?: longName ?: ustarName(h)
                val fileSize = pax["size"]?.toLongOrNull() ?: size
                val mtime = pax["mtime"]?.substringBefore('.')?.toLongOrNull() ?: number(h, 136, 12, dataStart - BLOCK)
                longName = null; pax = emptyMap()
                val isDir = type == '5' || (type in "0\u0000" && rawName.endsWith("/"))
                val isFile = type == '0' || type == '\u0000' || type == '7'
                val path = safePath(rawName)
                when {
                    !isDir && !isFile -> other++                                     // links, devices, FIFOs
                    path == null -> unsafe++
                    else -> {
                        // A folder implied by a file's path, or named after it, must not be a file too.
                        byPath[path] = Entry(path, isDir, if (isDir) 0 else fileSize, if (isDir) 0 else dataStart, mtime * 1000)
                        if (byPath.size > MAX_ENTRIES) throw TarFormatException("more than $MAX_ENTRIES entries; refused")
                    }
                }
                r.skipPadded(if (isFile) fileSize else 0)
            }
            val entries = byPath.values.toList()
            // A path both a file and, for another entry, a folder: either answer could hide content.
            val files = entries.filter { !it.isDirectory }.map { it.path }.toHashSet()
            for (e in entries) for (i in 1 until e.path.size) if (e.path.subList(0, i) in files)
                throw TarFormatException("${e.path.subList(0, i).joinToString("/")} is both a file and a folder; refused")
            return TarArchive(entries, unsafe, other)
        }

        private fun ustarName(h: ByteArray): String {
            val name = cString(h, 0, 100)
            val isUstar = String(h, 257, 5, Charsets.US_ASCII) == "ustar"
            val prefix = if (isUstar) cString(h, 345, 155) else ""
            return if (prefix.isEmpty()) name else "$prefix/$name"
        }

        private fun cString(h: ByteArray, off: Int, len: Int): String {
            var end = off
            while (end < off + len && h[end].toInt() != 0) end++
            return String(h, off, end - off, Charsets.UTF_8)
        }

        /** An octal field, or GNU's base-256 form for values that do not fit (a high bit on the first byte). */
        private fun number(h: ByteArray, off: Int, len: Int, at: Long): Long {
            if (h[off].toInt() and 0x80 != 0) {
                var v = (h[off].toLong() and 0x7F)
                for (i in off + 1 until off + len) v = (v shl 8) or (h[i].toLong() and 0xFF)
                return v
            }
            var v = 0L
            var i = off
            while (i < off + len && (h[i] == ' '.code.toByte() || h[i].toInt() == 0)) i++
            while (i < off + len && h[i] in '0'.code.toByte()..'7'.code.toByte()) { v = v * 8 + (h[i] - '0'.code.toByte()); i++ }
            if (i < off + len && h[i] != ' '.code.toByte() && h[i].toInt() != 0)
                throw TarFormatException("a damaged number field in the header at byte $at")
            return v
        }

        private fun checkSum(h: ByteArray, at: Long) {
            val stored = number(h, 148, 8, at)
            var unsigned = 0L
            for (i in 0 until BLOCK) unsigned += if (i in 148 until 156) 32 else (h[i].toLong() and 0xFF)
            var signed = 0L
            for (i in 0 until BLOCK) signed += if (i in 148 until 156) 32 else h[i].toLong()
            if (stored != unsigned && stored != signed) throw TarFormatException("the header at byte $at fails its checksum; not a TAR archive, or a damaged one")
        }

        /** PAX records, "<length> <key>=<value>\n" each. */
        private fun paxRecords(text: String): Map<String, String> {
            val out = HashMap<String, String>()
            var i = 0
            val bytes = text.toByteArray(Charsets.UTF_8)
            while (i < bytes.size) {
                var sp = i
                while (sp < bytes.size && bytes[sp] != ' '.code.toByte()) sp++
                val len = String(bytes, i, sp - i, Charsets.US_ASCII).toIntOrNull() ?: break
                if (len <= 0 || i + len > bytes.size) break
                val rec = String(bytes, sp + 1, i + len - sp - 2, Charsets.UTF_8)   // drops the newline
                val eq = rec.indexOf('=')
                if (eq > 0) out[rec.substring(0, eq)] = rec.substring(eq + 1)
                i += len
            }
            return out
        }

        /** The checked path components, or null if the name could reach outside the archive. */
        internal fun safePath(name: String): List<String>? {
            if (name.isEmpty() || name.startsWith("/") || '\u0000' in name) return null
            val parts = name.trimEnd('/').removePrefix("./").split('/')
            if (parts.isEmpty() || parts.any { it.isEmpty() || it == "." || it == ".." }) return null
            return parts
        }
    }

    /** Reads whole blocks from the stream and keeps count of the position. */
    private class Reader(private val input: InputStream) {
        var position = 0L
        private val buf = ByteArray(BLOCK)

        /** The next block, or null at the end of the stream before it. */
        fun block(): ByteArray? {
            var n = 0
            while (n < BLOCK) {
                val got = input.read(buf, n, BLOCK - n)
                if (got < 0) { if (n == 0) return null else throw EOFException("the archive ends inside a header at byte $position") }
                n += got
            }
            position += BLOCK
            return buf.copyOf()
        }

        /** [size] bytes of an extended header's text, padding included in the position. */
        fun text(size: Long): String {
            if (size > MAX_NAME_BYTES) throw TarFormatException("an extended header of $size bytes; refused")
            val out = ByteArray(size.toInt())
            var n = 0
            while (n < out.size) { val got = input.read(out, n, out.size - n); if (got < 0) throw EOFException("the archive ends inside an extended header"); n += got }
            position += size
            pendingPad = ((BLOCK - size % BLOCK) % BLOCK)
            return String(out, Charsets.UTF_8).trimEnd('\u0000')
        }

        private var pendingPad = 0L

        /** Skips [size] bytes of data and the padding to the next block (and any left by [text]). */
        fun skipPadded(size: Long) {
            val total = size + ((BLOCK - size % BLOCK) % BLOCK) + pendingPad
            pendingPad = 0
            var left = total
            while (left > 0) {
                val s = input.skip(left)
                if (s <= 0) { if (input.read() < 0) throw EOFException("the archive ends inside an entry's data"); left-- } else left -= s
            }
            position += total
        }
    }
}

package app.fayaz.otgmaster.iso

import app.fayaz.otgmaster.core.DocumentHandle
import app.fayaz.otgmaster.core.DocumentNode
import app.fayaz.otgmaster.core.DocumentSource
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * An ISO 9660 image as a read-only [DocumentSource] (architecture proposal, Milestone 6, F11):
 * the volume descriptors from sector 16, with Joliet's UCS-2 names used when the image has a
 * Joliet supplementary descriptor (escape %/@, %/C or %/E), and the plain level-1/2 names
 * otherwise, with their ";1" version and trailing dot removed. Directories are read when first
 * listed and kept. Files are read from their extent in place. A hybrid image (an MBR or GPT in
 * its first sectors) still has its descriptors at sector 16 and opens the same way. UDF-only
 * discs and multi-extent files (over 4 GiB) are refused.
 *
 * Names are untrusted: "." and ".." records are skipped, a name with a "/" or a NUL is left out,
 * and two entries of one folder with the same name make that folder [IsoFormatException]
 * ambiguous. Depth, entries per folder and folder size are bounded.
 */
class IsoDocumentSource private constructor(
    private val channel: FileChannel,
    private val length: Long,
    private val joliet: Boolean,
    rootExtent: Long,
    rootSize: Long,
    val volumeLabel: String,
) : DocumentSource {

    class IsoFormatException(message: String) : IOException(message)

    class Node internal constructor(
        val path: List<String>,
        override val isDirectory: Boolean,
        internal val extent: Long,
        override val size: Long,
        override val lastModified: Long,
    ) : DocumentNode {
        override val name: String get() = path.lastOrNull() ?: ""
        override val isRoot: Boolean get() = path.isEmpty()
    }

    private val root = Node(emptyList(), true, rootExtent, rootSize, 0)
    private val listings = ConcurrentHashMap<List<String>, List<Node>>()

    override val readOnlyReason: String get() = READ_ONLY
    override fun root(): DocumentNode = root
    override fun child(dir: DocumentNode, name: String): DocumentNode? = children(dir).firstOrNull { it.name == name }

    override fun children(dir: DocumentNode): List<DocumentNode> {
        val d = dir as Node
        if (!d.isDirectory) return emptyList()
        return listings.getOrPut(d.path) { readDirectory(d) }
    }

    private fun readFully(pos: Long, len: Int): ByteBuffer {
        if (pos < 0 || pos + len > length) throw IsoFormatException("a structure lies outside the image")
        val b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        var p = pos
        while (b.hasRemaining()) { val n = channel.read(b, p); if (n < 0) throw EOFException("the image ended at byte $p"); p += n }
        return b.flip() as ByteBuffer
    }

    private fun readDirectory(d: Node): List<Node> {
        if (d.path.size >= MAX_DEPTH) throw IsoFormatException("folders nested deeper than $MAX_DEPTH")
        if (d.size > MAX_DIR_BYTES) throw IsoFormatException("a folder of ${d.size} bytes, more than this reader lists")
        val buf = readFully(d.extent * SECTOR, d.size.toInt())
        val out = ArrayList<Node>(); val seen = HashSet<String>()
        var p = 0
        while (p < buf.limit()) {
            val len = buf.get(p).toInt() and 0xFF
            if (len == 0) { p = ((p / SECTOR + 1) * SECTOR).toInt(); continue }   // records do not span sectors
            if (len < 34 || p + len > buf.limit()) throw IsoFormatException("a directory record in ${d.path.joinToString("/").ifEmpty { "the root" }} is damaged")
            val extent = buf.getInt(p + 2).toLong() and 0xFFFFFFFFL
            val size = buf.getInt(p + 10).toLong() and 0xFFFFFFFFL
            val flags = buf.get(p + 25).toInt()
            val nameLen = buf.get(p + 32).toInt() and 0xFF
            if (33 + nameLen > len) throw IsoFormatException("a directory record's name runs past it")
            val raw = ByteArray(nameLen).also { buf.position(p + 33); buf.get(it) }
            p += len
            if (nameLen == 1 && (raw[0].toInt() == 0 || raw[0].toInt() == 1)) continue   // "." and ".."
            if (flags and 0x01 != 0) continue                                           // hidden (associated) file
            if (flags and 0x80 != 0) throw IsoFormatException("multi-extent files (over 4 GiB) are not supported yet")
            val name = decodeName(raw, flags and 0x02 != 0)
            if (name.isEmpty() || name == "." || name == ".." || name.contains('/') || name.contains('\u0000')) continue
            if (!seen.add(name)) throw IsoFormatException("two entries are named $name in ${d.path.joinToString("/").ifEmpty { "the root" }}")
            if (out.size >= MAX_ENTRIES) throw IsoFormatException("more than $MAX_ENTRIES entries in one folder")
            val isDir = flags and 0x02 != 0
            if (!isDir && extent * SECTOR + size > length) throw IsoFormatException("$name runs past the end of the image")
            out += Node(d.path + name, isDir, extent, size, recordingTime(buf, p - len + 18))
        }
        return out
    }

    private fun decodeName(raw: ByteArray, isDir: Boolean): String {
        var n = if (joliet) String(raw, Charsets.UTF_16BE) else String(raw, Charsets.ISO_8859_1)
        n = n.substringBefore(';')                       // the file version, "README.TXT;1"
        if (!isDir && n.endsWith(".")) n = n.dropLast(1)  // "README." for a name with no extension
        return n
    }

    /** The 7-byte recording date: years since 1900, month, day, hour, minute, second, GMT offset in 15 min. */
    private fun recordingTime(b: ByteBuffer, at: Int): Long = runCatching {
        val y = b.get(at).toInt() and 0xFF
        if (y == 0) return 0
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(1900 + y, (b.get(at + 1).toInt() and 0xFF) - 1, b.get(at + 2).toInt(), b.get(at + 3).toInt(), b.get(at + 4).toInt(), b.get(at + 5).toInt())
        cal.timeInMillis - b.get(at + 6).toLong() * 15 * 60_000
    }.getOrDefault(0)

    override fun open(node: DocumentNode): DocumentHandle {
        val n = node as Node
        if (n.isDirectory) throw IOException("A folder cannot be opened")
        val start = n.extent * SECTOR
        return object : DocumentHandle {
            override fun size() = n.size
            override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
                if (offset >= n.size) return 0
                val want = minOf(length.toLong(), n.size - offset).toInt()
                val b = ByteBuffer.wrap(buffer, 0, want)
                var p = start + offset
                while (b.hasRemaining()) { val r = channel.read(b, p); if (r < 0) throw IOException("the image ended inside ${n.name}"); p += r }
                return want
            }
            override fun write(offset: Long, buffer: ByteArray, length: Int) = refuse()
            override fun truncate() = refuse()
            override fun flush() {}
            override fun close() {}
        }
    }

    private fun refuse(): Nothing = throw IOException(READ_ONLY)
    override fun createFile(dir: DocumentNode, name: String): DocumentNode = refuse()
    override fun createDirectory(dir: DocumentNode, name: String): DocumentNode = refuse()
    override fun rename(node: DocumentNode, name: String) = refuse()
    override fun delete(node: DocumentNode) = refuse()

    companion object {
        const val SECTOR = 2048L
        const val READ_ONLY = "Disc images open read-only"
        const val MAX_DEPTH = 64
        const val MAX_ENTRIES = 100_000
        const val MAX_DIR_BYTES = 16L shl 20

        /** True if [channel] has an ISO 9660 primary volume descriptor ("CD001" at sector 16). */
        fun looksLikeIso(head16: ByteArray): Boolean =
            head16.size >= 6 && head16[0].toInt() == 1 && String(head16, 1, 5, Charsets.US_ASCII) == "CD001"

        fun open(channel: FileChannel, length: Long = channel.size()): IsoDocumentSource {
            var pvd: ByteBuffer? = null; var svd: ByteBuffer? = null
            var sector = 16L
            while (true) {
                if ((sector + 1) * SECTOR > length || sector > 16 + 64) throw IsoFormatException("no volume descriptor set terminator")
                val b = ByteBuffer.allocate(SECTOR.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                var p = sector * SECTOR
                while (b.hasRemaining()) { val n = channel.read(b, p); if (n < 0) throw EOFException("the image ended in its volume descriptors"); p += n }
                b.flip()
                val type = b.get(0).toInt() and 0xFF
                if (String(ByteArray(5).also { b.position(1); b.get(it) }, Charsets.US_ASCII) != "CD001")
                    throw IsoFormatException(if (sector == 16L) "not an ISO 9660 image (a UDF-only disc is not supported)" else "a damaged volume descriptor")
                when (type) {
                    1 -> if (pvd == null) pvd = b
                    2 -> {
                        val esc = String(ByteArray(3).also { b.position(88); b.get(it) }, Charsets.US_ASCII)
                        if (esc == "%/@" || esc == "%/C" || esc == "%/E") svd = b
                    }
                    255 -> break
                }
                sector++
            }
            val use = svd ?: pvd ?: throw IsoFormatException("no primary volume descriptor")
            val joliet = svd != null
            val rootExtent = use.getInt(156 + 2).toLong() and 0xFFFFFFFFL
            val rootSize = use.getInt(156 + 10).toLong() and 0xFFFFFFFFL
            val labelRaw = ByteArray(32).also { use.position(40); use.get(it) }
            val label = (if (joliet) String(labelRaw, Charsets.UTF_16BE) else String(labelRaw, Charsets.US_ASCII)).trim()
            return IsoDocumentSource(channel, length, joliet, rootExtent, rootSize, label)
        }
    }
}

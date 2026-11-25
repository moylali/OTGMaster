package app.fayaz.otgmaster.zip

import app.fayaz.otgmaster.core.DocumentHandle
import app.fayaz.otgmaster.core.DocumentNode
import app.fayaz.otgmaster.core.DocumentSource
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * A ZIP archive as a read-only [DocumentSource] (architecture proposal, Milestone 4): the tree
 * from [ZipArchive]'s index, files opened through [DocumentHandle]s that read stored entries in
 * place and inflate Deflate ones.
 *
 * Seeks in a Deflate entry: an entry up to [MEMORY_ENTRY_BYTES] is inflated whole on its first
 * read and served from memory, so a viewer jumping around a PDF costs nothing more. A larger one
 * is inflated as a stream; a read behind the stream's position restarts it from the entry's
 * start, a read ahead decodes and drops the bytes between. Either way the CRC-32 is checked when
 * the decoder reaches the end of the entry, and an entry that inflates to more than its declared
 * size is refused, so a damaged or hostile archive fails with an error rather than wrong bytes.
 */
class ZipDocumentSource(val archive: ZipArchive) : DocumentSource {

    class Node internal constructor(
        val path: List<String>,
        override val isDirectory: Boolean,
        val entry: ZipArchive.Entry?,
    ) : DocumentNode {
        override val name: String get() = path.lastOrNull() ?: ""
        override val isRoot: Boolean get() = path.isEmpty()
        override val size: Long get() = if (isDirectory) 0 else entry?.uncompressedSize ?: 0
        override val lastModified: Long get() = entry?.lastModified ?: 0
    }

    private val root = Node(emptyList(), true, null)
    private val children: Map<List<String>, List<Node>> = archive.entries
        .map { Node(it.path, it.isDirectory, it) }
        .groupBy { it.path.dropLast(1) }

    override val readOnlyReason: String get() = READ_ONLY
    override fun root(): DocumentNode = root
    override fun child(dir: DocumentNode, name: String): DocumentNode? =
        children[(dir as Node).path]?.firstOrNull { it.name == name }
    override fun children(dir: DocumentNode): List<DocumentNode> = children[(dir as Node).path].orEmpty()

    override fun open(node: DocumentNode): DocumentHandle {
        val e = (node as Node).entry ?: throw IOException("A folder cannot be opened")
        if (node.isDirectory) throw IOException("A folder cannot be opened")
        if (e.encrypted) throw IOException("This ZIP entry is encrypted, which Container Master cannot open yet")
        val dataStart = dataStart(e)
        return when (e.method) {
            0 -> Stored(e, dataStart)
            8 -> if (e.uncompressedSize <= MEMORY_ENTRY_BYTES) InMemory(inflateAll(e, dataStart)) else Streamed(e, dataStart)
            else -> throw IOException("This ZIP entry uses compression method ${e.method}; only stored and Deflate are supported")
        }
    }

    /** Where the entry's data begins: after its local header, whose name and extra lengths may differ from the central one's. */
    private fun dataStart(e: ZipArchive.Entry): Long {
        val h = archive.readFully(e.localHeaderOffset, 30)
        if (h.getInt(0) != 0x04034b50) throw IOException("The archive's local header for ${e.path.last()} is damaged")
        val start = e.localHeaderOffset + 30 + (h.getShort(26).toInt() and 0xFFFF) + (h.getShort(28).toInt() and 0xFFFF)
        if (start + e.compressedSize > archive.length) throw IOException("${e.path.last()} runs past the end of the archive")
        return start
    }

    private fun refuse(): Nothing = throw IOException(READ_ONLY)
    override fun createFile(dir: DocumentNode, name: String): DocumentNode = refuse()
    override fun createDirectory(dir: DocumentNode, name: String): DocumentNode = refuse()
    override fun rename(node: DocumentNode, name: String) = refuse()
    override fun delete(node: DocumentNode) = refuse()

    private abstract inner class ReadOnly : DocumentHandle {
        override fun write(offset: Long, buffer: ByteArray, length: Int) = refuse()
        override fun truncate() = refuse()
        override fun flush() {}
        override fun close() {}
    }

    private inner class Stored(private val e: ZipArchive.Entry, private val start: Long) : ReadOnly() {
        private var verified = false
        override fun size() = e.uncompressedSize
        override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
            if (offset >= e.uncompressedSize) return 0
            val n = minOf(length.toLong(), e.uncompressedSize - offset).toInt()
            val b = java.nio.ByteBuffer.wrap(buffer, 0, n)
            var p = start + offset
            while (b.hasRemaining()) {
                val r = archive.channel.read(b, p)
                if (r < 0) throw IOException("The archive ended inside ${e.path.last()}")
                p += r
            }
            // A read that reaches the end of a stored entry from its start checks its CRC once.
            if (!verified && offset == 0L && n.toLong() == e.uncompressedSize) {
                checkCrc(e, CRC32().apply { update(buffer, 0, n) }.value); verified = true
            }
            return n
        }
    }

    private inner class InMemory(private val bytes: ByteArray) : ReadOnly() {
        override fun size() = bytes.size.toLong()
        override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
            if (offset >= bytes.size) return 0
            val n = minOf(length.toLong(), bytes.size - offset).toInt()
            System.arraycopy(bytes, offset.toInt(), buffer, 0, n)
            return n
        }
    }

    private inner class Streamed(private val e: ZipArchive.Entry, private val start: Long) : ReadOnly() {
        private var inf = Inflater(true)
        private var inPos = 0L
        private var outPos = 0L
        private var crc = CRC32()
        private val inBuf = ByteArray(64 * 1024)
        private val skip = ByteArray(64 * 1024)
        override fun size() = e.uncompressedSize

        private var dummyGiven = false
        private fun restart() { inf.end(); inf = Inflater(true); inPos = 0; outPos = 0; crc = CRC32(); dummyGiven = false }

        /** Inflates up to [len] bytes into [dst] at [off]; returns the count, 0 at the entry's end. */
        private fun inflate(dst: ByteArray, off: Int, len: Int): Int {
            while (true) {
                if (inf.finished()) return 0
                if (inf.needsInput()) {
                    val n = minOf(inBuf.size.toLong(), e.compressedSize - inPos).toInt()
                    if (n <= 0) {
                        // Inflater(nowrap = true) needs one dummy byte after the data before it
                        // can report the end of the stream (java.util.zip.Inflater's own note).
                        if (dummyGiven) throw IOException("${e.path.last()} is damaged: its compressed data ended early")
                        dummyGiven = true; inf.setInput(ByteArray(1)); continue
                    }
                    val bb = java.nio.ByteBuffer.wrap(inBuf, 0, n)
                    var p = start + inPos
                    while (bb.hasRemaining()) { val r = archive.channel.read(bb, p); if (r < 0) throw IOException("The archive ended inside ${e.path.last()}"); p += r }
                    inf.setInput(inBuf, 0, n); inPos += n
                }
                val got = try { inf.inflate(dst, off, len) } catch (x: DataFormatException) {
                    throw IOException("${e.path.last()} is damaged: ${x.message}")
                }
                if (got > 0) {
                    crc.update(dst, off, got); outPos += got
                    if (outPos > e.uncompressedSize) throw IOException("${e.path.last()} inflates past its declared size; refused")
                    if (inf.finished()) { if (outPos != e.uncompressedSize) throw IOException("${e.path.last()} is damaged: wrong size"); checkCrc(e, crc.value) }
                    return got
                }
                if (inf.finished()) { checkCrc(e, crc.value); return 0 }
                if (inf.needsDictionary()) throw IOException("${e.path.last()} needs a preset dictionary; refused")
            }
        }

        override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
            if (offset >= e.uncompressedSize) return 0
            if (offset < outPos) restart()
            while (outPos < offset) {
                val n = inflate(skip, 0, minOf(skip.size.toLong(), offset - outPos).toInt())
                if (n == 0) return 0
            }
            var total = 0
            val want = minOf(length.toLong(), e.uncompressedSize - offset).toInt()
            while (total < want) {
                val n = inflate(buffer, total, want - total)
                if (n == 0) break
                total += n
            }
            // At the declared end, run the decoder to its own end: that is where the CRC-32 is
            // checked and where an entry longer than declared shows itself.
            if (outPos == e.uncompressedSize) drain()
            return total
        }

        /** Decodes to the end of the stream, checking its CRC; anything decoded past the declared size throws. */
        fun drain() { while (inflate(skip, 0, skip.size) != 0) { /* inflate checks the size */ } }

        override fun close() = inf.end()
    }

    private fun inflateAll(e: ZipArchive.Entry, start: Long): ByteArray {
        val out = ByteArray(e.uncompressedSize.toInt())
        val h = Streamed(e, start)
        try {
            var off = 0
            while (off < out.size) {
                val chunk = ByteArray(minOf(1 shl 20, out.size - off))
                val n = h.read(off.toLong(), chunk, chunk.size)
                if (n <= 0) throw IOException("${e.path.last()} is damaged: it inflated to $off of ${out.size} bytes")
                chunk.copyInto(out, off, 0, n); off += n
            }
            h.drain()
        } finally { h.close() }
        return out
    }

    private fun checkCrc(e: ZipArchive.Entry, actual: Long) {
        if (actual != e.crc32) throw IOException("${e.path.last()} is damaged: CRC-32 %08x, expected %08x".format(actual, e.crc32))
    }

    companion object {
        const val READ_ONLY = "ZIP archives open read-only"
        /** Deflate entries up to this size are inflated whole on their first read. */
        const val MEMORY_ENTRY_BYTES = 16L shl 20
    }
}

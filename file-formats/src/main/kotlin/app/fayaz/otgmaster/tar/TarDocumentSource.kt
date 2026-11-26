package app.fayaz.otgmaster.tar

import app.fayaz.otgmaster.core.DocumentHandle
import app.fayaz.otgmaster.core.DocumentNode
import app.fayaz.otgmaster.core.DocumentSource
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream

/**
 * A TAR archive as a read-only [DocumentSource]: the tree from [TarArchive]'s index, files read
 * through [DocumentHandle]s.
 *
 * A plain .tar keeps every file's bytes contiguous and uncompressed, so a read is a positional
 * read of the archive file, at any offset, at no cost. A .tar.gz has no index into its
 * compressed stream: a file up to [MEMORY_ENTRY_BYTES] is inflated whole on its first read and
 * served from memory; a larger one is inflated as a stream from the archive's start to the
 * file, a read behind the stream's position restarting it, a read ahead decoding and dropping
 * the bytes between. gzip's own CRC-32 is checked whenever the stream reaches its end.
 */
class TarDocumentSource private constructor(
    private val channel: FileChannel,
    val archive: TarArchive,
    private val gzip: Boolean,
) : DocumentSource {

    class Node internal constructor(val path: List<String>, override val isDirectory: Boolean, val entry: TarArchive.Entry?) : DocumentNode {
        override val name: String get() = path.lastOrNull() ?: ""
        override val isRoot: Boolean get() = path.isEmpty()
        override val size: Long get() = entry?.size ?: 0
        override val lastModified: Long get() = entry?.lastModified ?: 0
    }

    private val root = Node(emptyList(), true, null)
    private val children: Map<List<String>, List<Node>>

    init {
        // Folders a file's path implies but the archive never names get a node of their own.
        val nodes = LinkedHashMap<List<String>, Node>()
        for (e in archive.entries) {
            for (i in 1 until e.path.size) {
                val dir = e.path.subList(0, i)
                if (dir !in nodes) nodes[dir] = Node(dir, true, null)
            }
            nodes[e.path] = Node(e.path, e.isDirectory, e)
        }
        children = nodes.values.groupBy { it.path.dropLast(1) }
    }

    override val readOnlyReason: String get() = READ_ONLY
    override fun root(): DocumentNode = root
    override fun child(dir: DocumentNode, name: String): DocumentNode? = children[(dir as Node).path]?.firstOrNull { it.name == name }
    override fun children(dir: DocumentNode): List<DocumentNode> = children[(dir as Node).path].orEmpty()

    override fun open(node: DocumentNode): DocumentHandle {
        val n = node as Node
        val e = n.entry ?: throw IOException("A folder cannot be opened")
        if (n.isDirectory) throw IOException("A folder cannot be opened")
        return when {
            !gzip -> InPlace(e)
            e.size <= MEMORY_ENTRY_BYTES -> InMemory(GzStream().apply { seek(e.dataOffset) }.readExactly(e.size.toInt(), e))
            else -> Streamed(e)
        }
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

    private inner class InPlace(private val e: TarArchive.Entry) : ReadOnly() {
        override fun size() = e.size
        override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
            if (offset >= e.size) return 0
            val n = minOf(length.toLong(), e.size - offset).toInt()
            val b = ByteBuffer.wrap(buffer, 0, n)
            var p = e.dataOffset + offset
            while (b.hasRemaining()) { if (channel.read(b, p).also { if (it > 0) p += it } < 0) throw IOException("The archive ended inside ${e.path.last()}") }
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

    /** The uncompressed archive as a forward-only stream, with its position. */
    private inner class GzStream {
        private val input: InputStream = GZIPInputStream(PositionalInput(channel).buffered(64 * 1024), 64 * 1024)
        var position = 0L; private set
        private val drop = ByteArray(64 * 1024)

        fun seek(to: Long) {
            while (position < to) {
                val n = input.read(drop, 0, minOf(drop.size.toLong(), to - position).toInt())
                if (n < 0) throw IOException("The archive ended before byte $to")
                position += n
            }
        }

        fun read(dst: ByteArray, off: Int, len: Int): Int {
            val n = input.read(dst, off, len)
            if (n > 0) position += n
            return n
        }

        fun readExactly(count: Int, e: TarArchive.Entry): ByteArray {
            val out = ByteArray(count)
            var n = 0
            while (n < count) { val got = read(out, n, count - n); if (got < 0) throw IOException("The archive ended inside ${e.path.last()}"); n += got }
            close()
            return out
        }

        fun close() = input.close()
    }

    private inner class Streamed(private val e: TarArchive.Entry) : ReadOnly() {
        private var s: GzStream? = null
        override fun size() = e.size
        override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
            if (offset >= e.size) return 0
            val want = e.dataOffset + offset
            var cur = s
            if (cur == null || cur.position > want) { cur?.close(); cur = GzStream(); s = cur }
            cur.seek(want)
            val n = minOf(length.toLong(), e.size - offset).toInt()
            var total = 0
            while (total < n) { val got = cur.read(buffer, total, n - total); if (got < 0) throw IOException("The archive ended inside ${e.path.last()}"); total += got }
            return total
        }
        override fun close() { s?.close(); s = null }
    }

    companion object {
        const val READ_ONLY = "TAR archives open read-only"
        /** A .tar.gz file up to this size is inflated whole on its first read. */
        const val MEMORY_ENTRY_BYTES = 16L * 1024 * 1024

        /** Opens a plain TAR, or a gzip-compressed one when [gzip]: reads the whole index first. */
        fun open(channel: FileChannel, gzip: Boolean): TarDocumentSource {
            val input: InputStream = PositionalInput(channel).buffered(64 * 1024)
            val index = TarArchive.index(if (gzip) GZIPInputStream(input, 64 * 1024) else input)
            return TarDocumentSource(channel, index, gzip)
        }
    }
}

/**
 * Reads [channel] from byte 0 with positional reads and a position of its own, so several
 * streams and handles over one channel never move each other (a Channels.newInputStream would
 * share the channel's position).
 */
internal class PositionalInput(private val channel: FileChannel) : InputStream() {
    private var position = 0L
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = channel.read(ByteBuffer.wrap(b, off, len), position)
        if (n > 0) position += n
        return n
    }
    override fun skip(n: Long): Long {
        val s = minOf(n, maxOf(0L, channel.size() - position))
        position += s
        return s
    }
}

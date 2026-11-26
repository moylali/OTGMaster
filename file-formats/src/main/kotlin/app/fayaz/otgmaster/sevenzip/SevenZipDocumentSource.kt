package app.fayaz.otgmaster.sevenzip

import app.fayaz.otgmaster.core.DocumentHandle
import app.fayaz.otgmaster.core.DocumentNode
import app.fayaz.otgmaster.core.DocumentSource
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.IOException
import java.io.InputStream
import java.nio.channels.SeekableByteChannel

/**
 * A 7z archive as a read-only [DocumentSource], read with Apache Commons Compress (LZMA, LZMA2,
 * Deflate, BZip2, PPMd and the AES-256 encryption 7-Zip offers, with [password]).
 *
 * 7z compresses files together in solid blocks, so there is no seeking into the middle of a
 * file's bytes: a file up to [MEMORY_ENTRY_BYTES] is decoded whole on its first read and served
 * from memory; a larger one is decoded as a stream, a read behind its position restarting it.
 * SevenZFile moves one shared channel position, so every read of the archive holds its lock.
 * Each file's CRC-32, where the archive records one, is checked by Commons Compress as its
 * stream ends.
 *
 * Names are untrusted, as for ZIP and TAR: an empty, ".", ".." or absolute component leaves the
 * entry out ([skippedUnsafe]); anti-items (7-Zip's deletion markers) are left out too.
 */
class SevenZipDocumentSource private constructor(private val file: SevenZFile) : DocumentSource {

    class Node internal constructor(val path: List<String>, override val isDirectory: Boolean, val entry: SevenZArchiveEntry?) : DocumentNode {
        override val name: String get() = path.lastOrNull() ?: ""
        override val isRoot: Boolean get() = path.isEmpty()
        override val size: Long get() = if (isDirectory) 0 else entry?.size ?: 0
        override val lastModified: Long get() = entry?.takeIf { it.hasLastModifiedDate }?.lastModifiedDate?.time ?: 0
    }

    private val root = Node(emptyList(), true, null)
    private val children: Map<List<String>, List<Node>>
    var skippedUnsafe = 0; private set

    init {
        val nodes = LinkedHashMap<List<String>, Node>()
        for (e in file.entries) {
            if (e.isAntiItem) continue
            val path = safePath(e.name) ?: run { skippedUnsafe++; null } ?: continue
            for (i in 1 until path.size) { val dir = path.subList(0, i); if (dir !in nodes) nodes[dir] = Node(dir, true, null) }
            nodes[path] = Node(path, e.isDirectory, e)
        }
        val files = nodes.values.filter { !it.isDirectory }.map { it.path }.toHashSet()
        for (n in nodes.values) for (i in 1 until n.path.size) if (n.path.subList(0, i) in files)
            throw IOException("${n.path.subList(0, i).joinToString("/")} is both a file and a folder; refused")
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
        if (!e.hasStream()) return InMemory(ByteArray(0))
        return if (e.size <= MEMORY_ENTRY_BYTES) InMemory(decodeWhole(e)) else Streamed(e)
    }

    private fun decodeWhole(e: SevenZArchiveEntry): ByteArray = synchronized(file) {
        try {
            file.getInputStream(e).use { it.readNBytes(e.size.toInt()) }.also {
                if (it.size.toLong() != e.size) throw IOException("${e.name} is damaged: it decodes to ${it.size} bytes, not ${e.size}")
            }
        } catch (x: IOException) { throw IOException(reason(e, x), x) }
    }

    private fun reason(e: SevenZArchiveEntry, x: Exception): String = when {
        x.javaClass.simpleName == "PasswordRequiredException" -> "${e.name} is encrypted: the archive's password is needed"
        (x.message ?: "").contains("CRC", ignoreCase = true) -> "${e.name} is damaged, or the password is wrong: its checksum does not match"
        else -> "${e.name} could not be decoded: ${x.message}"
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

    private inner class InMemory(private val bytes: ByteArray) : ReadOnly() {
        override fun size() = bytes.size.toLong()
        override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
            if (offset >= bytes.size) return 0
            val n = minOf(length.toLong(), bytes.size - offset).toInt()
            System.arraycopy(bytes, offset.toInt(), buffer, 0, n)
            return n
        }
    }

    private inner class Streamed(private val e: SevenZArchiveEntry) : ReadOnly() {
        private var input: InputStream? = null
        private var position = 0L
        private val drop = ByteArray(64 * 1024)
        override fun size() = e.size
        override fun read(offset: Long, buffer: ByteArray, length: Int): Int = synchronized(file) {
            if (offset >= e.size) return 0
            try {
                var s = input
                if (s == null || position > offset) { s?.close(); s = file.getInputStream(e); input = s; position = 0 }
                while (position < offset) {
                    val n = s!!.read(drop, 0, minOf(drop.size.toLong(), offset - position).toInt())
                    if (n < 0) throw IOException("${e.name} ended early")
                    position += n
                }
                val want = minOf(length.toLong(), e.size - offset).toInt()
                var total = 0
                while (total < want) {
                    val n = s!!.read(buffer, total, want - total)
                    if (n < 0) throw IOException("${e.name} ended early")
                    total += n; position += n
                }
                total
            } catch (x: IOException) {
                input?.close(); input = null
                throw IOException(reason(e, x), x)
            }
        }
        override fun close() = synchronized(file) { input?.close(); input = null }
    }

    companion object {
        const val READ_ONLY = "7z archives open read-only"
        const val MEMORY_ENTRY_BYTES = 16L * 1024 * 1024

        /**
         * Opens the 7z archive in [channel], with [password] if it is encrypted (empty for none).
         * An archive whose list of files is itself encrypted needs the password here; one that
         * encrypts only the contents opens without it and asks at the first read.
         */
        fun open(channel: SeekableByteChannel, password: CharArray = CharArray(0)): SevenZipDocumentSource {
            val b = SevenZFile.builder().setSeekableByteChannel(channel)
            if (password.isNotEmpty()) b.setPassword(password)
            return SevenZipDocumentSource(b.get())
        }

        internal fun safePath(name: String?): List<String>? {
            if (name.isNullOrEmpty() || name.startsWith("/") || name.startsWith("\\") || '\u0000' in name) return null
            val parts = name.replace('\\', '/').trimEnd('/').split('/')
            if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
            return parts
        }
    }

    fun close() = file.close()
}

package app.fayaz.otgmaster.disk

import app.fayaz.otgmaster.core.DocumentHandle
import app.fayaz.otgmaster.core.DocumentNode
import app.fayaz.otgmaster.core.DocumentSource
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import java.nio.ByteBuffer

/**
 * [DocumentSource] over a mounted libaums [FileSystem]: FAT32 through libaums, and exFAT,
 * ext4, NTFS, APFS and restic snapshots through their UsbFile implementations.
 *
 * Lookup lists the folder and matches the name exactly, which is what the provider did
 * before this adapter existed (`getFileForDocId`). The drivers' own `search` is not used:
 * exFAT matches without case and NTFS the way Windows does, and document IDs must name
 * one entry exactly. A cheaper lookup needs invalidation on every write, including the
 * app's own, so it waits for the engine to own all writes (architecture proposal, F6;
 * Milestone 2).
 */
class UsbFileDocumentSource(
    private val fileSystem: FileSystem,
    private val readOnly: () -> String?,
) : DocumentSource {

    class Node(val file: UsbFile) : DocumentNode {
        override val name: String get() = file.name
        override val isDirectory: Boolean get() = file.isDirectory
        override val isRoot: Boolean get() = file.isRoot
        override val size: Long get() = runCatching { if (file.isDirectory) 0L else file.length }.getOrDefault(0L)
        override val lastModified: Long get() = runCatching { file.lastModified() }.getOrDefault(0L)
    }

    /** A [DocumentHandle] over a libaums file: what the provider's descriptor callbacks call. */
    class Handle(val file: UsbFile) : DocumentHandle {
        override fun size(): Long = file.length

        /**
         * The provider's onRead used to return the requested length unconditionally, so a short
         * or failed read handed the client whatever the recycled SAF buffer held, presented as
         * file content. The ByteBuffer's final position is the honest count.
         */
        override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
            val fileLength = file.length
            if (offset >= fileLength) return 0
            val toRead = minOf(length.toLong(), fileLength - offset).toInt()
            val b = ByteBuffer.wrap(buffer, 0, toRead)
            file.read(offset, b)
            return b.position()
        }

        override fun write(offset: Long, buffer: ByteArray, length: Int) {
            file.write(offset, ByteBuffer.wrap(buffer, 0, length))
        }

        override fun truncate() {
            file.length = 0
        }

        override fun flush() = file.flush()

        override fun close() = file.close()
    }

    override val readOnlyReason: String? get() = readOnly()

    override fun root(): DocumentNode = Node(fileSystem.rootDirectory)

    override fun child(dir: DocumentNode, name: String): DocumentNode? =
        dir.file.listFiles().firstOrNull { it.name == name }?.let(::Node)

    override fun children(dir: DocumentNode): List<DocumentNode> =
        dir.file.listFiles().map(::Node)

    override fun open(node: DocumentNode): DocumentHandle = Handle(node.file)

    override fun createFile(dir: DocumentNode, name: String): DocumentNode = Node(dir.file.createFile(name))

    override fun createDirectory(dir: DocumentNode, name: String): DocumentNode = Node(dir.file.createDirectory(name))

    override fun rename(node: DocumentNode, name: String) {
        node.file.name = name
    }

    override fun delete(node: DocumentNode) = node.file.delete()

    companion object {
        /** The libaums file behind a node of this source. */
        val DocumentNode.file: UsbFile get() = (this as Node).file
    }
}

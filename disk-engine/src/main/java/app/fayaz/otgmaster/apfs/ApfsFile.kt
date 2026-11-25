package app.fayaz.otgmaster.apfs

import me.jahnen.libaums.core.fs.UsbFile
import java.io.IOException
import java.nio.ByteBuffer

/**
 * A file or directory on an [ApfsFileSystem], named by its inode number.
 *
 * Holds no native resource, so [close] has nothing to release. Every operation that
 * would change the volume throws: APFS is mounted read-only.
 */
class ApfsFile internal constructor(
    private val fs: ApfsFileSystem,
    override val parent: ApfsFile?,
    private val node: ApfsNode,
) : UsbFile {

    override val isDirectory: Boolean get() = node.isDirectory

    override val isRoot: Boolean get() = parent == null

    override var name: String
        get() = if (isRoot) "" else node.name
        set(_) = fs.readOnly()

    override val absolutePath: String
        get() = when {
            isRoot -> UsbFile.separator
            parent!!.isRoot -> UsbFile.separator + name
            else -> parent.absolutePath + UsbFile.separator + name
        }

    override var length: Long
        get() = node.size
        set(_) = fs.readOnly()

    override fun createdAt(): Long = node.createdAt
    override fun lastModified(): Long = node.lastModified
    override fun lastAccessed(): Long = node.lastAccessed

    override fun search(path: String): UsbFile? {
        var current: ApfsFile = this
        for (part in path.split(UsbFile.separator).filter { it.isNotEmpty() }) {
            if (!current.isDirectory) return null
            current = fs.match(fs.listDir(current.node.id), part)?.let { ApfsFile(fs, current, it) } ?: return null
        }
        return current
    }

    override fun list(): Array<String> = listFiles().map { it.name }.toTypedArray()

    override fun listFiles(): Array<UsbFile> {
        if (!isDirectory) throw IOException("Not a directory: $absolutePath")
        return fs.listDir(node.id).map { ApfsFile(fs, this, it) }.toTypedArray()
    }

    override fun read(offset: Long, destination: ByteBuffer) {
        if (isDirectory) throw IOException("Cannot read a directory as a file")
        val size = destination.remaining()
        if (size == 0) return
        val direct = destination.hasArray()
        val target = if (direct) destination.array() else ByteArray(size)
        val targetOffset = if (direct) destination.arrayOffset() + destination.position() else 0
        val n = fs.withNative { h -> ApfsNative.readFile(h, node.id, offset, size, target, targetOffset) }
        // A failure must not look like a short read (see NtfsFile.read).
        if (n < 0) fs.error("APFS read of $absolutePath at $offset ($size bytes) failed", n)
        if (direct) destination.position(destination.position() + n)
        else destination.put(target, 0, n)
    }

    override fun write(offset: Long, source: ByteBuffer) = fs.readOnly()
    override fun flush() {}
    override fun close() {}
    override fun createDirectory(name: String): UsbFile = fs.readOnly()
    override fun createFile(name: String): UsbFile = fs.readOnly()
    override fun moveTo(destination: UsbFile) = fs.readOnly()
    override fun delete() = fs.readOnly()
}

package app.fayaz.otgmaster.fs.apfs

import me.jahnen.libaums.core.fs.UsbFile
import java.io.IOException
import java.nio.ByteBuffer

class ApfsFile(
    private val fs: ApfsFileSystem,
    private val path: String
) : UsbFile {

    private val isRoot = path == "/"
    
    override val isDirectory: Boolean
        get() = ApfsNative.isDirectory(fs.contextPtr, path)

    override var name: String
        get() = if (isRoot) "/" else path.substringAfterLast("/")
        set(value) { throw IOException("Read-only file system") }

    override val absolutePath: String = path

    override val parent: UsbFile?
        get() {
            if (isRoot) return null
            val parentPath = path.substringBeforeLast("/")
            return ApfsFile(fs, if (parentPath.isEmpty()) "/" else parentPath)
        }

    override var length: Long
        get() = ApfsNative.getFileSize(fs.contextPtr, path)
        set(value) { throw IOException("Read-only file system") }

    override val isRoot: Boolean = this.isRoot

    override fun search(path: String): UsbFile? {
        val targetPath = if (isRoot) "/$path" else "${this.path}/$path"
        // Try getting size, if it doesn"t fail maybe it exists. Wait, listDir is safer.
        val dir = targetPath.substringBeforeLast("/")
        val name = targetPath.substringAfterLast("/")
        val list = ApfsNative.listDirectory(fs.contextPtr, if (dir.isEmpty()) "/" else dir) ?: return null
        if (list.contains(name)) return ApfsFile(fs, targetPath)
        return null
    }

    override fun createdAt(): Long = 0L
    override fun lastModified(): Long = 0L
    override fun lastAccessed(): Long = 0L

    override fun list(): Array<String> {
        if (!isDirectory) throw IOException("Not a directory")
        return ApfsNative.listDirectory(fs.contextPtr, path) ?: emptyArray()
    }

    override fun listFiles(): Array<UsbFile> {
        val names = list()
        return names.map { ApfsFile(fs, if (isRoot) "/$it" else "$path/$it") }.toTypedArray()
    }

    override fun read(offset: Long, destination: ByteBuffer) {
        if (isDirectory) throw IOException("Cannot read a directory")
        val size = destination.remaining()
        val read = ApfsNative.readFile(fs.contextPtr, path, offset, destination, size)
        if (read < 0) throw IOException("Failed to read file")
        destination.position(destination.position() + read)
    }

    override fun write(offset: Long, source: ByteBuffer) { throw IOException("Read-only file system") }
    override fun flush() {}
    override fun close() {}
    override fun createDirectory(name: String): UsbFile { throw IOException("Read-only file system") }
    override fun createFile(name: String): UsbFile { throw IOException("Read-only file system") }
    override fun moveTo(destination: UsbFile) { throw IOException("Read-only file system") }
    override fun delete() { throw IOException("Read-only file system") }
}

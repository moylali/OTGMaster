package app.fayaz.otgmaster.exfat

import me.jahnen.libaums.core.fs.UsbFile
import java.io.IOException
import java.nio.ByteBuffer

class ExFatFile(
    private val fileSystem: ExFatFileSystem,
    override val parent: UsbFile?,
    private val node: ExFatNode
) : UsbFile {

    override val isDirectory: Boolean
        get() = node.isDirectory

    override var name: String
        get() = node.name
        set(value) {
            val parentPath = parent?.absolutePath ?: ""
            val newPath = if (parentPath == UsbFile.separator) "/$value" else "$parentPath/$value"
            val rc = ExFatNative.rename(fileSystem.exfatPtr, absolutePath, newPath)
            if (rc != 0) throw IOException("Failed to rename exFAT file: $rc")
            node.name = value
        }

    override val absolutePath: String
        get() = if (isRoot) UsbFile.separator else parent!!.absolutePath + (if (parent.isRoot) "" else UsbFile.separator) + name

    override var length: Long
        get() = node.size
        set(value) {
            if (isDirectory) throw IOException("Cannot set length on directory")
            val rc = ExFatNative.setLength(fileSystem.exfatPtr, node.nodePtr, value)
            if (rc != 0) throw IOException("Failed to set length on exFAT file: $rc")
            node.size = value
        }

    override val isRoot: Boolean
        get() = parent == null

    override fun search(path: String): UsbFile? {
        val parts = path.split(UsbFile.separator).filter { it.isNotEmpty() }
        var current: UsbFile = this
        for (part in parts) {
            if (!current.isDirectory) return null
            val children = current.listFiles()
            val child = children.find { it.name.equals(part, ignoreCase = true) }
            if (child == null) return null
            current = child
        }
        return current
    }

    override fun createdAt(): Long = node.createdAt

    override fun lastModified(): Long = node.lastModified

    override fun lastAccessed(): Long = node.lastModified // Fallback

    override fun list(): Array<String> {
        return listFiles().map { it.name }.toTypedArray()
    }

    override fun listFiles(): Array<UsbFile> {
        if (!isDirectory) throw IOException("Not a directory")
        val nodes = ExFatNative.readDir(fileSystem.exfatPtr, node.nodePtr)
        return nodes.map { ExFatFile(fileSystem, this, it) }.toTypedArray()
    }

    override fun read(offset: Long, destination: ByteBuffer) {
        if (isDirectory) throw IOException("Cannot read directory as file")
        val size = destination.remaining()
        val tempBuffer = ByteArray(size)
        val bytesRead = ExFatNative.readFile(fileSystem.exfatPtr, node.nodePtr, offset, size, tempBuffer)
        if (bytesRead > 0) {
            destination.put(tempBuffer, 0, bytesRead)
        }
    }

    override fun write(offset: Long, source: ByteBuffer) {
        if (isDirectory) throw IOException("Cannot write to directory")
        val size = source.remaining()
        val tempBuffer = ByteArray(size)
        source.get(tempBuffer)
        val bytesWritten = ExFatNative.writeFile(fileSystem.exfatPtr, node.nodePtr, offset, size, tempBuffer)
        if (bytesWritten < 0) {
            throw IOException("Failed to write to exFAT file: $bytesWritten")
        }
    }

    override fun flush() {
        ExFatNative.flush(fileSystem.exfatPtr)
    }

    override fun close() {
        flush()
    }

    override fun createDirectory(name: String): UsbFile {
        if (!isDirectory) throw IOException("Cannot create directory inside a file")
        val newPath = if (absolutePath == UsbFile.separator) "/$name" else "$absolutePath/$name"
        val rc = ExFatNative.createDirectory(fileSystem.exfatPtr, newPath)
        if (rc != 0) throw IOException("Failed to create exFAT directory: $rc")
        return search(name) ?: throw IOException("Failed to find newly created exFAT directory")
    }

    override fun createFile(name: String): UsbFile {
        if (!isDirectory) throw IOException("Cannot create file inside a file")
        val newPath = if (absolutePath == UsbFile.separator) "/$name" else "$absolutePath/$name"
        val rc = ExFatNative.createFile(fileSystem.exfatPtr, newPath)
        if (rc != 0) throw IOException("Failed to create exFAT file: $rc")
        return search(name) ?: throw IOException("Failed to find newly created exFAT file")
    }

    override fun moveTo(destination: UsbFile) {
        val newPath = if (destination.absolutePath == UsbFile.separator) "/$name" else "${destination.absolutePath}/$name"
        val rc = ExFatNative.rename(fileSystem.exfatPtr, absolutePath, newPath)
        if (rc != 0) throw IOException("Failed to move exFAT file: $rc")
    }

    override fun delete() {
        val rc = ExFatNative.deleteNode(fileSystem.exfatPtr, node.nodePtr)
        if (rc != 0) throw IOException("Failed to delete exFAT file/dir: $rc")
    }
}

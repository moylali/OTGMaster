package com.otgmaster.exfat

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
            throw IOException("Rename not implemented yet for exFAT prototype.")
        }

    override val absolutePath: String
        get() = if (isRoot) UsbFile.separator else parent!!.absolutePath + (if (parent.isRoot) "" else UsbFile.separator) + name

    override var length: Long
        get() = node.size
        set(value) {
            throw IOException("Setting length not implemented yet for exFAT prototype.")
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
        throw IOException("Write not implemented yet for exFAT prototype.")
    }

    override fun flush() {}

    override fun close() {}

    override fun createDirectory(name: String): UsbFile {
        throw IOException("createDirectory not implemented yet for exFAT prototype.")
    }

    override fun createFile(name: String): UsbFile {
        throw IOException("createFile not implemented yet for exFAT prototype.")
    }

    override fun moveTo(destination: UsbFile) {
        throw IOException("moveTo not implemented yet for exFAT prototype.")
    }

    override fun delete() {
        throw IOException("delete not implemented yet for exFAT prototype.")
    }
}

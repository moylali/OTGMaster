package app.fayaz.otgmaster.exfat

import me.jahnen.libaums.core.fs.UsbFile
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock

class ExFatFile(
    private val fileSystem: ExFatFileSystem,
    override val parent: UsbFile?,
    private val node: ExFatNode
) : UsbFile {
    private var isClosed = false

    /**
     * Whether this handle has mutated the volume. close() used to flush the whole
     * filesystem unconditionally, so merely reading a file paid for a metadata
     * flush — and did so while holding the lock.
     */
    @Volatile private var dirty = false

    private fun checkNotClosed() {
        if (isClosed) throw IOException("File is closed")
    }

    override val isDirectory: Boolean
        get() { checkNotClosed(); return node.isDirectory }

    override var name: String
        get() { checkNotClosed(); return node.name }
        set(value) {
            checkNotClosed()
            val parentPath = parent?.absolutePath ?: ""
            val newPath = if (parentPath == UsbFile.separator) "/$value" else "$parentPath/$value"
            val rc = fileSystem.lock.withLock {
                ExFatNative.rename(fileSystem.exfatPtr, absolutePath, newPath)
            }
            if (rc != 0) throw IOException("Failed to rename exFAT file: $rc")
            dirty = true
            node.name = value
        }

    override val absolutePath: String
        get() { checkNotClosed(); return if (isRoot) UsbFile.separator else parent!!.absolutePath + (if (parent.isRoot) "" else UsbFile.separator) + name }

    override var length: Long
        get() { checkNotClosed(); return node.size }
        set(value) {
            checkNotClosed()
            if (isDirectory) throw IOException("Cannot set length on directory")
            val rc = fileSystem.lock.withLock {
                ExFatNative.setLength(fileSystem.exfatPtr, node.nodePtr, value)
            }
            if (rc != 0) throw IOException("Failed to set length on exFAT file: $rc")
            dirty = true
            node.size = value
        }

    override val isRoot: Boolean
        get() = parent == null

    override fun search(path: String): UsbFile? {
        checkNotClosed()
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

    override fun createdAt(): Long { checkNotClosed(); return node.createdAt }

    override fun lastModified(): Long { checkNotClosed(); return node.lastModified }

    override fun lastAccessed(): Long { checkNotClosed(); return node.lastModified } // Fallback

    override fun list(): Array<String> {
        checkNotClosed()
        return listFiles().map { it.name }.toTypedArray()
    }

    override fun listFiles(): Array<UsbFile> {
        checkNotClosed()
        if (!isDirectory) throw IOException("Not a directory")
        val nodes = fileSystem.lock.withLock {
            ExFatNative.readDir(fileSystem.exfatPtr, node.nodePtr)
        } ?: return emptyArray()
        return nodes.map { ExFatFile(fileSystem, this, it) }.toTypedArray()
    }

    override fun read(offset: Long, destination: ByteBuffer) {
        checkNotClosed()
        if (isDirectory) throw IOException("Cannot read directory as file")
        val size = destination.remaining()
        val tempBuffer = ByteArray(size)
        val bytesRead = fileSystem.lock.withLock {
            ExFatNative.readFile(fileSystem.exfatPtr, node.nodePtr, offset, size, tempBuffer)
        }
        if (bytesRead > 0) {
            destination.put(tempBuffer, 0, bytesRead)
        }
    }

    override fun write(offset: Long, source: ByteBuffer) {
        checkNotClosed()
        if (isDirectory) throw IOException("Cannot write to directory")
        val size = source.remaining()
        val tempBuffer = ByteArray(size)
        source.get(tempBuffer)
        val bytesWritten = fileSystem.lock.withLock {
            ExFatNative.writeFile(fileSystem.exfatPtr, node.nodePtr, offset, size, tempBuffer)
        }
        if (bytesWritten < 0) {
            throw IOException("Failed to write to exFAT file: $bytesWritten")
        }
        dirty = true
    }



    override fun flush() {
        if (isClosed) return
        fileSystem.lock.withLock {
            if (!isClosed && !fileSystem.isUnmounted) {
                ExFatNative.flush(fileSystem.exfatPtr)
            }
        }
    }

    override fun close() {
        if (isClosed) return
        fileSystem.lock.withLock {
            if (isClosed) return
            isClosed = true
            if (!fileSystem.isUnmounted) {
                // Only flush if this handle mutated the volume. Flushing on every
                // close made read-only access pay for a metadata write.
                if (dirty) {
                    ExFatNative.flush(fileSystem.exfatPtr)
                    dirty = false
                }
                // Root node ref count is NOT incremented by getRootNode (it just wraps the
                // pointer), so putNode must not be called on root — exfat_unmount handles it.
                if (!isRoot) {
                    ExFatNative.putNode(fileSystem.exfatPtr, node.nodePtr)
                }
            }
        }
    }

    override fun createDirectory(name: String): UsbFile {
        checkNotClosed()
        if (!isDirectory) throw IOException("Cannot create directory inside a file")
        val newPath = if (absolutePath == UsbFile.separator) "/$name" else "$absolutePath/$name"
        val rc = fileSystem.lock.withLock {
            ExFatNative.createDirectory(fileSystem.exfatPtr, newPath)
        }
        if (rc != 0) throw IOException("Failed to create exFAT directory: $rc")
        dirty = true
        return search(name) ?: throw IOException("Failed to find newly created exFAT directory")
    }

    override fun createFile(name: String): UsbFile {
        checkNotClosed()
        if (!isDirectory) throw IOException("Cannot create file inside a file")
        val newPath = if (absolutePath == UsbFile.separator) "/$name" else "$absolutePath/$name"
        val rc = fileSystem.lock.withLock {
            ExFatNative.createFile(fileSystem.exfatPtr, newPath)
        }
        if (rc != 0) throw IOException("Failed to create exFAT file: $rc")
        dirty = true
        return search(name) ?: throw IOException("Failed to find newly created exFAT file")
    }

    override fun moveTo(destination: UsbFile) {
        checkNotClosed()
        val newPath = if (destination.absolutePath == UsbFile.separator) "/$name" else "${destination.absolutePath}/$name"
        val rc = fileSystem.lock.withLock {
            ExFatNative.rename(fileSystem.exfatPtr, absolutePath, newPath)
        }
        if (rc != 0) throw IOException("Failed to move exFAT file: $rc")
        dirty = true
    }

    override fun delete() {
        checkNotClosed()
        val rc = fileSystem.lock.withLock {
            ExFatNative.deleteNode(fileSystem.exfatPtr, node.nodePtr)
        }
        if (rc != 0) throw IOException("Failed to delete exFAT file/dir: $rc")
        dirty = true
    }

    /**
     * Releases the native node if it can be done promptly.
     *
     * Must never block: finalizers run on a watchdog-monitored daemon thread
     * that kills the process when a single finalize() exceeds 10 seconds, and
     * close() both takes the filesystem lock and (previously) flushed — either
     * of which can sit behind a multi-second USB transfer. Listing a
     * 10,000-entry directory crashed the app exactly this way:
     *
     *   FATAL EXCEPTION: FinalizerWatchdogDaemon
     *   TimeoutException: ExFatFile.finalize() timed out after 10 seconds
     *
     * If the lock cannot be taken quickly the node is left for exfat_unmount to
     * reclaim — a bounded leak until unmount, which is strictly better than
     * killing the process.
     */
    protected fun finalize() {
        if (isClosed || isRoot) return
        try {
            if (!fileSystem.lock.tryLock(FINALIZE_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return
            try {
                if (isClosed || fileSystem.isUnmounted) return
                isClosed = true
                ExFatNative.putNode(fileSystem.exfatPtr, node.nodePtr)
            } finally {
                fileSystem.lock.unlock()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Throwable) {
            // A throwing finalizer would also take the process down.
        }
    }

    private companion object {
        /** Far below the 10s watchdog budget, leaving room for a queue of finalizers. */
        const val FINALIZE_LOCK_TIMEOUT_MS = 250L
    }
}

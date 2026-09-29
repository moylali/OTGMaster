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
            fileSystem.checkWritable()
            val parentPath = parent?.absolutePath ?: ""
            val newPath = if (parentPath == UsbFile.separator) "/$value" else "$parentPath/$value"
            val rc = fileSystem.withNative {
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
            fileSystem.checkWritable()
            val rc = fileSystem.withNative {
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
        // null means the native call failed; an empty directory comes back as a
        // zero-length array. Mapping null to emptyArray() reported every failure —
        // a bad sector, a pulled drive — as "this directory is empty", which is a
        // worse answer than an error: a SAF client concludes the files were deleted,
        // and anything syncing or mirroring the directory would act on that.
        val nodes = fileSystem.withNative {
            ExFatNative.readDir(fileSystem.exfatPtr, node.nodePtr)
        } ?: throw IOException("exFAT directory listing failed for $absolutePath")
        return nodes.map { ExFatFile(fileSystem, this, it) }.toTypedArray()
    }

    override fun read(offset: Long, destination: ByteBuffer) {
        checkNotClosed()
        if (isDirectory) throw IOException("Cannot read directory as file")
        val size = destination.remaining()
        // Read straight into the destination's backing array when it has one, which
        // removes the intermediate ByteArray and the copy out of it. Only a direct
        // ByteBuffer needs the fallback.
        val direct = destination.hasArray()
        val target = if (direct) destination.array() else ByteArray(size)
        val targetOffset = if (direct) destination.arrayOffset() + destination.position() else 0
        val bytesRead = fileSystem.withNative {
            ExFatNative.readFile(
                fileSystem.exfatPtr, node.nodePtr, offset, size, target, targetOffset,
            )
        }
        // A negative return is a native I/O failure (bad sector, device pulled).
        // Dropping it silently left destination untouched, and the SAF callback
        // reports the full requested length as read regardless — so the client
        // received whatever the recycled buffer already held, as if it were file
        // content. Fail loudly instead.
        if (bytesRead < 0) {
            throw IOException("exFAT read failed at offset $offset ($size bytes): $bytesRead")
        }
        if (bytesRead > 0) {
            if (direct) {
                destination.position(destination.position() + bytesRead)
            } else {
                destination.put(target, 0, bytesRead)
            }
        }
    }

    override fun write(offset: Long, source: ByteBuffer) {
        checkNotClosed()
        fileSystem.checkWritable()
        if (isDirectory) throw IOException("Cannot write to directory")
        val size = source.remaining()
        val tempBuffer = ByteArray(size)
        source.get(tempBuffer)

        // Loop until everything lands. A short write (0 < n < size) used to be
        // treated as success: source.position() had already advanced by the full
        // amount, so the unwritten tail was gone with nothing reported. libexfat can
        // return short at a cluster boundary or when the volume fills.
        var written = 0
        while (written < size) {
            val chunk = size - written
            val n = fileSystem.withNative {
                ExFatNative.writeFile(
                    fileSystem.exfatPtr, node.nodePtr, offset + written, chunk,
                    if (written == 0) tempBuffer else tempBuffer.copyOfRange(written, size),
                )
            }
            if (n < 0) {
                throw IOException(
                    "exFAT write failed at offset ${offset + written} " +
                    "($written of $size bytes written): $n"
                )
            }
            if (n == 0) {
                throw IOException(
                    "exFAT write stalled at offset ${offset + written} " +
                    "($written of $size bytes written) — volume full?"
                )
            }
            written += n
        }
        val bytesWritten = written
        // libexfat has grown the file on disk, but node.size is a cached Kotlin
        // field. Leaving it stale makes appended data invisible: SAF bounds every
        // read by file.length, so onRead returns 0 (EOF) for the new tail and the
        // client sees a truncated file.
        val end = offset + bytesWritten
        if (end > node.size) node.size = end
        dirty = true
    }



    override fun flush() {
        if (isClosed) return
        fileSystem.lock.withLock {
            if (!isClosed && !fileSystem.isUnmounted && !fileSystem.readOnly) {
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
        fileSystem.checkWritable()
        if (!isDirectory) throw IOException("Cannot create directory inside a file")
        val newPath = if (absolutePath == UsbFile.separator) "/$name" else "$absolutePath/$name"
        val rc = fileSystem.withNative {
            ExFatNative.createDirectory(fileSystem.exfatPtr, newPath)
        }
        if (rc != 0) throw IOException("Failed to create exFAT directory: $rc")
        dirty = true
        return search(name) ?: throw IOException("Failed to find newly created exFAT directory")
    }

    override fun createFile(name: String): UsbFile {
        checkNotClosed()
        fileSystem.checkWritable()
        if (!isDirectory) throw IOException("Cannot create file inside a file")
        val newPath = if (absolutePath == UsbFile.separator) "/$name" else "$absolutePath/$name"
        val rc = fileSystem.withNative {
            ExFatNative.createFile(fileSystem.exfatPtr, newPath)
        }
        if (rc != 0) throw IOException("Failed to create exFAT file: $rc")
        dirty = true
        return search(name) ?: throw IOException("Failed to find newly created exFAT file")
    }

    override fun moveTo(destination: UsbFile) {
        checkNotClosed()
        fileSystem.checkWritable()
        val newPath = if (destination.absolutePath == UsbFile.separator) "/$name" else "${destination.absolutePath}/$name"
        val rc = fileSystem.withNative {
            ExFatNative.rename(fileSystem.exfatPtr, absolutePath, newPath)
        }
        if (rc != 0) throw IOException("Failed to move exFAT file: $rc")
        dirty = true
    }

    override fun delete() {
        checkNotClosed()
        fileSystem.checkWritable()
        val rc = fileSystem.withNative {
            ExFatNative.deleteNode(fileSystem.exfatPtr, node.nodePtr)
        }
        if (rc != 0) throw IOException("Failed to delete exFAT file/dir: $rc")
        dirty = true
    }

    /**
     * Queues the native node for release on a normal thread.
     *
     * A finalizer must do no work that can block. Finalizers run on a
     * watchdog-monitored daemon that kills the process when one finalize() exceeds ten
     * seconds, and releasing a node means flushing it, which means a USB write. See
     * ExFatFileSystem.pendingReleases for the failure this replaces.
     */
    protected fun finalize() {
        if (isClosed || isRoot) return
        // Hand off and return immediately. Doing the release here is what killed the
        // process on a slow drive: exfat_put_node flushes the node, that is a USB
        // write, and on a Huawei P20 Lite (0.43 MB/s) it exceeded the ten-second
        // finalizer budget. Taking the lock with a timeout, as this used to, bounded
        // only the wait — not the transfer that follows it.
        isClosed = true
        runCatching { fileSystem.releaseNodeLater(node.nodePtr) }
    }

    private companion object {
        /** Far below the 10s watchdog budget, leaving room for a queue of finalizers. */
        const val FINALIZE_LOCK_TIMEOUT_MS = 250L
    }
}

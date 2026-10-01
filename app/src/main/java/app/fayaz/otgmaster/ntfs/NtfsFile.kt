package app.fayaz.otgmaster.ntfs

import me.jahnen.libaums.core.fs.UsbFile
import java.io.IOException
import java.nio.ByteBuffer

/**
 * A file or directory on an [NtfsFileSystem], named by its MFT reference.
 *
 * Holds no native resource, so [close] has nothing to release and there is no
 * finalizer. [node] caches size and times from the last listing; this handle's
 * own writes keep it current.
 */
class NtfsFile internal constructor(
    private val fs: NtfsFileSystem,
    override val parent: NtfsFile?,
    private val node: NtfsNode,
) : UsbFile {

    override val isDirectory: Boolean get() = node.isDirectory

    override val isRoot: Boolean get() = parent == null

    override var name: String
        get() = if (isRoot) "" else node.name
        set(value) = rename(parent ?: throw IOException("Cannot rename the root"), value)

    override val absolutePath: String
        get() = when {
            isRoot -> UsbFile.separator
            parent!!.isRoot -> UsbFile.separator + name
            else -> parent.absolutePath + UsbFile.separator + name
        }

    override var length: Long
        get() = node.size
        set(value) {
            if (isDirectory) throw IOException("Cannot set length on a directory")
            fs.checkWritable()
            val rc = fs.withNative { h -> NtfsNative.setLength(h, node.mref, value) }
            if (rc != 0) fs.error("NTFS truncate of $absolutePath to $value failed", rc)
            node.size = value
            fs.invalidateListings()
        }

    override fun createdAt(): Long = node.createdAt
    override fun lastModified(): Long = node.lastModified
    override fun lastAccessed(): Long = node.lastAccessed

    override fun search(path: String): UsbFile? {
        var current: NtfsFile = this
        for (part in path.split(UsbFile.separator).filter { it.isNotEmpty() }) {
            if (!current.isDirectory) return null
            current = current.child(part) ?: return null
        }
        return current
    }

    /** The entry called [childName] in this directory, matched as Windows would. */
    private fun child(childName: String): NtfsFile? {
        val entries = fs.listDir(node.mref)
        // Exact first: the POSIX namespace can hold names differing only in case.
        val match = entries.firstOrNull { it.name == childName }
            ?: entries.firstOrNull { it.name.equals(childName, ignoreCase = true) }
        return match?.let { NtfsFile(fs, this, it) }
    }

    override fun list(): Array<String> = listFiles().map { it.name }.toTypedArray()

    override fun listFiles(): Array<UsbFile> {
        if (!isDirectory) throw IOException("Not a directory: $absolutePath")
        return fs.listDir(node.mref).map { NtfsFile(fs, this, it) }.toTypedArray()
    }

    override fun read(offset: Long, destination: ByteBuffer) {
        if (isDirectory) throw IOException("Cannot read a directory as a file")
        val size = destination.remaining()
        if (size == 0) return
        // Straight into the destination's array when it has one.
        val direct = destination.hasArray()
        val target = if (direct) destination.array() else ByteArray(size)
        val targetOffset = if (direct) destination.arrayOffset() + destination.position() else 0
        val n = fs.withNative { h ->
            NtfsNative.readFile(h, node.mref, offset, size, target, targetOffset)
        }
        // A failure must not look like a short read: the provider reports the
        // requested length regardless, and the client would receive whatever the
        // recycled buffer held as if it were file content.
        if (n < 0) fs.error("NTFS read of $absolutePath at $offset ($size bytes) failed", n)
        if (direct) destination.position(destination.position() + n)
        else destination.put(target, 0, n)
    }

    override fun write(offset: Long, source: ByteBuffer) {
        if (isDirectory) throw IOException("Cannot write to a directory")
        fs.checkWritable()
        val size = source.remaining()
        if (size == 0) return
        val direct = source.hasArray()
        val data = if (direct) source.array() else ByteArray(size).also { source.duplicate().get(it) }
        val dataOffset = if (direct) source.arrayOffset() + source.position() else 0
        val n = fs.withNative { h ->
            NtfsNative.writeFile(h, node.mref, offset, size, data, dataOffset)
        }
        if (n < 0) fs.error("NTFS write to $absolutePath at $offset ($size bytes) failed", n)
        // The native loop writes everything or fails, so a short count is a bug.
        if (n != size) throw IOException("NTFS short write to $absolutePath: $n of $size bytes")
        source.position(source.position() + n)
        val end = offset + n
        if (end > node.size) {
            node.size = end
            fs.invalidateListings()
        }
        node.lastModified = System.currentTimeMillis()
    }

    override fun flush() {
        if (fs.isUnmounted || fs.isReadOnly) return
        val rc = fs.withNative { h -> NtfsNative.sync(h) }
        if (rc != 0) fs.error("NTFS sync failed", rc)
    }

    /** Nothing native is held open; every write has reached the device already. */
    override fun close() {}

    override fun createDirectory(name: String): UsbFile = create(name, directory = true)

    override fun createFile(name: String): UsbFile = create(name, directory = false)

    private fun create(childName: String, directory: Boolean): UsbFile {
        if (!isDirectory) throw IOException("Cannot create inside a file")
        fs.checkWritable()
        val created = fs.withNative { h ->
            NtfsNative.create(h, node.mref, childName, directory).also { fs.invalidateListings() }
        } ?: fs.error("NTFS create of $childName in $absolutePath failed")
        return NtfsFile(fs, this, created)
    }

    override fun moveTo(destination: UsbFile) {
        val target = destination as? NtfsFile
            ?: throw IOException("Cannot move between filesystems")
        if (!target.isDirectory) throw IOException("Move target is not a directory")
        rename(target, name)
    }

    private fun rename(newParent: NtfsFile, newName: String) {
        val oldParent = parent ?: throw IOException("Cannot move the root")
        fs.checkWritable()
        if (isDirectory) {
            // Into itself or a descendant would detach the subtree from the root.
            var p: NtfsFile? = newParent
            while (p != null) {
                if (p.node.mref == node.mref) throw IOException("Cannot move a directory into itself")
                p = p.parent
            }
        }
        val sameDir = newParent.node.mref == oldParent.node.mref
        if (sameDir && newName == node.name) return
        val caseOnly = sameDir && newName.equals(node.name, ignoreCase = true)
        val rc = fs.withNative { h ->
            NtfsNative.rename(h, node.mref, oldParent.node.mref, node.name,
                newParent.node.mref, newName, caseOnly).also { fs.invalidateListings() }
        }
        if (rc != 0) fs.error("NTFS rename of $absolutePath to $newName failed", rc)
        node.name = newName
    }

    /**
     * Deletes this file, or this directory and everything under it — the same
     * contract as libaums' FAT32 directories, which is what the DocumentsProvider
     * relies on when a user deletes a folder.
     */
    override fun delete() {
        val p = parent ?: throw IOException("Cannot delete the root")
        fs.checkWritable()
        if (isDirectory) listFiles().forEach { it.delete() }
        val rc = fs.withNative { h ->
            NtfsNative.delete(h, p.node.mref, node.mref, node.name).also { fs.invalidateListings() }
        }
        if (rc != 0) fs.error("NTFS delete of $absolutePath failed", rc)
    }
}

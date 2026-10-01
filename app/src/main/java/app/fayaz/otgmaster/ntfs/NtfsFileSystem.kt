package app.fayaz.otgmaster.ntfs

import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * NTFS through libntfs-3g, readable and writable.
 *
 * The native side holds no per-file state: an [NtfsFile] is an MFT reference plus
 * cached attributes, and each call opens the inode, does its work and closes it.
 * So there is nothing for a finalizer to release and nothing that can dangle
 * across an unmount except the volume handle, which [withNative] guards.
 *
 * Writes go through libntfs-3g's own allocation, index and $LogFile-free update
 * paths, in the order its FUSE driver uses them. A volume Windows did not close
 * cleanly — hibernated, Fast Startup, or pulled without "safely remove" — comes
 * up read-only with [readOnlyReason] saying so, rather than being "fixed" by
 * emptying its journal.
 */
class NtfsFileSystem private constructor(
    private val device: RawBlockDevice,
    private val handle: Long,
    /** Why the volume refuses writes, or null if it accepts them. */
    val readOnlyReason: String?,
) : FileSystem {

    val isReadOnly: Boolean get() = readOnlyReason != null

    /** Serialises every libntfs-3g call; the library has no locking of its own. */
    private val lock = ReentrantLock()

    @Volatile
    var isUnmounted = false
        private set

    /**
     * Runs [body] against the volume handle with the lock held, refusing once
     * [unmount] has freed it — the check is inside the lock, so it cannot change
     * between the test and the call.
     */
    internal fun <T> withNative(body: (Long) -> T): T = lock.withLock {
        if (isUnmounted) throw IOException("NTFS filesystem is unmounted")
        body(handle)
    }

    internal fun checkWritable() {
        readOnlyReason?.let { throw IOException("NTFS volume is read-only: $it") }
    }

    /** Throws an IOException naming [what] and the errno of the last failed call. */
    internal fun error(what: String, rc: Int = -NtfsNative.lastErrno()): Nothing {
        val errno = -rc
        throw IOException("$what: ${errnoName(errno)}")
    }

    /**
     * Directory listings by MFT reference.
     *
     * The DocumentsProvider resolves every document id by listing each directory
     * on its path, and an NTFS listing opens every child's record for its size and
     * times. Without this a deep path in a large directory paid for that on every
     * call. Any change to the namespace or to a file's size clears it: a stale
     * listing is the one wrong answer this must never give.
     */
    private val dirCache = object : LinkedHashMap<Long, Array<NtfsNode>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Array<NtfsNode>>) = size > 32
    }

    internal fun listDir(mref: Long): Array<NtfsNode> = withNative { h ->
        dirCache[mref] ?: (NtfsNative.readDir(h, mref) ?: error("NTFS directory listing failed"))
            .also { dirCache[mref] = it }
    }

    /** Cluster size and $Bitmap, read under the lock. For diagnostics (the debug volume dump). */
    fun allocationBitmap(): Pair<Int, ByteArray> = withNative { h ->
        NtfsNative.clusterSize(h) to (NtfsNative.clusterBitmap(h) ?: error("cannot read \$Bitmap"))
    }

    internal fun invalidateListings() = lock.withLock { dirCache.clear() }

    private val rootNode: NtfsNode by lazy {
        withNative { h -> NtfsNative.root(h) } ?: error("NTFS root directory unreadable")
    }

    override val rootDirectory: UsbFile get() = NtfsFile(this, null, rootNode)

    override val volumeLabel: String
        get() = withNative { h -> NtfsNative.volumeLabel(h) }

    override val capacity: Long
        get() = lock.withLock { if (isUnmounted) 0L else NtfsNative.capacity(handle) }

    override val freeSpace: Long
        get() = lock.withLock { if (isUnmounted) 0L else NtfsNative.freeSpace(handle) }

    override val occupiedSpace: Long get() = capacity - freeSpace

    override val chunkSize: Int
        get() = lock.withLock { if (isUnmounted) device.blockSize else NtfsNative.clusterSize(handle) }

    override val type: Int get() = 0x07  // MBR partition type for NTFS/exFAT

    fun unmount() {
        lock.withLock {
            if (isUnmounted) return
            isUnmounted = true
            dirCache.clear()
            val rc = NtfsNative.unmount(handle)
            if (rc != 0) android.util.Log.e("NTFS", "unmount: ${errnoName(-rc)}")
        }
    }

    companion object {
        const val MOUNTED_READ_ONLY = "it was mounted read-only"

        /**
         * Mounts the NTFS volume on [device].
         *
         * @param readOnly the user chose to mount this partition read-only.
         * @throws IOException if libntfs-3g cannot mount it, with the reason.
         */
        fun mount(device: RawBlockDevice, readOnly: Boolean): NtfsFileSystem {
            val handle = NtfsNative.mount(device, device.blockCount * device.blockSize, readOnly)
            if (handle == 0L) {
                throw IOException("NTFS mount failed: ${errnoName(NtfsNative.lastErrno())}")
            }
            val reason = when (NtfsNative.readOnlyReason(handle)) {
                NtfsNative.READ_ONLY_NONE -> null
                NtfsNative.READ_ONLY_HIBERNATED ->
                    "Windows left it hibernated or with Fast Startup metadata cached. " +
                        "Shut Windows down fully (not Fast Startup) before writing to it."
                NtfsNative.READ_ONLY_UNCLEAN ->
                    "it was not safely removed from Windows, and its journal has changes " +
                        "not yet applied. Reconnect it to Windows and eject it properly " +
                        "before writing to it."
                else -> MOUNTED_READ_ONLY
            }
            return NtfsFileSystem(device, handle, reason)
        }

        /** True if [bootSector] carries the NTFS OEM id "NTFS    " at offset 3. */
        fun hasNtfsSignature(bootSector: ByteArray): Boolean =
            bootSector.size >= 11 &&
                String(bootSector, 3, 8, Charsets.US_ASCII) == "NTFS    "

        internal fun errnoName(errno: Int): String = when (errno) {
            1 -> "operation not permitted (EPERM)"
            2 -> "no such file or directory (ENOENT)"
            5 -> "I/O error (EIO)"
            12 -> "out of memory (ENOMEM)"
            13 -> "permission denied (EACCES)"
            16 -> "busy (EBUSY)"
            17 -> "already exists (EEXIST)"
            20 -> "not a directory (ENOTDIR)"
            21 -> "is a directory (EISDIR)"
            22 -> "invalid argument or name (EINVAL)"
            27 -> "file too large (EFBIG)"
            28 -> "no space left on volume (ENOSPC)"
            30 -> "read-only volume (EROFS)"
            36 -> "name too long (ENAMETOOLONG)"
            39 -> "directory not empty (ENOTEMPTY)"
            61 -> "no data (ENODATA)"
            95 -> "not supported, e.g. a compressed or encrypted file (EOPNOTSUPP)"
            else -> "errno $errno"
        }
    }
}

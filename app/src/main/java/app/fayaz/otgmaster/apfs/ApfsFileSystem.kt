package app.fayaz.otgmaster.apfs

import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import java.io.IOException
import java.text.Normalizer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * APFS through libfsapfs, read-only, plain or natively encrypted.
 *
 * Mounts the first volume of the container, which is the only one on a drive Disk
 * Utility formatted. An encrypted volume is unlocked with its password inside
 * libfsapfs, which unwraps the volume key from the keybags; there is no decrypting
 * block device underneath as there is for LUKS, VeraCrypt or BitLocker.
 *
 * Like [app.fayaz.otgmaster.ntfs.NtfsFileSystem], the native side holds no per-file
 * state: an [ApfsFile] is an inode number plus cached attributes.
 */
class ApfsFileSystem private constructor(
    private val device: RawBlockDevice,
    private val handle: Long,
) : FileSystem {

    /** Always set: libfsapfs has no write support. */
    val readOnlyReason: String = READ_ONLY_REASON

    /** Serialises every libfsapfs call; it is built without libyal's thread support. */
    private val lock = ReentrantLock()

    @Volatile
    var isUnmounted = false
        private set

    /** Whether names that differ only in case are different files on this volume. */
    val isCaseSensitive: Boolean = ApfsNative.isCaseSensitive(handle)

    internal fun <T> withNative(body: (Long) -> T): T = lock.withLock {
        if (isUnmounted) throw IOException("APFS filesystem is unmounted")
        body(handle)
    }

    internal fun error(what: String, rc: Int = -ApfsNative.lastErrno()): Nothing =
        throw IOException("$what: ${errnoName(-rc)}")

    internal fun readOnly(): Nothing = throw IOException("APFS volume is read-only: $readOnlyReason")

    /**
     * Directory listings by inode number. The volume is read-only, so a listing can
     * never go stale while it is mounted; the bound only caps memory.
     */
    private val dirCache = object : LinkedHashMap<Long, Array<ApfsNode>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Array<ApfsNode>>) = size > 64
    }

    internal fun listDir(id: Long): Array<ApfsNode> = withNative { h ->
        dirCache[id] ?: (ApfsNative.readDir(h, id) ?: error("APFS directory listing failed"))
            .also { dirCache[id] = it }
    }

    /**
     * The entry called [name] among [entries], matched the way APFS does: always
     * insensitive to Unicode normalization (macOS may store either form), and
     * insensitive to case unless the volume is case-sensitive.
     */
    internal fun match(entries: Array<ApfsNode>, name: String): ApfsNode? {
        entries.firstOrNull { it.name == name }?.let { return it }
        val want = Normalizer.normalize(name, Normalizer.Form.NFD)
        return entries.firstOrNull {
            val have = Normalizer.normalize(it.name, Normalizer.Form.NFD)
            if (isCaseSensitive) have == want else have.equals(want, ignoreCase = true)
        }
    }

    private val rootNode: ApfsNode by lazy {
        withNative { h -> ApfsNative.root(h) } ?: error("APFS root directory unreadable")
    }

    override val rootDirectory: UsbFile get() = ApfsFile(this, null, rootNode)

    override val volumeLabel: String
        get() = withNative { h -> ApfsNative.volumeLabel(h) }

    override val capacity: Long
        get() = lock.withLock { if (isUnmounted) 0L else ApfsNative.capacity(handle) }

    override val occupiedSpace: Long
        get() = lock.withLock { if (isUnmounted) 0L else ApfsNative.usedSpace(handle) }

    /**
     * Capacity less this volume's allocation. Other volumes in the same container
     * share the space and are not counted; Disk Utility puts one volume on a USB
     * drive.
     */
    override val freeSpace: Long get() = (capacity - occupiedSpace).coerceAtLeast(0L)

    override val chunkSize: Int get() = 4096

    override val type: Int get() = 0xAF   // the MBR type Apple uses for APFS/HFS

    fun unmount() {
        lock.withLock {
            if (isUnmounted) return
            isUnmounted = true
            dirCache.clear()
            ApfsNative.unmount(handle)
        }
    }

    /** The password did not unlock the volume (or none was given for an encrypted one). */
    class WrongPasswordException(message: String) : Exception(message)

    companion object {
        const val READ_ONLY_REASON = "OTG Master reads APFS but cannot write to it"

        /**
         * Mounts the first volume of the APFS container on [device].
         *
         * @param password for an encrypted volume; ignored for a plain one.
         * @throws WrongPasswordException if an encrypted volume does not unlock.
         * @throws IOException if libfsapfs cannot open the container.
         */
        fun mount(device: RawBlockDevice, password: String?): ApfsFileSystem {
            val pw = password?.toByteArray(Charsets.UTF_8)
            val handle = try {
                ApfsNative.mount(device, device.blockCount * device.blockSize, pw)
            } finally {
                pw?.fill(0)
            }
            if (handle == 0L) {
                val errno = ApfsNative.lastErrno()
                if (errno == EACCES) {
                    throw WrongPasswordException(
                        if (password == null) "APFS volume is encrypted: enter its password"
                        else "Wrong password for the encrypted APFS volume")
                }
                throw IOException("APFS mount failed: ${errnoName(errno)}")
            }
            return ApfsFileSystem(device, handle)
        }

        /**
         * True if [device] holds an APFS container whose first volume is encrypted.
         * Null if it holds no APFS container libfsapfs can open.
         */
        fun isEncrypted(device: RawBlockDevice): Boolean? =
            when (ApfsNative.probe(device, device.blockCount * device.blockSize)) {
                ApfsNative.PROBE_PLAIN -> false
                ApfsNative.PROBE_ENCRYPTED -> true
                else -> null
            }

        /** True if [block0] is an APFS container superblock: "NXSB" at byte 32. */
        fun hasSignature(block0: ByteArray): Boolean =
            block0.size >= 36 && block0[32] == 'N'.code.toByte() && block0[33] == 'X'.code.toByte() &&
                block0[34] == 'S'.code.toByte() && block0[35] == 'B'.code.toByte()

        private const val EACCES = 13

        internal fun errnoName(errno: Int): String = when (errno) {
            2 -> "no such file or directory (ENOENT)"
            5 -> "I/O error (EIO)"
            12 -> "out of memory (ENOMEM)"
            13 -> "wrong password (EACCES)"
            20 -> "not a directory (ENOTDIR)"
            22 -> "not an APFS container libfsapfs can read (EINVAL)"
            else -> "errno $errno"
        }
    }
}

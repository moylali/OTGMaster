package app.fayaz.otgmaster.apfs

import app.fayaz.otgmaster.block.RawBlockDevice

/**
 * JNI surface of cpp/apfs/ApfsNative.cpp (libfsapfs, read-only).
 *
 * Calls returning Int return 0 or a byte count on success and a negative errno on
 * failure. Calls returning an object return null on failure, with the errno in
 * [lastErrno] on the same thread. None of them is thread-safe; ApfsFileSystem
 * serialises them.
 */
object ApfsNative {

    init {
        // Host unit tests load a desktop build of the same sources instead.
        val hostLib = System.getProperty("otg.native.hostlib")
        if (hostLib != null) System.load(hostLib) else System.loadLibrary("veracrypt-native")
    }

    const val PROBE_PLAIN = 0
    const val PROBE_ENCRYPTED = 1

    /** [PROBE_PLAIN], [PROBE_ENCRYPTED], or a negative errno if no APFS container opens. */
    @JvmStatic external fun probe(device: RawBlockDevice, sizeBytes: Long): Int

    /**
     * Volume handle for the container's first volume, or 0 with [lastErrno] set:
     * EACCES for a wrong password, or for an encrypted volume given none.
     */
    @JvmStatic external fun mount(device: RawBlockDevice, sizeBytes: Long, password: ByteArray?): Long
    @JvmStatic external fun unmount(handle: Long): Int
    @JvmStatic external fun lastErrno(): Int
    @JvmStatic external fun volumeLabel(handle: Long): String
    /** The container's size: every volume in it shares this space. */
    @JvmStatic external fun capacity(handle: Long): Long
    /** Bytes this volume has allocated in the container. */
    @JvmStatic external fun usedSpace(handle: Long): Long
    @JvmStatic external fun isCaseSensitive(handle: Long): Boolean

    @JvmStatic external fun root(handle: Long): ApfsNode?
    @JvmStatic external fun readDir(handle: Long, id: Long): Array<ApfsNode>?
    @JvmStatic external fun readFile(
        handle: Long, id: Long, offset: Long, size: Int, buffer: ByteArray, bufferOffset: Int,
    ): Int

    // Called from native code to reach the block device.

    @JvmStatic
    fun pread(device: RawBlockDevice, offset: Long, size: Int, dest: ByteArray): Int = try {
        val bs = device.blockSize
        val first = offset / bs
        val last = (offset + size - 1) / bs
        val blocks = device.readBlocks(first, (last - first + 1).toInt())
        System.arraycopy(blocks, (offset % bs).toInt(), dest, 0, size)
        size
    } catch (e: Exception) {
        android.util.Log.e("ApfsNative", "pread $size @ $offset failed", e)
        -1
    }
}

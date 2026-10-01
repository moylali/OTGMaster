package app.fayaz.otgmaster.ntfs

import app.fayaz.otgmaster.block.RawBlockDevice

/**
 * JNI surface of cpp/ntfs/NtfsNative.cpp (libntfs-3g).
 *
 * Calls returning Int return 0 or a byte count on success and a negative errno on
 * failure. Calls returning an object return null on failure, with the errno in
 * [lastErrno] on the same thread. None of them is thread-safe; NtfsFileSystem
 * serialises them.
 */
object NtfsNative {

    init {
        // Host unit tests load a desktop build of the same sources instead.
        val hostLib = System.getProperty("otg.ntfs.hostlib")
        if (hostLib != null) System.load(hostLib) else System.loadLibrary("veracrypt-native")
    }

    /** Volume handle, or 0 with [lastErrno] set. */
    @JvmStatic external fun mount(device: RawBlockDevice, sizeBytes: Long, readOnly: Boolean): Long
    @JvmStatic external fun unmount(handle: Long): Int
    @JvmStatic external fun lastErrno(): Int
    /** One of the READ_ONLY_* constants. */
    @JvmStatic external fun readOnlyReason(handle: Long): Int
    @JvmStatic external fun volumeLabel(handle: Long): String
    @JvmStatic external fun capacity(handle: Long): Long
    @JvmStatic external fun freeSpace(handle: Long): Long
    @JvmStatic external fun clusterSize(handle: Long): Int

    @JvmStatic external fun root(handle: Long): NtfsNode?
    @JvmStatic external fun stat(handle: Long, mref: Long, name: String): NtfsNode?
    @JvmStatic external fun readDir(handle: Long, mref: Long): Array<NtfsNode>?
    @JvmStatic external fun lookup(handle: Long, dirMref: Long, name: String): NtfsNode?

    @JvmStatic external fun readFile(
        handle: Long, mref: Long, offset: Long, size: Int, buffer: ByteArray, bufferOffset: Int,
    ): Int
    @JvmStatic external fun writeFile(
        handle: Long, mref: Long, offset: Long, size: Int, buffer: ByteArray, bufferOffset: Int,
    ): Int
    @JvmStatic external fun setLength(handle: Long, mref: Long, length: Long): Int

    @JvmStatic external fun create(handle: Long, dirMref: Long, name: String, directory: Boolean): NtfsNode?
    @JvmStatic external fun delete(handle: Long, dirMref: Long, mref: Long, name: String): Int
    @JvmStatic external fun rename(
        handle: Long, mref: Long, oldDirMref: Long, oldName: String,
        newDirMref: Long, newName: String, caseOnly: Boolean,
    ): Int
    @JvmStatic external fun sync(handle: Long): Int

    const val READ_ONLY_NONE = 0
    const val READ_ONLY_REQUESTED = 1
    const val READ_ONLY_HIBERNATED = 2
    const val READ_ONLY_UNCLEAN = 3

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
        android.util.Log.e("NtfsNative", "pread $size @ $offset failed", e)
        -1
    }

    @JvmStatic
    fun pwrite(device: RawBlockDevice, offset: Long, size: Int, src: ByteArray): Int = try {
        val bs = device.blockSize
        val first = offset / bs
        val last = (offset + size - 1) / bs
        val inBlock = (offset % bs).toInt()
        if (inBlock == 0 && size % bs == 0) {
            device.writeBlocks(first, src)
        } else {
            // Read-modify-write the partial sectors at either end.
            val blocks = device.readBlocks(first, (last - first + 1).toInt())
            System.arraycopy(src, 0, blocks, inBlock, size)
            device.writeBlocks(first, blocks)
        }
        size
    } catch (e: Exception) {
        android.util.Log.e("NtfsNative", "pwrite $size @ $offset failed", e)
        -1
    }
}

package app.fayaz.otgmaster.fs.apfs

import androidx.annotation.Keep
import app.fayaz.otgmaster.fs.BlockDevice

/**
 * JNI bindings for the native APFS parser (e.g., apfs-fuse or libfsapfs).
 * This bridges the pure Kotlin [BlockDevice] interface to the native C/C++ parser.
 */
@Keep
object ApfsNative {
    init {
        System.loadLibrary("apfs")
    }

    /**
     * Initializes a native APFS context for the given block device.
     * @param device The block device to read from.
     * @param password Optional password if the volume is encrypted (null if unencrypted).
     * @return A native pointer to the APFS context, or 0 on failure.
     */
    @JvmStatic
    external fun mount(device: BlockDevice, password: String?): Long

    /**
     * Reads a directory's contents from the APFS volume.
     * @param contextPtr The native pointer returned by [mount].
     * @param path The absolute path to the directory (e.g., "/").
     * @return An array of file names, or null on error.
     */
    @JvmStatic
    external fun listDirectory(contextPtr: Long, path: String): Array<String>?

    /**
     * Frees the native APFS context.
     */
    @JvmStatic
    external fun unmount(contextPtr: Long)
}

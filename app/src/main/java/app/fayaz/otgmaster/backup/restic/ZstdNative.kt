package app.fayaz.otgmaster.backup.restic

import java.io.IOException

/**
 * zstd's own decoder (app/src/main/cpp/zstd, docs/VENDOR_FIXES.md). Restic compresses by
 * default since format 2, so every repository a desktop made needs it. A pure-Java decoder
 * was used first and passed on the host, but read fields of sun.misc.Unsafe that Android
 * does not have: the first compressed file crashed the app with NoSuchFieldError.
 */
object ZstdNative {
    init {
        // Host unit tests load a desktop build of the same sources instead.
        val hostLib = System.getProperty("otg.native.hostlib")
        if (hostLib != null) System.load(hostLib) else System.loadLibrary("veracrypt-native")
    }

    /**
     * Decompresses [length] bytes of [src] from [offset]. [expected] is the decompressed size
     * when the caller knows it, and anything else in that case is an error; -1 takes it from
     * the frame, or decodes in pieces if the frame does not state it.
     */
    @JvmStatic
    @Throws(IOException::class)
    external fun decompress(src: ByteArray, offset: Int, length: Int, expected: Int): ByteArray
}

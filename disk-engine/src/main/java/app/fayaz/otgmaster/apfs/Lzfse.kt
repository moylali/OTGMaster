package app.fayaz.otgmaster.apfs

import java.io.IOException

/**
 * LZFSE decoding through libfmos (cpp/apfs/ApfsNative.cpp), the decoder vendored with libfsapfs.
 * Container Master hands it to the disk-image reader for images compressed as ULFO, macOS's
 * default for compressed DMGs since 10.11.
 */
object Lzfse {
    init {
        // Host unit tests load a desktop build of the same sources instead.
        val hostLib = System.getProperty("otg.native.hostlib")
        if (hostLib != null) System.load(hostLib) else System.loadLibrary("veracrypt-native")
    }

    /** Decodes one LZFSE stream to exactly [expected] bytes, or throws. */
    @JvmStatic
    @Throws(IOException::class)
    external fun decompress(src: ByteArray, expected: Int): ByteArray
}

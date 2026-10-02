package app.fayaz.otgmaster.veracrypt

object VeraCryptNative {
    init {
        // Host unit tests load a desktop build of the same sources instead.
        val hostLib = System.getProperty("otg.native.hostlib")
        if (hostLib != null) System.load(hostLib) else System.loadLibrary("veracrypt-native")
    }

    @JvmStatic
    external fun decryptHeader(
        cipher: Int,
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        encryptedHeader: ByteArray
    ): ByteArray?

    /**
     * Crypts [length] bytes of [buffer] starting at [offset] in place, as a run of
     * consecutive 512-byte sectors beginning at [startSector].
     *
     * One JNI crossing and one key schedule for the whole run, versus one of each
     * per sector for decryptSector/encryptSector. [direction] is 0 to decrypt, 1 to
     * encrypt. Returns 0 on success; the buffer is left untouched on failure.
     */
    external fun cryptSectorsInPlace(
        cipher: Int,
        direction: Int,
        masterKey: ByteArray,
        startSector: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    external fun decryptSector(
        cipher: Int,
        masterKey: ByteArray,
        sectorNum: Long,
        encryptedSector: ByteArray
    ): ByteArray?

    external fun encryptSector(
        cipher: Int,
        masterKey: ByteArray,
        sectorNum: Long,
        unencryptedSector: ByteArray
    ): ByteArray?
}

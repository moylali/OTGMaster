package app.fayaz.otgmaster.bitlocker

/** JNI surface of cpp/bitlocker (bitlocker_crypto.c). */
object BitLockerNative {

    init {
        // Host unit tests load a desktop build of the same sources instead.
        val hostLib = System.getProperty("otg.native.hostlib")
        if (hostLib != null) System.load(hostLib) else System.loadLibrary("veracrypt-native")
    }

    const val MODE_CBC = 1
    const val MODE_CBC_ELEPHANT = 2
    const val MODE_XTS = 3

    /** The 0x100000-round SHA-256 stretch of [initial] (32 bytes) with [salt] (16). */
    @JvmStatic external fun stretchKey(initial: ByteArray, salt: ByteArray): ByteArray?

    /** AES-CCM, 12-byte nonce, 16-byte tag. Null when the tag does not verify. */
    @JvmStatic external fun ccmDecrypt(key: ByteArray, nonce: ByteArray, tag: ByteArray, data: ByteArray): ByteArray?

    /** Ciphertext followed by its 16-byte tag. For building test volumes. */
    @JvmStatic external fun ccmEncrypt(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray?

    /** Key schedules for one volume; 0 on a bad mode or key length. Free with [freeContext]. */
    @JvmStatic external fun newContext(mode: Int, key: ByteArray, sectorSize: Int): Long
    @JvmStatic external fun freeContext(handle: Long)

    /**
     * Crypts [length] bytes of [buffer] at [offset] in place: whole sectors, the
     * first being [firstSector] in units of the volume's sector size. 0 on success.
     */
    @JvmStatic external fun cryptSectors(
        handle: Long, encrypt: Boolean, firstSector: Long, buffer: ByteArray, offset: Int, length: Int,
    ): Int
}

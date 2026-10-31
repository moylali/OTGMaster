package app.fayaz.otgmaster.veracrypt

object VeraCryptNative {
    init {
        // Host unit tests load a desktop build of the same sources instead.
        val hostLib = System.getProperty("otg.native.hostlib")
        if (hostLib != null) System.load(hostLib) else System.loadLibrary("veracrypt-native")
    }

    /**
     * Derives the header key with [kdf] and decrypts the header's 448 encrypted bytes
     * under the cascade [ciphers] (nativeIds in VeraCrypt table order, see
     * [VeraCryptCipher.components]). Returns those bytes whether or not the password
     * was right — checking "VERA" is the caller's job — or null if setup failed.
     *
     * [iterations] is PBKDF2's count or Argon2id's time cost; [memoryKiB] is Argon2id's
     * memory cost and is ignored for PBKDF2. Throws IllegalStateException with
     * Argon2's message when Argon2id itself fails, most often by not getting its memory.
     */
    @JvmStatic
    external fun decryptHeader(
        ciphers: IntArray,
        kdf: Int,
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKiB: Int,
        encryptedHeader: ByteArray
    ): ByteArray?

    /**
     * The inverse of [decryptHeader], for creating a volume: derives the header key the
     * same way and encrypts [plainHeader]'s first 448 bytes (the header from offset 64,
     * "VERA" first). Same exceptions as [decryptHeader].
     */
    @JvmStatic
    external fun encryptHeader(
        ciphers: IntArray,
        kdf: Int,
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKiB: Int,
        plainHeader: ByteArray
    ): ByteArray?

    /**
     * Crypts [length] bytes of [buffer] starting at [offset] in place, as a run of
     * consecutive 512-byte sectors beginning at [startSector], under the cascade
     * [ciphers] (one id for a single cipher, as LUKS uses it).
     *
     * One JNI crossing and one key schedule for the whole run. [direction] is 0 to
     * decrypt, 1 to encrypt. Returns 0 on success; the buffer is left untouched on
     * failure.
     */
    /**
     * Argon2id (version 0x13) of [password] and [salt]: [outLen] bytes. For LUKS2 keyslots.
     * Throws IllegalStateException with Argon2's message when it fails (usually memory).
     */
    @JvmStatic
    external fun argon2id(password: ByteArray, salt: ByteArray, timeCost: Int, memoryKiB: Int,
                          lanes: Int, outLen: Int): ByteArray?

        external fun cryptSectorsInPlace(
        ciphers: IntArray,
        direction: Int,
        masterKey: ByteArray,
        startSector: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int
}

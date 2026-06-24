package app.fayaz.otgmaster.veracrypt

object VeraCryptNative {
    init {
        System.loadLibrary("veracrypt-native")
    }

    @JvmStatic
    external fun decryptHeader(
        cipher: Int,
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        encryptedHeader: ByteArray
    ): ByteArray?

    external fun decryptSector(
        cipher: Int,
        masterKey: ByteArray,
        sectorNum: Long,
        encryptedSector: ByteArray
    ): ByteArray?
}

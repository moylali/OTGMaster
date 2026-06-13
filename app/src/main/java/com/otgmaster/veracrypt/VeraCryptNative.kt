package com.otgmaster.veracrypt

object VeraCryptNative {
    init {
        System.loadLibrary("veracrypt-native")
    }

    @JvmStatic
    external fun decryptHeader(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        encryptedHeader: ByteArray
    ): ByteArray?

    external fun decryptSector(
        masterKey: ByteArray,
        sectorNum: Long,
        encryptedSector: ByteArray
    ): ByteArray?
}

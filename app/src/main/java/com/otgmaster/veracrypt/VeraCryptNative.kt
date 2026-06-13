package com.otgmaster.veracrypt

object VeraCryptNative {
    init {
        System.loadLibrary("veracrypt-native")
    }

    external fun decryptHeader(
        password: ByteArray,
        salt: ByteArray,
        encryptedHeader: ByteArray
    ): ByteArray?

    external fun decryptSector(
        masterKey: ByteArray,
        sectorNum: Long,
        encryptedSector: ByteArray
    ): ByteArray?
}

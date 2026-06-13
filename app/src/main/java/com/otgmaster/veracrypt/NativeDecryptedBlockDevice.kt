package com.otgmaster.veracrypt

import com.otgmaster.block.RawBlockDevice

class NativeDecryptedBlockDevice(
    private val encryptedDevice: RawBlockDevice,
    private val masterKey: ByteArray,
    private val volumeDataOffset: Long // the number of blocks to skip to reach data
) : RawBlockDevice {

    override val blockSize: Int
        get() = encryptedDevice.blockSize
    override val blockCount: Long
        get() = encryptedDevice.blockCount - volumeDataOffset

    override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
        val physicalStartBlock = startBlock + volumeDataOffset
        val encryptedData = encryptedDevice.readBlocks(physicalStartBlock, blockCount)
        
        val decryptedData = ByteArray(encryptedData.size)
        val sectorCount = encryptedData.size / 512
        
        for (i in 0 until sectorCount) {
            val sectorEncrypted = encryptedData.copyOfRange(i * 512, (i + 1) * 512)
            val sectorDecrypted = VeraCryptNative.decryptSector(masterKey, physicalStartBlock + i, sectorEncrypted)
            
            if (sectorDecrypted != null) {
                System.arraycopy(sectorDecrypted, 0, decryptedData, i * 512, 512)
            } else {
                throw IllegalStateException("Decryption failed at physical sector ${physicalStartBlock + i}")
            }
        }
        
        return decryptedData
    }

    override fun close() {
        masterKey.fill(0)
    }
}

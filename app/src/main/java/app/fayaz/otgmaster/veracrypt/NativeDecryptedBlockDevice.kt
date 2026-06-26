package app.fayaz.otgmaster.veracrypt

import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.nio.ByteBuffer

class NativeDecryptedBlockDevice(
    private val encryptedDevice: RawBlockDevice,
    private val masterKey: ByteArray,
    private val volumeDataOffset: Long, // absolute device blocks to skip to reach data (for I/O)
    private val decryptedBlockCount: Long,
    private val cipherNativeId: Int = SingleCipher.AES.nativeId,
    // Sector number (relative to the START OF THE VOLUME, not the physical disk) where the
    // data area begins — this is what VeraCrypt's XTS tweak is actually computed from. For a
    // standard volume this is always headerSize/blockSize (e.g. 256 for 512-byte sectors),
    // regardless of where the volume sits inside a partition. Using the *physical* sector
    // number here instead (as this used to) only happens to work when the volume starts at
    // physical sector 0 (e.g. "whole device") — for any volume inside a partition with a
    // nonzero start, it corrupts every data sector while leaving the header (which uses its
    // own fixed tweak) unaffected.
    private val tweakDataOffset: Long = volumeDataOffset
) : RawBlockDevice, BlockDeviceDriver {

    override val blockSize: Int
        get() = encryptedDevice.blockSize
    override val blockCount: Long
        get() = decryptedBlockCount
    override val blocks: Long
        get() = blockCount

    override fun init() {
        // Nothing to init
    }

    override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
        val physicalStartBlock = startBlock + volumeDataOffset
        val encryptedData = encryptedDevice.readBlocks(physicalStartBlock, blockCount)

        val decryptedData = ByteArray(encryptedData.size)
        val sectorCount = encryptedData.size / 512
        
        val sectorsPerBlock = blockSize / 512L
        val tweakDataOffsetSectors = tweakDataOffset * sectorsPerBlock
        val startBlockSectors = startBlock * sectorsPerBlock

        for (i in 0 until sectorCount) {
            val sectorEncrypted = encryptedData.copyOfRange(i * 512, (i + 1) * 512)
            val tweak = tweakDataOffsetSectors + startBlockSectors + i
            val sectorDecrypted = VeraCryptNative.decryptSector(cipherNativeId, masterKey, tweak, sectorEncrypted)

            if (sectorDecrypted != null) {
                System.arraycopy(sectorDecrypted, 0, decryptedData, i * 512, 512)
            } else {
                throw IllegalStateException("Decryption failed at physical sector ${physicalStartBlock + i}")
            }
        }
        
        return decryptedData
    }

    override fun read(deviceOffset: Long, buffer: ByteBuffer) {
        val bytesToRead = buffer.remaining()
        require(bytesToRead % blockSize == 0) { "buffer.remaining() must be multiple of blockSize" }
        val blocksToRead = bytesToRead / blockSize
        // deviceOffset is a block number per BlockDeviceDriver contract (ByteBlockDevice converts bytes→blocks before calling here)
        val data = readBlocks(deviceOffset, blocksToRead)
        buffer.put(data)
    }

    override fun writeBlocks(startBlock: Long, data: ByteArray) {
        val physicalStartBlock = startBlock + volumeDataOffset
        val sectorCount = data.size / 512
        val encryptedData = ByteArray(data.size)
        
        val sectorsPerBlock = blockSize / 512L
        val tweakDataOffsetSectors = tweakDataOffset * sectorsPerBlock
        val startBlockSectors = startBlock * sectorsPerBlock

        for (i in 0 until sectorCount) {
            val sectorDecrypted = data.copyOfRange(i * 512, (i + 1) * 512)
            val tweak = tweakDataOffsetSectors + startBlockSectors + i
            val sectorEncrypted = VeraCryptNative.encryptSector(cipherNativeId, masterKey, tweak, sectorDecrypted)

            if (sectorEncrypted != null) {
                System.arraycopy(sectorEncrypted, 0, encryptedData, i * 512, 512)
            } else {
                throw IllegalStateException("Encryption failed at physical sector ${physicalStartBlock + i}")
            }
        }
        
        encryptedDevice.writeBlocks(physicalStartBlock, encryptedData)
    }

    override fun write(deviceOffset: Long, buffer: ByteBuffer) {
        val bytesToWrite = buffer.remaining()
        require(bytesToWrite % blockSize == 0) { "buffer.remaining() must be multiple of blockSize" }
        val data = ByteArray(bytesToWrite)
        buffer.get(data)
        writeBlocks(deviceOffset, data)
    }

    override fun close() {
        masterKey.fill(0)
        // Without this, the underlying USB interface claim/connection is never released,
        // so the device can't be reopened after unmounting until the app restarts.
        encryptedDevice.close()
    }
}

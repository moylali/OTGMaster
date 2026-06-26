package app.fayaz.otgmaster.veracrypt

import app.fayaz.otgmaster.block.RawBlockDevice
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class NativeDecryptedBlockDeviceTest {

    @Test
    fun testPartitionedVolumeBounds() {
        val fakeDevice = object : RawBlockDevice {
            override val blockSize: Int = 512
            override val blockCount: Long = 10000 // Total physical blocks
            
            override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
                return ByteArray(blockCount * blockSize)
            }
            
            override fun writeBlocks(startBlock: Long, data: ByteArray) {}
            override fun close() {}
        }
        
        val masterKey = ByteArray(32)
        val candidate = VolumeCandidate("partition", 2048, 4096)
        
        val dataOffsetSectors = 131072L / fakeDevice.blockSize
        val decryptedDevice = NativeDecryptedBlockDevice(
            encryptedDevice = fakeDevice,
            masterKey = masterKey,
            volumeDataOffset = candidate.startBlock + dataOffsetSectors,
            decryptedBlockCount = (candidate.blockCount ?: (fakeDevice.blockCount - candidate.startBlock)) - dataOffsetSectors,
            cipherNativeId = SingleCipher.AES.nativeId,
            tweakDataOffset = dataOffsetSectors
        )
        
        assertEquals(3840L, decryptedDevice.blockCount)
        assertEquals(3840L, decryptedDevice.blocks)
    }
}

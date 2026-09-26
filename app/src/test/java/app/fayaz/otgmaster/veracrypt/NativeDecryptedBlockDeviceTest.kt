package app.fayaz.otgmaster.veracrypt

import app.fayaz.otgmaster.block.RawBlockDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
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

    /**
     * close() zeroes the master key, so I/O afterwards must fail rather than
     * proceed with an all-zero key.
     *
     * A write is the dangerous direction: it would encrypt real sectors with the
     * zero key and hand them to the still-open raw device, corrupting the volume.
     * close() deliberately leaves the underlying device open, because partitions on
     * one drive share it, so nothing downstream would stop the write.
     */
    @Test
    fun writeAfterCloseFailsAndNeverReachesTheDevice() {
        var writesSeen = 0
        val device = closedDeviceOver(onWrite = { writesSeen++ })

        assertThrows(IOException::class.java) {
            device.writeBlocks(0, ByteArray(512))
        }
        assertEquals("no sector may reach the device after close()", 0, writesSeen)
    }

    @Test
    fun readAfterCloseFails() {
        val device = closedDeviceOver()
        val e = assertThrows(IOException::class.java) { device.readBlocks(0, 1) }
        assertTrue(e.message!!.contains("closed"))
    }

    private fun closedDeviceOver(onWrite: () -> Unit = {}): NativeDecryptedBlockDevice {
        val backing = object : RawBlockDevice {
            override val blockSize: Int = 512
            override val blockCount: Long = 10000
            override fun readBlocks(startBlock: Long, blockCount: Int) =
                ByteArray(blockCount * blockSize)
            override fun writeBlocks(startBlock: Long, data: ByteArray) { onWrite() }
            override fun close() {}
        }
        return NativeDecryptedBlockDevice(
            encryptedDevice = backing,
            masterKey = ByteArray(32) { 1 },
            volumeDataOffset = 256,
            decryptedBlockCount = 1000,
            cipherNativeId = SingleCipher.AES.nativeId,
            tweakDataOffset = 256,
        ).also { it.close() }
    }
}

package app.fayaz.otgmaster.block

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SlicedBlockDeviceTest {

    /** 16 blocks of 4 bytes; block n holds the byte n four times. */
    private class Memory : RawBlockDevice {
        override val blockSize = 4
        override val blockCount = 16L
        val bytes = ByteArray(64) { (it / 4).toByte() }
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            requireInRange(startBlock, blockCount.toLong(), "read")
            return bytes.copyOfRange((startBlock * 4).toInt(), ((startBlock + blockCount) * 4).toInt())
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) {
            requireInRange(startBlock, (data.size / 4).toLong(), "write")
            data.copyInto(bytes, (startBlock * 4).toInt())
        }
        override fun close() {}
    }

    @Test
    fun `the offset is applied once, in both directions`() {
        val disk = Memory()
        val slice = SlicedBlockDevice(disk, firstBlock = 5, blockCount = 4)
        assertArrayEquals(byteArrayOf(5, 5, 5, 5, 6, 6, 6, 6), slice.readBlocks(0, 2))
        slice.writeBlocks(3, byteArrayOf(9, 9, 9, 9))
        assertEquals(9, disk.bytes[8 * 4].toInt())
        assertEquals(7, disk.bytes[7 * 4].toInt())
        assertEquals(9, disk.bytes[9 * 4].toInt())
    }

    @Test
    fun `nothing outside the slice is reachable`() {
        val disk = Memory()
        val slice = SlicedBlockDevice(disk, firstBlock = 5, blockCount = 4)
        assertThrows(IllegalArgumentException::class.java) { slice.readBlocks(3, 2) }
        assertThrows(IllegalArgumentException::class.java) { slice.readBlocks(-1, 1) }
        assertThrows(IllegalArgumentException::class.java) { slice.writeBlocks(4, ByteArray(4)) }
        assertEquals("the refused write reached the disk", 9, disk.bytes[9 * 4].toInt())
    }

    @Test
    fun `requireInRange accepts the last block and refuses one past it`() {
        val disk = Memory()
        disk.requireInRange(15, 1, "read")
        disk.requireInRange(0, 16, "read")
        val e = assertThrows(IllegalArgumentException::class.java) { disk.requireInRange(15, 2, "write") }
        assertEquals("write of 2 blocks at 15 exceeds device (16 blocks)", e.message)
    }
}

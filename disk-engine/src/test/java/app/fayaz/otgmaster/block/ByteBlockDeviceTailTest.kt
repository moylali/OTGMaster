package app.fayaz.otgmaster.block

import me.jahnen.libaums.core.driver.ByteBlockDevice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * A write that ends partway through a sector reads that sector, and only that one
 * (docs/VENDOR_FIXES.md V21).
 *
 * V1 made the tail a read-modify-write, so the bytes after the write survive, but it
 * read the whole span first. At 16 KiB writes that was cheap; with FAT32 writes merged
 * into 1 MiB runs (the V19 experiment) it read 1 GB extra during a 2.2 GB bigwrite on
 * the OnePlus, and ate the gain from merging (BENCHMARK_RUNS.md, 2026-10-16).
 */
class ByteBlockDeviceTailTest {

    /** Memory-backed 512-byte sectors, counting the blocks read. */
    private class Mem(blocks: Int) : RawBlockDevice {
        val bytes = ByteArray(blocks * 512) { (it * 7 + 1).toByte() }
        var blocksRead = 0L
        override val blockSize = 512
        override val blockCount = blocks.toLong()
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            blocksRead += blockCount
            return bytes.copyOfRange((startBlock * 512).toInt(), ((startBlock + blockCount) * 512).toInt())
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) {
            data.copyInto(bytes, (startBlock * 512).toInt())
        }
        override fun close() {}
    }

    private fun writeAndCheck(offset: Int, length: Int, maxBlocksRead: Long) {
        val mem = Mem(4096)
        val expected = mem.bytes.copyOf()
        val data = ByteArray(length) { (it * 13 + 5).toByte() }
        data.copyInto(expected, offset)
        ByteBlockDevice(RawBlockDeviceAdapter(mem)).write(offset.toLong(), ByteBuffer.wrap(data))
        assertArrayEquals("bytes around a $length-byte write at $offset", expected, mem.bytes)
        assertTrue("$length bytes at $offset read ${mem.blocksRead} blocks; at most $maxBlocksRead needed",
            mem.blocksRead <= maxBlocksRead)
    }

    @Test
    fun aLargeWriteEndingMidSectorReadsOnlyItsLastSector() =
        writeAndCheck(offset = 4096, length = (1 shl 20) + 100, maxBlocksRead = 1)

    @Test
    fun aWriteStartingAndEndingMidSectorReadsTwoSectors() =
        writeAndCheck(offset = 4096 + 300, length = (1 shl 20) + 100, maxBlocksRead = 2)

    @Test
    fun aWriteInsideOneSectorKeepsBothSides() =
        writeAndCheck(offset = 512 * 9 + 10, length = 20, maxBlocksRead = 1)

    @Test
    fun aShortTailAfterAPartialHeadKeepsTheRest() =
        writeAndCheck(offset = 512 * 3 + 500, length = 30, maxBlocksRead = 2)

    @Test
    fun anAlignedWriteReadsNothing() =
        writeAndCheck(offset = 8192, length = 65536, maxBlocksRead = 0)
}

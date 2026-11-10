package app.fayaz.otgmaster.partition

import app.fayaz.otgmaster.block.RawBlockDevice
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A GPT header comes from whatever drive is plugged in, so its counts must be checked
 * before they size a read. Review, 2026-11-09: the entry count times the entry size was
 * an Int with no cap, so a bad header overflowed it or asked for a read of gigabytes,
 * and an entry could point past the end of the drive.
 */
class GptParserTest {

    private class MemDevice(blocks: Int) : RawBlockDevice {
        val bytes = ByteArray(blocks * 512)
        var largestRead = 0
        override val blockSize = 512
        override val blockCount = blocks.toLong()
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            require(startBlock >= 0 && blockCount >= 0 && startBlock + blockCount <= this.blockCount) {
                "read of $blockCount blocks at $startBlock past the end of a ${this.blockCount}-block device"
            }
            largestRead = maxOf(largestRead, blockCount)
            val from = (startBlock * 512).toInt()
            return bytes.copyOfRange(from, from + blockCount * 512)
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) = throw UnsupportedOperationException()
        override fun close() {}
    }

    /** A 1 MiB disk with a GPT header at LBA 1 and entries from LBA 2. */
    private fun disk(entries: Int, entrySize: Int, vararg parts: Pair<Long, Long>): MemDevice {
        val d = MemDevice(2048)
        val h = ByteBuffer.wrap(d.bytes, 512, 512).order(ByteOrder.LITTLE_ENDIAN)
        h.put("EFI PART".toByteArray())
        h.position(512 + 72); h.putLong(2); h.putInt(entries); h.putInt(entrySize)
        parts.forEachIndexed { i, (first, last) ->
            val e = ByteBuffer.wrap(d.bytes, 1024 + i * 128, 128).order(ByteOrder.LITTLE_ENDIAN)
            e.putLong(0x4433221100L); e.putLong(0x8877665544L)   // a non-zero type GUID
            e.position(1024 + i * 128 + 32); e.putLong(first); e.putLong(last)
        }
        return d
    }

    @Test
    fun `a valid table parses`() {
        val parts = GptParser.parse(disk(128, 128, 34L to 1000L, 1001L to 2014L))
        assertEquals(listOf(34L to 967L, 1001L to 1014L), parts.map { it.firstLba to it.sectorCount })
    }

    @Test
    fun `an entry count that overflows is refused, not trusted`() {
        val d = disk(Int.MAX_VALUE, 128, 34L to 1000L)
        assertEquals(emptyList<PartitionEntry>(), GptParser.parse(d))
    }

    @Test
    fun `a table larger than the drive is not read`() {
        val d = disk(1_000_000, 128, 34L to 1000L)
        assertEquals(emptyList<PartitionEntry>(), GptParser.parse(d))
        assertEquals("a read was sized from the bad header", 1, d.largestRead)
    }

    @Test
    fun `an entry size that is not a multiple of 128 is refused`() {
        assertEquals(emptyList<PartitionEntry>(), GptParser.parse(disk(4, 200, 34L to 1000L)))
    }

    @Test
    fun `entries outside the drive or backwards are skipped`() {
        val parts = GptParser.parse(disk(128, 128, 34L to 1000L, 1500L to 9_000_000L, 1200L to 1100L))
        assertEquals(listOf(34L to 967L), parts.map { it.firstLba to it.sectorCount })
    }
}

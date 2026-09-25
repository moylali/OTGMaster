package app.fayaz.otgmaster.block

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Correctness first: a cache that returns wrong bytes is far worse than a slow
 * one, and everything below it is hard to verify on-device.
 */
class CachedBlockDeviceTest {

    private class FakeDevice(
        override val blockSize: Int = 512,
        override val blockCount: Long = 4096,
    ) : RawBlockDevice {
        var readCalls = 0
        var blocksRead = 0L
        val store = ByteArray((blockCount * blockSize).toInt()) { (it % 251).toByte() }

        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            readCalls++
            blocksRead += blockCount
            val from = (startBlock * blockSize).toInt()
            return store.copyOfRange(from, from + blockCount * blockSize)
        }

        override fun writeBlocks(startBlock: Long, data: ByteArray) {
            System.arraycopy(data, 0, store, (startBlock * blockSize).toInt(), data.size)
        }

        override fun close() {}
    }

    private fun expected(dev: FakeDevice, startBlock: Long, blocks: Int) =
        dev.store.copyOfRange(
            (startBlock * dev.blockSize).toInt(),
            ((startBlock + blocks) * dev.blockSize).toInt(),
        )

    @Test
    fun `returns the same bytes as the underlying device`() {
        val dev = FakeDevice()
        val cache = CachedBlockDevice(dev, readAheadBytes = 8 * 512)
        for (start in longArrayOf(0, 1, 7, 8, 9, 100, 1000)) {
            for (count in intArrayOf(1, 2, 8, 9, 17)) {
                assertArrayEquals(
                    "start=$start count=$count",
                    expected(dev, start, count),
                    cache.readBlocks(start, count),
                )
            }
        }
    }

    @Test
    fun `repeated reads hit the cache instead of the device`() {
        val dev = FakeDevice()
        val cache = CachedBlockDevice(dev, readAheadBytes = 64 * 512)
        cache.readBlocks(0, 1)
        val afterFirst = dev.readCalls
        repeat(50) { cache.readBlocks(it.toLong(), 1) }
        assertEquals("no further device reads expected", afterFirst, dev.readCalls)
        assertTrue("hits should dominate", cache.hits >= 50)
    }

    @Test
    fun `a small read fetches a whole line, collapsing round trips`() {
        val dev = FakeDevice()
        // The measured pathology: thousands of tiny reads over a small region.
        val cache = CachedBlockDevice(dev, readAheadBytes = 128 * 512)
        repeat(1000) { i -> cache.readBlocks((i % 128).toLong(), 1) }
        assertEquals("should need exactly one underlying read", 1, dev.readCalls)
        assertEquals(128L, dev.blocksRead)
    }

    @Test
    fun `reads spanning several lines are stitched correctly`() {
        val dev = FakeDevice()
        val cache = CachedBlockDevice(dev, readAheadBytes = 4 * 512)
        assertArrayEquals(expected(dev, 2, 20), cache.readBlocks(2, 20))
    }

    @Test
    fun `writes go through and invalidate the affected lines`() {
        val dev = FakeDevice()
        val cache = CachedBlockDevice(dev, readAheadBytes = 8 * 512)
        cache.readBlocks(0, 8)
        val payload = ByteArray(512) { 0x5A }
        cache.writeBlocks(3, payload)
        assertArrayEquals("write must reach the device", payload,
            dev.store.copyOfRange(3 * 512, 4 * 512))
        assertArrayEquals("re-read must see the new bytes", payload,
            cache.readBlocks(3, 1))
    }

    @Test
    fun `eviction keeps the cache bounded and correct`() {
        val dev = FakeDevice()
        val lineBlocks = 8
        val cache = CachedBlockDevice(
            dev,
            maxCacheBytes = 4 * lineBlocks * 512,
            readAheadBytes = lineBlocks * 512,
        )
        for (line in 0 until 64L) cache.readBlocks(line * lineBlocks, 1)
        assertArrayEquals(expected(dev, 0, 1), cache.readBlocks(0, 1))
    }

    @Test
    fun `a final partial line at the end of the device reads correctly`() {
        val dev = FakeDevice(blockCount = 100)
        val cache = CachedBlockDevice(dev, readAheadBytes = 8 * 512)
        assertArrayEquals(expected(dev, 96, 4), cache.readBlocks(96, 4))
        assertArrayEquals(expected(dev, 99, 1), cache.readBlocks(99, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reading past the end is rejected`() {
        val dev = FakeDevice(blockCount = 100)
        CachedBlockDevice(dev).readBlocks(99, 2)
    }

    @Test
    fun `invalidate drops cached lines`() {
        val dev = FakeDevice()
        val cache = CachedBlockDevice(dev, readAheadBytes = 8 * 512)
        cache.readBlocks(0, 8)
        cache.invalidate()
        val before = dev.readCalls
        cache.readBlocks(0, 8)
        assertEquals("must refetch after invalidate", before + 1, dev.readCalls)
    }

    @Test
    fun `zero-block read returns empty and touches nothing`() {
        val dev = FakeDevice()
        val cache = CachedBlockDevice(dev)
        assertEquals(0, cache.readBlocks(5, 0).size)
        assertEquals(0, dev.readCalls)
    }
}

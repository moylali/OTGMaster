package app.fayaz.otgmaster.block

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    fun `a failed write still invalidates, so no stale bytes are served`() {
        // A write that throws may already have changed part of the device. Keeping
        // the pre-write line would then serve stale bytes for content that landed.
        val dev = object : RawBlockDevice {
            override val blockSize = 512
            override val blockCount = 4096L
            var failNext = false
            val store = ByteArray((4096 * 512)) { (it % 251).toByte() }
            override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
                val from = (startBlock * blockSize).toInt()
                return store.copyOfRange(from, from + blockCount * blockSize)
            }
            override fun writeBlocks(startBlock: Long, data: ByteArray) {
                // Mutate, then fail — the awkward case.
                System.arraycopy(data, 0, store, (startBlock * blockSize).toInt(), data.size)
                if (failNext) throw java.io.IOException("simulated transfer failure")
            }
            override fun close() {}
        }
        val cache = CachedBlockDevice(dev, readAheadBytes = 8 * 512)
        cache.readBlocks(0, 1)                       // populate line 0
        dev.failNext = true
        val payload = ByteArray(512) { 0x77 }
        runCatching { cache.writeBlocks(1, payload) }
        assertArrayEquals(
            "must refetch from the device, not serve the stale cached line",
            payload, cache.readBlocks(1, 1),
        )
    }

    @Test
    fun `a write spanning several lines invalidates all of them`() {
        val dev = FakeDevice()
        val cache = CachedBlockDevice(dev, readAheadBytes = 4 * 512)
        cache.readBlocks(0, 20)                      // bypasses (>= line), caches nothing
        repeat(20) { cache.readBlocks(it.toLong(), 1) }   // now populate lines 0..4
        val payload = ByteArray(20 * 512) { 0x3C }
        cache.writeBlocks(0, payload)
        assertArrayEquals("every touched line must be refetched",
            payload, cache.readBlocks(0, 20))
    }

    @Test
    fun `a write patches the cached line instead of forcing a refetch`() {
        val dev = FakeDevice()
        val cache = CachedBlockDevice(dev, readAheadBytes = 8 * 512)
        cache.readBlocks(0, 1)                  // populate line 0
        val before = dev.readCalls
        val payload = ByteArray(512) { 0x5A }
        cache.writeBlocks(3, payload)
        assertArrayEquals("re-read must see the new bytes", payload, cache.readBlocks(3, 1))
        assertEquals("patched line must not trigger a refetch", before, dev.readCalls)
        // Neighbouring blocks in the same line must be untouched.
        assertArrayEquals(expected(dev, 4, 1), cache.readBlocks(4, 1))
    }

    @Test
    fun `a multi-line write patches every cached line it covers`() {
        val dev = FakeDevice()
        val cache = CachedBlockDevice(dev, readAheadBytes = 4 * 512)
        repeat(20) { cache.readBlocks(it.toLong(), 1) }
        val before = dev.readCalls
        val payload = ByteArray(20 * 512) { 0x3C }
        cache.writeBlocks(0, payload)
        // Read back one block at a time: a 20-block read would exceed the line size
        // and bypass the cache, which would hit the device legitimately and say
        // nothing about whether the lines were patched.
        for (b in 0 until 20) {
            assertArrayEquals(
                "block $b", ByteArray(512) { 0x3C }, cache.readBlocks(b.toLong(), 1),
            )
        }
        assertEquals("no refetch after patching", before, dev.readCalls)
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
    fun `a large read is issued as ONE device call, not fragmented per line`() {
        // The bug this pins: routing large reads through lines makes the line size
        // cap the biggest transfer that reaches the device. With 8-block lines a
        // 200-block read became 25 separate calls; with 1-block lines a sequential
        // file read collapsed to 0.48 MB/s.
        val dev = FakeDevice()
        val cache = CachedBlockDevice(dev, readAheadBytes = 8 * 512)
        val data = cache.readBlocks(64, 200)
        assertEquals("must be a single underlying read", 1, dev.readCalls)
        assertEquals(200L, dev.blocksRead)
        assertArrayEquals(expected(dev, 64, 200), data)
        assertEquals(1L, cache.bypasses)
    }

    @Test
    fun `a read exactly one line long bypasses rather than caching`() {
        val dev = FakeDevice()
        val cache = CachedBlockDevice(dev, readAheadBytes = 8 * 512)
        cache.readBlocks(0, 8)
        assertEquals(1, dev.readCalls)
        assertEquals(1L, cache.bypasses)
        // Nothing was cached, so a following small read must fetch.
        cache.readBlocks(0, 1)
        assertEquals(2, dev.readCalls)
    }

    @Test
    fun `a large read with 1-block lines still issues one call`() {
        val dev = FakeDevice()
        val cache = CachedBlockDevice(dev, readAheadBytes = 512)   // no readahead
        val data = cache.readBlocks(10, 128)
        assertEquals("1-block lines must not fragment a 128-block read", 1, dev.readCalls)
        assertArrayEquals(expected(dev, 10, 128), data)
    }

    @Test
    fun `zero-block read returns empty and touches nothing`() {
        val dev = FakeDevice()
        val cache = CachedBlockDevice(dev)
        assertEquals(0, cache.readBlocks(5, 0).size)
        assertEquals(0, dev.readCalls)
    }

    /**
     * A write that lands while a line is being fetched must win.
     *
     * lineFor() releases the lock for the device read, so the fetched bytes can
     * predate a concurrent write. Publishing them would leave the cache disagreeing
     * with the device until the line is evicted — every later read wrong. This is
     * the lost update that "do the I/O outside the lock, then patch" produces if the
     * in-flight fetch is not accounted for.
     */
    @Test
    fun writeDuringFetchIsNotDiscarded() {
        val backing = LatchedDevice(blockSize = 512, blockCount = 64)
        val cache = CachedBlockDevice(backing, maxCacheBytes = 64 * 512, readAheadBytes = 8 * 512)

        // Reader blocks inside the device read for line 0.
        val reader = Thread { cache.readBlocks(0, 1) }
        reader.start()
        assertTrue("reader should reach the device", backing.entered.await(5, TimeUnit.SECONDS))

        // Writer changes block 0 while that fetch is stuck.
        val payload = ByteArray(512) { 0x5A }
        val writer = Thread { cache.writeBlocks(0, payload) }
        writer.start()
        Thread.sleep(150)

        backing.release.countDown()
        reader.join(5_000)
        writer.join(5_000)

        // Whatever the cache now serves must match the device, not the pre-write read.
        val fromCache = cache.readBlocks(0, 1)
        val fromDevice = backing.readBlocks(0, 1)
        assertArrayEquals("cache must agree with the device after the race",
            fromDevice, fromCache)
        assertArrayEquals("the write must be what survived", payload, fromCache)
    }

    /** A bypass read must not hold the lock, so other threads keep serving hits. */
    @Test
    fun bypassReadDoesNotBlockCacheHits() {
        val backing = LatchedDevice(blockSize = 512, blockCount = 64)
        val cache = CachedBlockDevice(backing, maxCacheBytes = 64 * 512, readAheadBytes = 8 * 512)
        cache.readBlocks(16, 1)          // populate a line away from the bypass range
        backing.armFor(0)                // next read of block 0 blocks

        val bypass = Thread { cache.readBlocks(0, 8) }   // >= line size, so a bypass
        bypass.start()
        assertTrue(backing.entered.await(5, TimeUnit.SECONDS))

        val hit = Thread { cache.readBlocks(16, 1) }
        hit.start()
        hit.join(3_000)
        val servedWhileBlocked = !hit.isAlive

        backing.release.countDown()
        bypass.join(5_000)
        assertTrue("a cache hit must not wait behind a bypass transfer", servedWhileBlocked)
    }

    /**
     * Device that blocks on one chosen read until released, so a race can be staged
     * deterministically instead of by timing.
     */
    private class LatchedDevice(
        override val blockSize: Int,
        override val blockCount: Long,
    ) : RawBlockDevice {
        val store = ByteArray((blockCount * blockSize).toInt()) { (it % 97).toByte() }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile private var blockOn: Long = 0

        fun armFor(block: Long) { blockOn = block }

        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            if (startBlock == blockOn && entered.count > 0) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            val out = ByteArray(blockCount * blockSize)
            System.arraycopy(store, (startBlock * blockSize).toInt(), out, 0, out.size)
            return out
        }

        override fun writeBlocks(startBlock: Long, data: ByteArray) {
            System.arraycopy(data, 0, store, (startBlock * blockSize).toInt(), data.size)
        }

        override fun close() {}
    }

    /**
     * The device write and the cache patch must be atomic together.
     *
     * If they are not, two writes to one line can reach the device in one order and
     * patch the cache in the other, leaving the cache disagreeing with the disk until
     * the line is evicted. This test fails on that shape: it blocks the first writer
     * inside the device and asserts no second writer gets in behind it.
     *
     * Written after shipping exactly that bug — the lock was released around the
     * transfer while the commit message claimed otherwise, and no existing test
     * noticed, because all of them were single-threaded.
     */
    @Test
    fun writesAreAtomicWithTheirCachePatch() {
        val backing = ConcurrencyWatchingDevice(blockSize = 512, blockCount = 64)
        val cache = CachedBlockDevice(backing, maxCacheBytes = 64 * 512, readAheadBytes = 8 * 512)
        cache.readBlocks(0, 1)   // populate line 0 so patchRange has something to do

        backing.blockNextWrite()
        val a = ByteArray(512) { 0x0A }
        val b = ByteArray(512) { 0x0B }

        val t1 = Thread { cache.writeBlocks(0, a) }
        t1.start()
        assertTrue("first writer should reach the device",
            backing.writeEntered.await(5, TimeUnit.SECONDS))

        val t2 = Thread { cache.writeBlocks(0, b) }
        t2.start()
        Thread.sleep(200)          // ample time for a second writer to slip through

        backing.releaseWrite.countDown()
        t1.join(5_000)
        t2.join(5_000)

        assertEquals("device must never see two overlapping writes",
            1, backing.maxConcurrentWrites)
        assertArrayEquals("cache must agree with the device",
            backing.readBlocks(0, 1), cache.readBlocks(0, 1))
    }

    /** Backing device that can stall one write and records write overlap. */
    private class ConcurrencyWatchingDevice(
        override val blockSize: Int,
        override val blockCount: Long,
    ) : RawBlockDevice {
        private val store = ByteArray((blockCount * blockSize).toInt()) { (it % 89).toByte() }
        private val inWrite = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile var maxConcurrentWrites = 0; private set
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        @Volatile private var stallNext = false

        fun blockNextWrite() { stallNext = true }

        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            val out = ByteArray(blockCount * blockSize)
            System.arraycopy(store, (startBlock * blockSize).toInt(), out, 0, out.size)
            return out
        }

        override fun writeBlocks(startBlock: Long, data: ByteArray) {
            val n = inWrite.incrementAndGet()
            synchronized(this) { if (n > maxConcurrentWrites) maxConcurrentWrites = n }
            try {
                if (stallNext) {
                    stallNext = false
                    writeEntered.countDown()
                    releaseWrite.await(10, TimeUnit.SECONDS)
                }
                System.arraycopy(data, 0, store, (startBlock * blockSize).toInt(), data.size)
            } finally {
                inWrite.decrementAndGet()
            }
        }

        override fun close() {}
    }
}

package app.fayaz.otgmaster.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 2026-11-22, Galaxy Tab A9+: queryRoots read freeSpace on the binder thread while a benchmark
 * held the exFAT volume's lock deleting a 2.2 GB file, and Android reported the app "not
 * responding" (ContentProvider ANR). These tests hold a real lock the way that delete did.
 */
class RootFreeSpaceTest {

    /** A volume whose freeSpace, like exFAT's, NTFS's and FAT32's, is read under its lock. */
    private class LockedVolume(var free: Long) {
        val lock = ReentrantLock()
        val reads = AtomicInteger()
        val freeSpace: Long get() = lock.withLock { reads.incrementAndGet(); free }
    }

    /** Holds [volume]'s lock on another thread until the returned latch is counted down. */
    private fun holdLock(volume: LockedVolume): CountDownLatch {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        Thread {
            volume.lock.withLock { held.countDown(); release.await(30, TimeUnit.SECONDS) }
        }.apply { isDaemon = true }.start()
        assertTrue(held.await(5, TimeUnit.SECONDS))
        return release
    }

    /** Runs [block] on a thread and says whether it returned within [ms]. */
    private fun <T> returnsWithin(ms: Long, block: () -> T): Pair<Boolean, T?> {
        var value: T? = null
        val t = Thread { value = block() }.apply { isDaemon = true }
        t.start()
        t.join(ms)
        return !t.isAlive to value
    }

    @Test
    fun `a query while a write holds the volume lock returns instead of waiting`() {
        val volume = LockedVolume(free = 1_000)
        val roots = RootFreeSpace(waitMs = 200)
        val release = holdLock(volume)
        try {
            val (returned, value) = returnsWithin(3_000) { roots.availableBytes("d") { volume.freeSpace } }
            assertTrue("queryRoots waited on the volume's lock", returned)
            assertNull("nothing has been read yet, so free space is unknown", value)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `the last value read is reported while the volume is busy`() {
        val volume = LockedVolume(free = 1_000)
        val roots = RootFreeSpace(waitMs = 200)
        assertEquals(1_000L, roots.availableBytes("d") { volume.freeSpace })

        volume.free = 500
        val release = holdLock(volume)
        try {
            val (returned, value) = returnsWithin(3_000) { roots.availableBytes("d") { volume.freeSpace } }
            assertTrue(returned)
            assertEquals(1_000L, value)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `the read left waiting fills in the next answer once the lock is free`() {
        val volume = LockedVolume(free = 1_000)
        val roots = RootFreeSpace(waitMs = 200)
        val release = holdLock(volume)
        assertNull(roots.availableBytes("d") { volume.freeSpace })
        volume.free = 700
        release.countDown()

        var value: Long? = null
        for (i in 1..50) {
            value = roots.availableBytes("d") { volume.freeSpace }
            if (value != null) break
            Thread.sleep(50)
        }
        assertEquals(700L, value)
    }

    @Test
    fun `repeated queries during one long write start only one read`() {
        val volume = LockedVolume(free = 1_000)
        val started = AtomicInteger()
        val roots = RootFreeSpace(waitMs = 50)
        val release = holdLock(volume)
        try {
            repeat(10) { roots.availableBytes("d") { started.incrementAndGet(); volume.freeSpace } }
            assertEquals("each query queued another thread behind the lock", 1, started.get())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `a busy drive does not hold up another drive's answer`() {
        val busy = LockedVolume(free = 1_000)
        val idle = LockedVolume(free = 42)
        val roots = RootFreeSpace(waitMs = 200)
        val release = holdLock(busy)
        try {
            assertNull(roots.availableBytes("busy") { busy.freeSpace })
            assertEquals(42L, roots.availableBytes("idle") { idle.freeSpace })
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `a failed read reports the last value rather than throwing`() {
        val roots = RootFreeSpace(waitMs = 200)
        assertEquals(10L, roots.availableBytes("d") { 10L })
        assertEquals(10L, roots.availableBytes("d") { throw IllegalStateException("unmounted") })
        roots.retainOnly(emptyList())
        assertFalse(roots.availableBytes("d") { throw IllegalStateException("unmounted") } != null)
    }
}

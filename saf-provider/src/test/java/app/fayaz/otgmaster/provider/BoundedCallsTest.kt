package app.fayaz.otgmaster.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A provider query waits on the volume's lock. These hold a real lock the way a long write or
 * delete does, and check that the binder thread gets an answer in bounded time.
 */
class BoundedCallsTest {

    private val lock = ReentrantLock()

    /** Holds [lock] on another thread until the returned latch is counted down. */
    private fun holdLock(): CountDownLatch {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        Thread { lock.withLock { held.countDown(); release.await(30, TimeUnit.SECONDS) } }
            .apply { isDaemon = true }.start()
        assertTrue(held.await(5, TimeUnit.SECONDS))
        return release
    }

    private fun listing(): List<String> = lock.withLock { listOf("a", "b") }

    @Test
    fun `a query on an idle drive returns its result`() {
        val calls = BoundedCalls<List<String>>()
        val out = calls.call("d:/", 1_000, onLate = { fail("not late") }) { listing() }
        assertEquals(BoundedCalls.Done(listOf("a", "b")), out)
    }

    @Test
    fun `a query on a busy drive returns Late in bounded time, then reports the result`() {
        val calls = BoundedCalls<List<String>>()
        val late = LinkedBlockingQueue<Result<List<String>>>()
        val release = holdLock()
        val t0 = System.nanoTime()
        val out = calls.call("d:/", 200, onLate = { late.put(it) }) { listing() }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(BoundedCalls.Late, out)
        assertTrue("waited $ms ms behind the lock", ms < 2_000)
        release.countDown()
        val r = late.poll(5, TimeUnit.SECONDS) ?: fail("the late result never came")
        assertEquals(listOf("a", "b"), (r as Result<List<String>>).getOrThrow())
    }

    @Test
    fun `repeated queries while busy share one outstanding query`() {
        val calls = BoundedCalls<List<String>>()
        val runs = AtomicInteger()
        val release = holdLock()
        try {
            repeat(10) { calls.call("d:/", 30, onLate = {}) { runs.incrementAndGet(); listing() } }
            Thread.sleep(100)
            assertEquals(1, runs.get())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `a query's failure is thrown on the calling thread`() {
        val calls = BoundedCalls<List<String>>()
        try {
            calls.call("d:/x", 1_000, onLate = {}) { throw FileNotFoundException("gone") }
            fail("no exception")
        } catch (e: FileNotFoundException) {
            assertEquals("gone", e.message)
        }
    }

    @Test
    fun `awaitOutstanding waits for the query already running`() {
        val calls = BoundedCalls<List<String>>()
        val release = holdLock()
        assertEquals(BoundedCalls.Late, calls.call("d:/", 50, onLate = {}) { listing() })
        assertEquals(BoundedCalls.Late, calls.awaitOutstanding("d:/", 50))
        release.countDown()
        assertEquals(BoundedCalls.Done(listOf("a", "b")), calls.awaitOutstanding("d:/", 5_000))
    }

    @Test
    fun `after a query finishes, the next one runs again`() {
        val calls = BoundedCalls<Int>()
        val runs = AtomicInteger()
        assertEquals(BoundedCalls.Done(1), calls.call("k", 1_000, onLate = {}) { runs.incrementAndGet() })
        Thread.sleep(50)
        assertEquals(BoundedCalls.Done(2), calls.call("k", 1_000, onLate = {}) { runs.incrementAndGet() })
    }
}

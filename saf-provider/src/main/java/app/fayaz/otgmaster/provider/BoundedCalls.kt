package app.fayaz.otgmaster.provider

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a provider query on a worker and waits for it on the binder thread for a bounded time.
 *
 * Every call the Files app makes into the provider has 20 s before Android reports the app
 * "not responding", and closing that dialog kills the process mid-write. A query waits on the
 * volume's lock, which a long write, delete or Format holds for as long as it runs. A query
 * that is not back in time is left running; [call] returns [Late] and the caller answers
 * without it (a "loading" listing, or the last row it had). When the query finishes, the
 * `onLate` given for it runs with the result, so the caller can tell the client to ask again.
 *
 * Calls with the same key share one outstanding query, so a client asking again and again
 * while the drive is busy does not queue a thread per ask behind the lock.
 */
internal class BoundedCalls<T>(
    /**
     * Keep a late query's result until [awaitOutstanding] takes it. For callers that wait on
     * after [Late]; a caller that never does would keep every late result until its key is
     * asked again, which for a folder listing can be thousands of rows.
     */
    private val holdLateResults: Boolean = false,
    private val executor: Executor = Executors.newCachedThreadPool { r ->
        Thread(r, "ProviderQuery").apply { isDaemon = true }
    },
) {
    sealed class Outcome<out T>
    data class Done<T>(val value: T) : Outcome<T>()
    object Late : Outcome<Nothing>()

    private inner class Call(val key: String, body: () -> T) : FutureTask<T>(body) {
        val late = AtomicBoolean(false)
        @Volatile var onLate: ((Result<T>) -> Unit)? = null
        override fun done() {
            // With holdLateResults a late query stays in the map until [awaitOutstanding] hands its
            // result out, or the next call for the key replaces it. Removed here, a waiter arriving
            // just after it finished found nothing and answered Late with the result in hand.
            val isLate = late.get()
            if (!(isLate && holdLateResults)) inFlight.remove(key, this)
            if (isLate) onLate?.invoke(runCatching { unwrap { get() } })
        }
    }

    private val inFlight = ConcurrentHashMap<String, Call>()

    /**
     * [body]'s value if it is back within [waitMs], else [Late]. A failure of [body] is
     * thrown here, unwrapped, as if it had run on this thread.
     */
    fun call(key: String, waitMs: Long, onLate: (Result<T>) -> Unit, body: () -> T): Outcome<T> {
        val c = callFor(key, body)
        try {
            return Done(unwrap { c.get(waitMs, TimeUnit.MILLISECONDS) })
        } catch (_: TimeoutException) {
            c.onLate = onLate
            c.late.set(true)
            // Finished between the timeout and here: done() may have seen late unset.
            return if (c.isDone) Done(unwrap { c.get() }) else Late
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("interrupted waiting for the drive").apply { initCause(e) }
        }
    }

    /**
     * Waits up to [waitMs] more for the query outstanding under [key], if there is one. With
     * holdLateResults it also hands out the result of a late one that has finished since, once;
     * after that the key is free and the next [call] runs the query again.
     */
    fun awaitOutstanding(key: String, waitMs: Long): Outcome<T> {
        val c = inFlight[key] ?: return Late
        return try {
            Done(unwrap { c.get(waitMs, TimeUnit.MILLISECONDS) })
        } catch (_: TimeoutException) {
            Late
        } finally {
            if (c.isDone) inFlight.remove(key, c)
        }
    }

    private fun callFor(key: String, body: () -> T): Call {
        while (true) {
            inFlight[key]?.let { if (!it.isDone) return it else inFlight.remove(key, it) }
            val fresh = Call(key, body)
            if (inFlight.putIfAbsent(key, fresh) == null) {
                executor.execute(fresh)
                return fresh
            }
        }
    }

    private inline fun <R> unwrap(get: () -> R): R = try {
        get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }
}

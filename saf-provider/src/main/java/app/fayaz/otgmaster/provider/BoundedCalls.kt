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
            inFlight.remove(key, this)
            if (late.get()) onLate?.invoke(runCatching { unwrap { get() } })
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

    /** Waits up to [waitMs] more for the query outstanding under [key], if there is one. */
    fun awaitOutstanding(key: String, waitMs: Long): Outcome<T> {
        val c = inFlight[key] ?: return Late
        return try {
            Done(unwrap { c.get(waitMs, TimeUnit.MILLISECONDS) })
        } catch (_: TimeoutException) {
            Late
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

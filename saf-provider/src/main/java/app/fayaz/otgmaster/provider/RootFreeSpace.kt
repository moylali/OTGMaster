package app.fayaz.otgmaster.provider

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Free space for [VeraCryptDocumentProvider.queryRoots] without waiting on the volume's lock.
 *
 * exFAT, NTFS and FAT32 answer freeSpace under the volume's lock, and a long write or delete
 * holds it for minutes. queryRoots read it on the binder thread, so on 2026-11-22 a query from
 * the system while a benchmark deleted a 2.2 GB file on exFAT (Galaxy Tab A9+) waited past
 * Android's limit and the app was reported "not responding"; closing that dialog killed the
 * app mid-write. The same ANR came on FAT32 the night before. The drive card in the app
 * already reads free space off the main thread for the same reason.
 *
 * Each read runs on a worker and is waited for [waitMs]. If it is not back by then, the last
 * value read for that drive is returned (null before the first), and the read carries on and
 * fills it in for the next query. At most one read per drive is outstanding, so repeated
 * queries during a long write do not pile up threads behind the lock.
 */
internal class RootFreeSpace(
    private val executor: Executor = Executors.newCachedThreadPool { r ->
        Thread(r, "RootFreeSpace").apply { isDaemon = true }
    },
    private val waitMs: Long = 500,
) {
    private val known = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap<String, FutureTask<Long>>()

    fun availableBytes(driveId: String, read: () -> Long): Long? {
        val task = taskFor(driveId, read) ?: return known[driveId]
        return try {
            task.get(waitMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            known[driveId]
        } catch (_: ExecutionException) {
            known[driveId]
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            known[driveId]
        }
    }

    /** Drops what is kept for drives no longer mounted. */
    fun retainOnly(driveIds: Collection<String>) {
        known.keys.retainAll(driveIds.toSet())
        inFlight.keys.retainAll(driveIds.toSet())
    }

    /** The outstanding read for [driveId], or a new one if the last has finished. */
    private fun taskFor(driveId: String, read: () -> Long): FutureTask<Long>? {
        val current = inFlight[driveId]
        if (current != null && !current.isDone) return current
        val fresh = FutureTask { read().also { known[driveId] = it } }
        val won = if (current == null) inFlight.putIfAbsent(driveId, fresh) == null
            else inFlight.replace(driveId, current, fresh)
        if (!won) return inFlight[driveId]
        executor.execute(fresh)
        return fresh
    }
}

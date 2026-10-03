package app.fayaz.otgmaster.exfat

import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * @param readOnly mounted with libexfat's `ro` option. libexfat honours that flag
 *   in some paths only — its FUSE layer, which enforces it, is not built here — so
 *   on-device its mkdir still issued a write that only the block layer stopped.
 *   Every mutating call is refused here instead, before libexfat can change any
 *   in-memory state an unmount would then try to flush.
 */
class ExFatFileSystem(
    private val blockDevice: RawBlockDevice,
    val exfatPtr: Long,
    val readOnly: Boolean = false,
) : FileSystem {

    internal fun checkWritable() {
        if (readOnly) throw IOException("exFAT volume is mounted read-only")
    }

    /**
     * Guards every libexfat call. Was an intrinsic monitor (`synchronized(this)`
     * / `synchronized(fileSystem)`), but ExFatFile.finalize() has to acquire it
     * too, and a finalizer that blocks is fatal: Android's
     * FinalizerWatchdogDaemon kills the process when a single finalize()
     * exceeds 10 seconds. Listing a 10,000-entry directory reliably did that,
     * because the finalizer thread waited on this lock behind multi-second USB
     * reads. A ReentrantLock lets the finalizer make a bounded attempt and back
     * off instead of hanging.
     */
    val lock = ReentrantLock()
    @Volatile
    var isUnmounted = false
        private set
    private val _rootDirectory: ExFatFile by lazy {
        val rootNode = withNative { ExFatNative.getRootNode(exfatPtr) }
        ExFatFile(this, null, rootNode!!)
    }

    /**
     * Node pointers waiting to be released, drained on a normal thread.
     *
     * ExFatFile.finalize() cannot do this itself. Finalizers run on a
     * watchdog-monitored daemon that kills the process if one finalize() exceeds ten
     * seconds, and exfat_put_node flushes the node — which means a real USB write.
     * a492960 bounded the *lock wait* to 250 ms, but not the I/O that follows, so on a
     * slow drive the write alone blew the budget:
     *
     *   FATAL EXCEPTION: FinalizerWatchdogDaemon
     *   TimeoutException: ExFatFile.finalize() timed out after 10 seconds
     *       at UsbDeviceConnection.native_bulk_request
     *       at LibaumsRawBlockDevice.writeBlocks
     *
     * Observed on a Huawei P20 Lite, where writes run at 0.43 MB/s — four to five
     * times slower than the other test devices, which is why it appeared only there.
     *
     * Bounded on purpose. If the queue is full the node is dropped and left for
     * exfat_unmount to reclaim, which is the same trade-off the previous fix made: a
     * leak until unmount is strictly better than killing the process.
     */
    private val pendingReleases = java.util.concurrent.ArrayBlockingQueue<Long>(512)

    private val releaser: Thread = Thread {
        try {
            while (true) {
                val ptr = pendingReleases.take()
                if (ptr == STOP_RELEASER) break
                // A normal thread may block here as long as it needs to.
                lock.withLock {
                    if (isUnmounted) return@withLock
                    runCatching { ExFatNative.putNode(exfatPtr, ptr) }
                }
            }
        } catch (_: InterruptedException) {
            // Shutting down; exfat_unmount reclaims whatever is left.
        }
    }.apply { name = "ExFatNodeReleaser"; isDaemon = true; start() }

    /**
     * Queues a node for release. Never blocks and never throws, so it is safe to call
     * from a finalizer.
     */
    internal fun releaseNodeLater(nodePtr: Long) {
        if (isUnmounted) return
        pendingReleases.offer(nodePtr)
    }

    /**
     * Runs [body] with the libexfat lock held, refusing to enter native code once
     * unmount() has freed the `struct exfat`.
     *
     * Every native call must go through here. Without the guard, anything still
     * holding an ExFatFile across an unmount — a SAF client with an open
     * ProxyFileDescriptor, a listing on a worker thread, a copy in flight when the
     * stick is pulled — hands libexfat a dangling pointer, and the process dies
     * with SIGSEGV instead of the caller seeing an IOException it can handle:
     *
     *   signal 11 (SIGSEGV), fault addr 0x1a00000060
     *     exfat_utf16_to_utf8 / exfat_get_name / ExFatNative_getRootNode
     *
     * Testing the flag inside the lock is what makes it sound: unmount() sets the
     * flag under this same lock, so it cannot flip between the check and the call.
     */
    fun <T> withNative(body: () -> T): T = lock.withLock {
        if (isUnmounted) throw IOException("exFAT filesystem is unmounted")
        body()
    }

    override val rootDirectory: UsbFile
        get() = _rootDirectory

    /**
     * The label from the volume's label entry, or "exFAT" for a volume without one.
     * It was "exFAT" for every volume, so two exFAT partitions on one drive could
     * not be told apart by label — the benchmark's drive filter matches labels.
     */
    override val volumeLabel: String
        get() = lock.withLock {
            if (isUnmounted) "exFAT" else ExFatNative.getLabel(exfatPtr).ifEmpty { "exFAT" }
        }

    override val capacity: Long
        get() = blockDevice.blockCount * blockDevice.blockSize

    override val occupiedSpace: Long
        get() = capacity - freeSpace

    override val freeSpace: Long
        get() = lock.withLock {
            if (isUnmounted) 0L else ExFatNative.getFreeSpace(exfatPtr)
        }

    override val chunkSize: Int
        get() = blockDevice.blockSize

    override val type: Int
        get() = 0 // exfat partition type, maybe just 0 or custom

    fun unmount() {
        lock.withLock {
            if (isUnmounted) return
            isUnmounted = true
            if (!readOnly) ExFatNative.flush(exfatPtr)
            ExFatNative.unmount(exfatPtr)
        }
        // Stop the releaser after the flag is set, so anything still queued is
        // discarded rather than handed a freed struct exfat. exfat_unmount has already
        // reclaimed those nodes.
        pendingReleases.clear()
        pendingReleases.offer(STOP_RELEASER)
        releaser.interrupt()
    }

    private companion object {
        /** Sentinel telling the releaser thread to finish. Not a valid pointer. */
        const val STOP_RELEASER = -1L
    }
}

package app.fayaz.otgmaster.usb

import android.util.Log
import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.usb.UsbCommunication
import java.nio.ByteBuffer

class LibaumsRawBlockDevice(
    private val driver: BlockDeviceDriver,
    private val communication: UsbCommunication,
    /**
     * The transfer size this drive model was last seen to accept on this phone, or
     * null if it has never been tried. See [UsbTransferQuirks] for why it is kept.
     */
    rememberedTransferBytes: Int? = null,
    /**
     * Called with the new size, in bytes, each time a failure forces a smaller one,
     * so it can be remembered for the next time this drive is plugged in.
     */
    private val onTransferSizeLowered: (Int) -> Unit = {},
    /**
     * Bulk-Only Mass Storage Resets sent so far, by libaums (local patch V25). A
     * parameter so tests can stand in for a drive that resets; see [replayRecentWrites].
     */
    private val bulkResets: () -> Long = { (driver as? me.jahnen.libaums.core.driver.scsi.ScsiBlockDevice)?.resets ?: 0L },
) : RawBlockDevice {
    override val blockSize: Int = driver.blockSize
    override val blockCount: Long = driver.blocks

    /**
     * Largest span we hand to libaums in one call, in blocks.
     *
     * libaums' ScsiBlockDevice does no chunking of its own: it turns whatever it
     * is given into a single SCSI READ(10)/WRITE(10) and a single
     * UsbDeviceConnection.bulkTransfer. Large requests are rejected outright by
     * some USB stacks — a Pixel 10 Pro XL (Android 17) fails a ~1.85 MB transfer
     * with result == -1 / errno 0, while a OnePlus 7 (Android 16) accepts it, so
     * the previous behaviour depended on undefined kernel limits.
     *
     * That path is reached whenever a caller asks for a large contiguous span.
     * The worst offender is mounting exFAT: libexfat loads the whole allocation
     * bitmap in one exfat_pread, which is ~1.85 MB for a 57 GiB volume with 4 KiB
     * clusters (15.1M clusters / 8 bits). Any volume above ~8M clusters trips it.
     *
     * 120 KiB matches the Linux usb-storage driver's default max_sectors (240
     * 512-byte sectors), which exists for the same reason: devices misbehave
     * above it. Expressed in bytes so it stays correct for 4096-byte-sector
     * drives, where 240 blocks would be 960 KiB.
     *
     * Some combinations reject even that. A realme RMX5110 (Android 16) with a
     * SanDisk 3.2Gen1 failed every 120 KiB read (issue #29), after libaums'
     * retries and Reset Recovery, while the partition table's small reads worked.
     * So a chunk that fails goes through [recover]: the drive is un-stuck if it
     * needs it, then the chunk is halved and retried, down to [minTransferBlocks],
     * and the smaller size is kept — for this device by this field, and across
     * attaches by [onTransferSizeLowered]. Re-sending a whole READ(10) or WRITE(10)
     * is safe: V12 resets the drive after a failed transfer, and the same bytes go
     * to the same blocks.
     *
     * [probeTransferSize] settles this at open, so the discovery does not happen
     * half-way through mounting a filesystem.
     */
    @Volatile private var maxTransferBlocks: Int = run {
        // The benchmark's override is deliberately unclamped: it exists to try sizes
        // outside the normal range. A remembered size is clamped, because it comes
        // from storage that outlives the code that wrote it.
        val bytes = transferBytesOverride
            ?: rememberedTransferBytes?.coerceIn(MIN_TRANSFER_BYTES, MAX_TRANSFER_BYTES)
            ?: MAX_TRANSFER_BYTES
        (bytes / blockSize).coerceAtLeast(1)
    }

    init {
        if (transferBytesOverride == null && rememberedTransferBytes != null) {
            app.fayaz.otgmaster.OtgMasterState.log(
                "USB: using ${maxTransferBlocks * blockSize / 1024} KiB transfers, remembered for this drive model")
        }
    }

    /**
     * Successful transfers since the last same-size retry, or -1 if there has been none.
     *
     * One failure says nothing about the size by itself, whether or not the drive
     * needed a port reset to answer again. In #88 the size is the cause: the same
     * transfer sticks the drive again straight after the reset. Elsewhere it is not:
     * a OnePlus 7 with a PNY stick failed the probe's first 120 KiB read and a
     * one-block read alike for 0.8 s after the drive was plugged in, then took 120 KiB
     * for hours; the M30 drops the same sticks once in hours of transfers at a size
     * they otherwise take. Stepping down on the first failure would ratchet those
     * drive models down on those phones for good, since the size is remembered and
     * only ever lowered. So a failure is retried once at the same size, and only a
     * second failure at that size within [SAME_SIZE_RETRY_WINDOW] transfers counts
     * against it.
     */
    @Volatile private var transfersSinceRetry = -1L

    /** The transfer size, in blocks, of the last same-size retry. See [transfersSinceRetry]. */
    @Volatile private var retriedChunk = 0

    /** Whether the failure before that retry needed a port reset. See [stepDown]. */
    @Volatile private var retryFollowedReset = false

    /**
     * The floor for [maxTransferBlocks].
     *
     * 16 KiB is the size every Android release has accepted, and was the floor until
     * a drive was found that refused it. It is 4 KiB now because the floor is only
     * ever reached with the drive proven to be answering — [answersAt] runs first —
     * so the last steps are a real test of the size rather than retries into a drive
     * that has stopped responding. §5.3 of docs/IO_PERFORMANCE.md measures 4 KiB
     * spans at 7.1 MB/s against 22.7 MB/s at 64 KiB, so this is a cost only the
     * drives that need it pay, and [onTransferSizeLowered] means they pay the
     * discovery once rather than on every attach.
     */
    private val minTransferBlocks: Int =
        (MIN_TRANSFER_BYTES / blockSize).coerceAtLeast(1)

    /**
     * Diagnostic lines put in the app's log, so a drive failing in a loop cannot
     * crowd the mount history out of a feedback report (which keeps the most recent
     * 3,500 characters — see FeedbackPayload).
     */
    private val reportedLines = java.util.concurrent.atomic.AtomicInteger(0)

    /** To the app's log — which is what "Report issue" sends — and to logcat. */
    private fun report(line: String) {
        Log.w(TAG, line)
        if (reportedLines.incrementAndGet() <= MAX_REPORTED_LINES) {
            app.fayaz.otgmaster.OtgMasterState.log(line)
        }
    }

    /**
     * Every message in the chain, not just the outermost.
     *
     * libaums throws "MAX_RECOVERY_ATTEMPTS Exceeded" for every transport failure
     * alike and carries the reason as the cause; the reason is the only part that
     * says what went wrong. EPIPE is a stalled endpoint, ENODEV is a drive that has
     * left the bus, and result == -1 with errno 0 is the USB stack refusing the
     * size. Those need three different answers and used to be indistinguishable in
     * a report: the cause reached logcat only, and feedback reports carry the app's
     * own log, never logcat. Issues #29, #71 and #88 were all filed without it.
     */
    private fun causeChain(e: Throwable): String =
        generateSequence(e) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }

    /**
     * A one-block read at [at]: is the drive answering at all?
     *
     * This is the question a failed transfer leaves open, and the whole of the
     * recovery depends on the answer. A drive that refuses the size still answers,
     * and retrying smaller works. A drive the transfer has left stuck answers
     * nothing, and retrying smaller cannot work — in issue #88 a Galaxy S21 FE
     * failed a 64 KiB read, then 32 KiB, then 16 KiB, and a 512-byte read of the
     * VeraCrypt header failed straight afterwards, so every one of those retries
     * was spent on a drive that had already gone.
     */
    private fun answersAt(at: Long): Boolean = try {
        synchronized(transferLock) {
            checkOpen()
            driver.read(at, ByteBuffer.allocate(blockSize))
        }
        true
    } catch (e: Exception) {
        report("USB: a one-block read at block $at failed too: ${causeChain(e)}")
        false
    }

    /**
     * Resets the drive's USB port and re-initialises it.
     *
     * libaums already sends a Bulk-Only Mass Storage Reset and clears both endpoint
     * halts between its own six attempts (ScsiBlockDevice.transferCommand, local
     * patch V12). On the drives in #71 and #88 that is not enough — only unplugging
     * recovered them. USBDEVFS_RESET is the step above it and nothing called it.
     */
    private fun resetPort(): Boolean = try {
        val t0 = System.nanoTime()
        synchronized(transferLock) {
            checkOpen()
            portResets++
            communication.resetDevice()
            driver.init()
        }
        report("USB: port reset and re-initialised in ${(System.nanoTime() - t0) / 1_000_000} ms: " +
            "${driver.blocks} blocks of ${driver.blockSize} B")
        true
    } catch (e: Exception) {
        report("USB: the port reset failed: ${causeChain(e)}")
        false
    } catch (e: LinkageError) {
        // The reset ioctl lives in usb-lib; a missing symbol is an Error, not an
        // Exception, and must fail the reset rather than the app (as in V13).
        report("USB: the port reset is unavailable: ${e.message}")
        false
    }

    /**
     * After [chunk] blocks failed at [at] with [e], whether the caller should retry.
     *
     * Order matters. Un-stick the drive first, then reduce the size: a step-down
     * aimed at a drive that has stopped answering burns six libaums attempts and a
     * 5 s timeout each per size for nothing, and ends by blaming the size for a
     * failure that was not about size.
     */
    private fun recover(op: String, chunk: Int, at: Long, e: Exception): Boolean {
        if (closed) return false
        report("USB: $op of ${chunk * blockSize / 1024} KiB at block $at failed: ${causeChain(e)}")
        val wasReset = !answersAt(at)
        if (wasReset) {
            report("USB: the drive has stopped answering; resetting its USB port")
            if (!resetPort() || !answersAt(at)) {
                report("USB: the drive is still not answering - unplug it and plug it in again")
                return false
            }
        }
        val since = transfersSinceRetry
        val repeated = chunk == retriedChunk && since in 0 until SAME_SIZE_RETRY_WINDOW
        val stuck = wasReset || (repeated && retryFollowedReset)
        retriedChunk = chunk
        retryFollowedReset = wasReset
        transfersSinceRetry = 0
        if (!repeated) {
            report("USB: the drive answers" + (if (wasReset) " again after the port reset" else "") +
                "; retrying the $op at the same size")
            return true
        }
        report("USB: the $op failed again at ${chunk * blockSize / 1024} KiB, $since transfers after the last retry")
        return stepDown(op, chunk, remember = stuck)
    }

    /**
     * Halves the transfer size, down to [minTransferBlocks]; false once it is there.
     * The caller has already established that the drive is answering.
     *
     * [remember] hands the new size to [onTransferSizeLowered], which keeps it for
     * this drive model on this phone, for good. Only when the failures took a port
     * reset to recover from — the drive was stuck, as in #88, where every attach
     * stuck it again at the old size and a replug threw the answer away. A drive
     * that refuses a size while still answering costs one failed transfer per
     * attach, which is no reason to hold every drive of its model back forever: on
     * a OnePlus 7 a PNY stick that takes 120 KiB for hours failed twice within a
     * second of being opened, and would otherwise have been kept at 60 KiB.
     */
    private fun stepDown(op: String, chunk: Int, remember: Boolean): Boolean {
        if (chunk <= minTransferBlocks) return false
        val next = (chunk / 2).coerceAtLeast(minTransferBlocks)
        synchronized(transferLock) { if (next < maxTransferBlocks) maxTransferBlocks = next }
        report("USB: retrying the $op with ${next * blockSize / 1024} KiB transfers")
        // Not for a benchmark override: that is someone deliberately trying sizes
        // this drive may well refuse, and the refusal is the measurement, not a
        // property of the drive worth keeping.
        if (remember && transferBytesOverride == null) onTransferSizeLowered(next * blockSize)
        return true
    }

    /**
     * The most recent writes, oldest first, up to [REPLAY_BYTES]: what a reset may have
     * cost, kept so it can be written again.
     *
     * A drive acknowledges a write when it has the data, not when the data is safe in
     * flash. A Bulk-Only reset or a port reset can then drop work the drive had in
     * hand. On device-matrix Drive 2 (OnePlus 7, 2026-10-30) a write failed at block
     * 17,607,921 and libaums reset the drive six times; afterwards block 17,607,920 -
     * written by the command before, and confirmed - read back as zeros. It begins
     * the 4 KiB unit holding the failed write's first seven blocks: the drive had
     * been rewriting that unit when the reset arrived, and lost all of it. The retry
     * restored the failed write's blocks. Nothing rewrote the one before it, because
     * as far as the app knew, it was already on the drive. The loss is invisible to
     * fsck - one sector of file data - and showed only as a bigwrite read-back mismatch.
     *
     * Writes are idempotent, so after any reset the safe answer is to write this window
     * again, oldest first: the drive ends in the state the app believes it is in.
     */
    private class Written(val block: Long, val data: ByteArray)
    private val recentWrites = ArrayDeque<Written>()
    private var recentBytes = 0
    @Volatile private var portResets = 0L

    private fun resets(): Long = bulkResets() + portResets

    /** Caller holds [transferLock]. Copies: the caller's array may be reused. */
    private fun rememberWrite(block: Long, src: ByteArray, offset: Int, length: Int) {
        if (length > REPLAY_BYTES) {
            recentWrites.clear(); recentBytes = 0
            // Only the tail can be lost to a reset that comes after this write.
            val keep = REPLAY_BYTES - REPLAY_BYTES % blockSize
            rememberWrite(block + (length - keep) / blockSize, src, offset + length - keep, keep)
            return
        }
        while (recentWrites.isNotEmpty() && recentBytes + length > REPLAY_BYTES) {
            recentBytes -= recentWrites.removeFirst().data.size
        }
        recentWrites.addLast(Written(block, src.copyOfRange(offset, offset + length)))
        recentBytes += length
    }

    /**
     * Writes [recentWrites] again, oldest first. Throws if the drive will not take
     * them, rather than carry on over data it may have dropped. A reset during the
     * replay starts it over, a few times at most.
     */
    private fun replayRecentWrites(why: String) {
        synchronized(transferLock) {
            if (recentWrites.isEmpty()) return
            repeat(MAX_REPLAY_ROUNDS) {
                val before = resets()
                for (w in recentWrites) driver.write(w.block, ByteBuffer.wrap(w.data))
                replays++
                report("USB: $why; wrote the last ${recentBytes / 1024} KiB again " +
                    "(${recentWrites.size} writes from block ${recentWrites.first().block}), " +
                    "in case the reset lost them")
                if (resets() == before) return
            }
            throw java.io.IOException("the drive kept resetting while the last writes were written again")
        }
    }

    /** After [recover] said to retry: a reset has happened on the way, so replay first. */
    private fun replayAfterRecovery(op: String, cause: Exception) {
        try {
            replayRecentWrites("recovered from a failed $op")
        } catch (e: Exception) {
            throw java.io.IOException(
                "could not write the last ${recentBytes / 1024} KiB again after a failed $op; " +
                    "data written just before it may not be on the drive: ${causeChain(e)}",
                cause,
            )
        }
    }

    /** How many times [recentWrites] has been written again; for tests and the benchmark. */
    @Volatile var replays = 0L
        private set

    @Volatile private var closed = false

    /**
     * Serialises SCSI traffic on this device.
     *
     * A mass-storage transfer is a CBW, then data, then a CSW on the same pair of
     * bulk endpoints. Two threads interleaving those sequences corrupts both — the
     * wrong CSW is matched to the wrong command — and libaums' ScsiBlockDevice also
     * reuses instance-level command buffers across calls, so concurrent entry
     * scribbles on a command another thread is still sending.
     *
     * Nothing used to reach here concurrently: CachedBlockDevice held its single
     * lock across the physical transfer, which serialised everything above it as a
     * side effect. Releasing that lock (so a cache hit need not queue behind a
     * multi-second USB read) removed the accidental protection, and this replaces it
     * deliberately at the layer that actually owns the endpoints.
     *
     * Held per chunk rather than per request, on purpose. Each driver.read/write of
     * one chunk is a complete, self-contained SCSI transaction, so chunk granularity
     * is sufficient for transport integrity — and it means a 2 GiB sequential read
     * yields the device between chunks instead of locking out a 24-byte metadata
     * read for the whole transfer.
     *
     * This is the only code that holds the driver: LibaumsRawBlockDeviceOpener
     * creates it as a local and hands it straight to this constructor, so there is
     * no path around this lock.
     */
    private val transferLock = Any()

    private fun checkOpen() {
        if (closed) throw java.io.IOException("USB block device is closed")
    }

    override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
        require(blockCount >= 0) { "blockCount must be non-negative" }
        checkOpen()
        val out = ByteArray(blockCount * blockSize)
        var done = 0
        while (done < blockCount) {
            val chunk = minOf(maxTransferBlocks, blockCount - done)
            // Wrap the destination directly (no slice, so array()/arrayOffset stay
            // consistent with what libaums' bulkInTransfer expects) to avoid an
            // extra full-size copy per chunk.
            val view = ByteBuffer.wrap(out, done * blockSize, chunk * blockSize)
            try {
                synchronized(transferLock) {
                    // Re-check inside the lock: close() takes it too, so without this
                    // a transfer could start against a communication that was closed
                    // while this thread waited.
                    checkOpen()
                    val t0 = System.nanoTime()
                    val resetsBefore = resets()
                    driver.read(startBlock + done, view)
                    io.readNanos += System.nanoTime() - t0
                    if (resets() != resetsBefore) replayRecentWrites("a read needed a reset")
                    if (transfersSinceRetry >= 0) transfersSinceRetry++
                    io.readCommands++
                    io.readBytes += chunk.toLong() * blockSize
                }
            } catch (e: Exception) {
                if (recover("read", chunk, startBlock + done, e)) { replayAfterRecovery("read", e); continue }
                // Transfer failures here are otherwise reported with no indication of
                // what was being read, which made a Pixel 10 Pro XL failure impossible
                // to attribute without guesswork. The whole cause chain goes in: this
                // message is the one a feedback report carries (see [causeChain]).
                throw java.io.IOException(
                    "read failed: chunk of $chunk blocks at ${startBlock + done} " +
                    "(request was $blockCount blocks at $startBlock; " +
                    "device has ${this.blockCount} blocks of $blockSize bytes; " +
                    "chunk limit $maxTransferBlocks blocks): ${causeChain(e)}",
                    e,
                )
            }
            done += chunk
        }
        return out
    }

    override fun writeBlocks(startBlock: Long, data: ByteArray) {
        require(data.size % blockSize == 0) { "Write length must be block aligned" }
        checkOpen()
        val total = data.size / blockSize
        var done = 0
        while (done < total) {
            val chunk = minOf(maxTransferBlocks, total - done)
            val view = ByteBuffer.wrap(data, done * blockSize, chunk * blockSize)
            try {
                synchronized(transferLock) {
                    checkOpen()
                    val t0 = System.nanoTime()
                    val resetsBefore = resets()
                    driver.write(startBlock + done, view)
                    io.writeNanos += System.nanoTime() - t0
                    rememberWrite(startBlock + done, data, done * blockSize, chunk * blockSize)
                    if (resets() != resetsBefore) replayRecentWrites("a write needed a reset")
                    if (transfersSinceRetry >= 0) transfersSinceRetry++
                    io.writeCommands++
                    io.writeBytes += chunk.toLong() * blockSize
                }
            } catch (e: Exception) {
                if (recover("write", chunk, startBlock + done, e)) { replayAfterRecovery("write", e); continue }
                throw java.io.IOException(
                    "write failed: chunk of $chunk blocks at ${startBlock + done} " +
                    "(request was $total blocks at $startBlock; " +
                    "device has ${this.blockCount} blocks of $blockSize bytes; " +
                    "chunk limit $maxTransferBlocks blocks): ${causeChain(e)}",
                    e,
                )
            }
            done += chunk
        }
    }

    override fun close() {
        // Under the lock: closing the UsbDeviceConnection while another thread is
        // inside bulkTransfer is a use-after-close in the USB stack, not a clean
        // error. Waiting for the in-flight chunk costs at most one chunk's time.
        synchronized(transferLock) {
            if (closed) return
            closed = true
            communication.close()
        }
    }

    /** SCSI commands this device sent and the time spent inside them, for the benchmark. */
    class Io {
        @Volatile var writeCommands = 0L
        @Volatile var writeBytes = 0L
        @Volatile var writeNanos = 0L
        @Volatile var readCommands = 0L
        @Volatile var readBytes = 0L
        @Volatile var readNanos = 0L
        fun reset() { writeCommands = 0; writeBytes = 0; writeNanos = 0; readCommands = 0; readBytes = 0; readNanos = 0 }
    }
    val io = Io()

    /**
     * Settles on a transfer size this drive accepts, before anything is mounted.
     *
     * The size used to be discovered by failing: the first large read of a mount
     * failed, and in #71 and #88 that read was libexfat's first read of the volume,
     * which left the drive stuck in the middle of mounting it. Worse, what was
     * learned died with this object, so the next attach started at 120 KiB and stuck
     * the drive again — #88's log shows the identical failure after each of three
     * replugs. Probing here moves that discovery to a read of block 0 with no
     * filesystem state in play, and [onTransferSizeLowered] keeps the answer.
     *
     * This is what the Linux usb-storage driver does with its per-device
     * `max_sectors` quirks, for the same devices and the same reason.
     *
     * Must be called before the device is handed to anything else, and only then:
     * it is a plain read through [readBlocks], so it would step the size down while
     * other work was in flight.
     */
    fun probeTransferSize() {
        if (closed) return
        val probe = minOf(maxTransferBlocks.toLong(), blockCount).toInt()
        if (probe <= 0) return
        try {
            val t0 = System.nanoTime()
            readBlocks(0, probe)
            report("USB: a ${probe * blockSize / 1024} KiB read at block 0 took " +
                "${(System.nanoTime() - t0) / 1_000_000} ms; using ${transferBytes / 1024} KiB transfers")
        } catch (e: Exception) {
            // Not fatal: small reads may still work, and the partition table is read
            // one block at a time, so the drive is still worth offering to the user.
            report("USB: this drive would not accept a ${probe * blockSize / 1024} KiB read " +
                "even at block 0; carrying on with ${transferBytes / 1024} KiB")
        }
    }

    /** The transfer size in use now, after any step-down. */
    val transferBytes: Int get() = maxTransferBlocks * blockSize

    /** For the benchmark: use [bytes] per transfer from now on (step-downs still apply). */
    fun setTransferBytes(bytes: Int) {
        synchronized(transferLock) { maxTransferBlocks = (bytes / blockSize).coerceAtLeast(minTransferBlocks) }
    }

    companion object {
        /** Debug builds' benchmark sets this to try other transfer sizes; null in the app. */
        @Volatile var transferBytesOverride: Int? = null
        /** See [maxTransferBlocks]. 120 KiB, matching Linux usb-storage max_sectors. */
        const val MAX_TRANSFER_BYTES = 120 * 1024
        /** See [minTransferBlocks]. */
        const val MIN_TRANSFER_BYTES = 4 * 1024
        /** See [reportedLines]. About one failing request's worth of detail. */
        private const val MAX_REPORTED_LINES = 24
        /** See [transfersSinceRetry]. About 7 MiB at 30 KiB, 28 MiB at 120 KiB. */
        private const val SAME_SIZE_RETRY_WINDOW = 240L
        /** See [recentWrites]: several 120 KiB commands, well past one flash page or erase unit. */
        const val REPLAY_BYTES = 1 shl 20
        /** See [replayRecentWrites]. */
        private const val MAX_REPLAY_ROUNDS = 3
        /** See [causeChain]. libaums nests two deep; the rest is room to spare. */
        private const val MAX_CAUSE_DEPTH = 4
        private const val TAG = "LibaumsRawBlockDevice"
    }
}

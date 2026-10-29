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
        Log.w(TAG, "one-block read at $at failed too: ${e.message}")
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
        synchronized(transferLock) {
            checkOpen()
            communication.resetDevice()
            driver.init()
        }
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
        if (!answersAt(at)) {
            report("USB: the drive has stopped answering; resetting its USB port")
            if (!resetPort() || !answersAt(at)) {
                report("USB: the drive is still not answering - unplug it and plug it in again")
                return false
            }
            report("USB: the drive answers again after the port reset")
        }
        return stepDown(op, chunk)
    }

    /**
     * Halves the transfer size, down to [minTransferBlocks]; false once it is there.
     * The caller has already established that the drive is answering.
     */
    private fun stepDown(op: String, chunk: Int): Boolean {
        if (chunk <= minTransferBlocks) return false
        val next = (chunk / 2).coerceAtLeast(minTransferBlocks)
        synchronized(transferLock) { if (next < maxTransferBlocks) maxTransferBlocks = next }
        report("USB: retrying the $op with ${next * blockSize / 1024} KiB transfers")
        // Not for a benchmark override: that is someone deliberately trying sizes
        // this drive may well refuse, and the refusal is the measurement, not a
        // property of the drive worth keeping.
        if (transferBytesOverride == null) onTransferSizeLowered(next * blockSize)
        return true
    }

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
                    driver.read(startBlock + done, view)
                    io.readNanos += System.nanoTime() - t0
                    io.readCommands++
                    io.readBytes += chunk.toLong() * blockSize
                }
            } catch (e: Exception) {
                if (recover("read", chunk, startBlock + done, e)) continue
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
                    driver.write(startBlock + done, view)
                    io.writeNanos += System.nanoTime() - t0
                    io.writeCommands++
                    io.writeBytes += chunk.toLong() * blockSize
                }
            } catch (e: Exception) {
                if (recover("write", chunk, startBlock + done, e)) continue
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
            readBlocks(0, probe)
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
        private const val MAX_REPORTED_LINES = 16
        /** See [causeChain]. libaums nests two deep; the rest is room to spare. */
        private const val MAX_CAUSE_DEPTH = 4
        private const val TAG = "LibaumsRawBlockDevice"
    }
}

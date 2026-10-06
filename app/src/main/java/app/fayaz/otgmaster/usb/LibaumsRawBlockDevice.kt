package app.fayaz.otgmaster.usb

import android.util.Log
import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.usb.UsbCommunication
import java.nio.ByteBuffer

class LibaumsRawBlockDevice(
    private val driver: BlockDeviceDriver,
    private val communication: UsbCommunication,
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
     * So a chunk that fails is halved and retried, down to [minTransferBlocks],
     * and the smaller size is kept for this device from then on. Re-sending a
     * whole READ(10) or WRITE(10) is safe: V12 resets the drive after a failed
     * transfer, and the same bytes go to the same blocks.
     */
    @Volatile private var maxTransferBlocks: Int =
        (MAX_TRANSFER_BYTES / blockSize).coerceAtLeast(1)

    /** The floor for [maxTransferBlocks]: 16 KiB, the bulk transfer size every Android release has accepted. */
    private val minTransferBlocks: Int =
        (MIN_TRANSFER_BYTES / blockSize).coerceAtLeast(1)

    /**
     * After [chunk] blocks failed with [e], whether to retry smaller. Lowers
     * [maxTransferBlocks] if so. Not for a closed device: that failure is not
     * about size.
     */
    private fun stepDown(chunk: Int, e: Exception, op: String, at: Long): Boolean {
        if (closed || chunk <= minTransferBlocks) return false
        val next = (chunk / 2).coerceAtLeast(minTransferBlocks)
        synchronized(transferLock) { if (next < maxTransferBlocks) maxTransferBlocks = next }
        Log.w(TAG, "$op of $chunk blocks at $at failed (${e.message}); retrying with chunks of $next blocks")
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
                    driver.read(startBlock + done, view)
                }
            } catch (e: Exception) {
                if (stepDown(chunk, e, "read", startBlock + done)) continue
                // Transfer failures here are otherwise reported with no indication of
                // what was being read, which made a Pixel 10 Pro XL failure impossible
                // to attribute without guesswork. The cause's message goes in too: the
                // app's log shows only this one (issue #29 had nothing else to go on).
                throw java.io.IOException(
                    "read failed: chunk of $chunk blocks at ${startBlock + done} " +
                    "(request was $blockCount blocks at $startBlock; " +
                    "device has ${this.blockCount} blocks of $blockSize bytes; " +
                    "chunk limit $maxTransferBlocks blocks): ${e.message}",
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
                    driver.write(startBlock + done, view)
                }
            } catch (e: Exception) {
                if (stepDown(chunk, e, "write", startBlock + done)) continue
                throw java.io.IOException(
                    "write failed: chunk of $chunk blocks at ${startBlock + done} " +
                    "(request was $total blocks at $startBlock; " +
                    "device has ${this.blockCount} blocks of $blockSize bytes; " +
                    "chunk limit $maxTransferBlocks blocks): ${e.message}",
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

    companion object {
        /** See [maxTransferBlocks]. 120 KiB, matching Linux usb-storage max_sectors. */
        const val MAX_TRANSFER_BYTES = 120 * 1024
        /** See [minTransferBlocks]. */
        const val MIN_TRANSFER_BYTES = 16 * 1024
        private const val TAG = "LibaumsRawBlockDevice"
    }
}

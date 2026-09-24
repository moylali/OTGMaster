package app.fayaz.otgmaster.usb

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
     */
    private val maxTransferBlocks: Int =
        (MAX_TRANSFER_BYTES / blockSize).coerceAtLeast(1)

    @Volatile private var closed = false

    override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
        require(blockCount >= 0) { "blockCount must be non-negative" }
        val out = ByteArray(blockCount * blockSize)
        var done = 0
        while (done < blockCount) {
            val chunk = minOf(maxTransferBlocks, blockCount - done)
            // Wrap the destination directly (no slice, so array()/arrayOffset stay
            // consistent with what libaums' bulkInTransfer expects) to avoid an
            // extra full-size copy per chunk.
            val view = ByteBuffer.wrap(out, done * blockSize, chunk * blockSize)
            driver.read(startBlock + done, view)
            done += chunk
        }
        return out
    }

    override fun writeBlocks(startBlock: Long, data: ByteArray) {
        require(data.size % blockSize == 0) { "Write length must be block aligned" }
        val total = data.size / blockSize
        var done = 0
        while (done < total) {
            val chunk = minOf(maxTransferBlocks, total - done)
            val view = ByteBuffer.wrap(data, done * blockSize, chunk * blockSize)
            driver.write(startBlock + done, view)
            done += chunk
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        communication.close()
    }

    companion object {
        /** See [maxTransferBlocks]. 120 KiB, matching Linux usb-storage max_sectors. */
        const val MAX_TRANSFER_BYTES = 120 * 1024
    }
}

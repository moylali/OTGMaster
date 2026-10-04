package app.fayaz.otgmaster.luks

import app.fayaz.otgmaster.bitlocker.BitLockerNative
import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.block.requireInRange
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * A LUKS2 data segment encrypted aes-xts-plain64 with sectors larger than 512
 * bytes — cryptsetup's default since 2.4 wherever the device allows it.
 *
 * Each [sectorSize]-byte sector is one XTS data unit, and its tweak counts in
 * those sectors (dm-crypt's iv_large_sectors, which LUKS2 always uses), starting
 * at [ivTweak] for the segment's first sector. Device blocks stay 512 bytes, so a
 * read is widened to whole sectors and a partial write read-modify-writes them.
 *
 * The XTS itself is bitlocker_crypto's (BitLocker's XTS mode is the same
 * construction); only the sector size and starting tweak differ.
 */
class LargeSectorXtsDevice(
    private val device: RawBlockDevice,
    /** First device block of the data segment. */
    private val payloadStartBlock: Long,
    private val payloadBlocks: Long,
    masterKey: ByteArray,
    private val sectorSize: Int,
    private val ivTweak: Long,
) : RawBlockDevice, BlockDeviceDriver {

    private val bs = device.blockSize
    private val lifecycle = ReentrantReadWriteLock()

    @Volatile private var handle: Long =
        BitLockerNative.newContext(BitLockerNative.MODE_XTS, masterKey, sectorSize).also {
            masterKey.fill(0)
            if (it == 0L) throw IOException("cannot set up aes-xts with $sectorSize-byte sectors")
        }

    init {
        require(sectorSize % bs == 0) { "sector size $sectorSize is not a multiple of the device block $bs" }
    }

    override val blockSize: Int get() = bs
    override val blockCount: Long get() = payloadBlocks / (sectorSize / bs) * (sectorSize / bs)
    override val blocks: Long get() = blockCount
    override fun init() {}

    private fun checkOpen(): Long = handle.takeIf { it != 0L }
        ?: throw IOException("LUKS volume is closed (it was unmounted)")

    private fun readWhole(from: Long, to: Long): ByteArray {
        val h = checkOpen()
        val data = device.readBlocks(payloadStartBlock + from / bs, ((to - from) / bs).toInt())
        val rc = BitLockerNative.cryptSectors(h, false, ivTweak + from / sectorSize, data, 0, data.size)
        if (rc != 0) throw IOException("LUKS2 decryption failed at byte $from (rc=$rc)")
        return data
    }

    override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray = lifecycle.read {
        requireInRange(startBlock, blockCount.toLong(), "read")
        val from = startBlock * bs
        val to = from + blockCount.toLong() * bs
        val aFrom = from / sectorSize * sectorSize
        val aTo = (to + sectorSize - 1) / sectorSize * sectorSize
        val plain = readWhole(aFrom, aTo)
        if (aFrom == from && aTo == to) plain
        else plain.copyOfRange((from - aFrom).toInt(), (to - aFrom).toInt())
    }

    override fun writeBlocks(startBlock: Long, data: ByteArray): Unit = lifecycle.read {
        val h = checkOpen()
        requireInRange(startBlock, data.size.toLong() / bs, "write")
        val from = startBlock * bs
        val to = from + data.size
        val aFrom = from / sectorSize * sectorSize
        val aTo = (to + sectorSize - 1) / sectorSize * sectorSize
        val buf = if (aFrom == from && aTo == to) data.copyOf()
            else readWhole(aFrom, aTo).also { System.arraycopy(data, 0, it, (from - aFrom).toInt(), data.size) }
        val rc = BitLockerNative.cryptSectors(h, true, ivTweak + aFrom / sectorSize, buf, 0, buf.size)
        if (rc != 0) throw IOException("LUKS2 encryption failed at byte $aFrom (rc=$rc)")
        device.writeBlocks(payloadStartBlock + aFrom / bs, buf)
    }

    override fun read(deviceOffset: Long, buffer: ByteBuffer) {
        val n = buffer.remaining()
        require(n % bs == 0) { "buffer must be a whole number of blocks" }
        buffer.put(readBlocks(deviceOffset, n / bs))
    }

    override fun write(deviceOffset: Long, buffer: ByteBuffer) {
        val n = buffer.remaining()
        require(n % bs == 0) { "buffer must be a whole number of blocks" }
        writeBlocks(deviceOffset, ByteArray(n).also { buffer.get(it) })
    }

    /** Frees and zeroes the key schedule; the shared USB device stays open. */
    override fun close() = lifecycle.write {
        val h = handle
        handle = 0L
        if (h != 0L) BitLockerNative.freeContext(h)
    }
}

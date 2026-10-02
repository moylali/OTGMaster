package app.fayaz.otgmaster.bitlocker

import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * The plaintext of an unlocked BitLocker volume, readable and writable.
 *
 * Three kinds of region, as cryptsetup maps them:
 *  - the first [BitLockerHeader.volumeHeaderSize] bytes are the volume's real first
 *    sectors, which BitLocker moved to [BitLockerHeader.volumeHeaderOffset] when it
 *    put its own signature in their place. They are read from there, encrypted
 *    under the sector numbers of where they now lie;
 *  - the three 64 KiB FVE metadata areas, and the place those first sectors were
 *    moved to, read as zeros and refuse writes — the filesystem has them reserved,
 *    so a write there means something is wrong, and letting it through would
 *    overwrite the keys;
 *  - everything else is encrypted in place.
 *
 * Blocks are the underlying device's (512 bytes); an encryption sector may be 4096,
 * in which case partial-sector I/O decrypts or read-modify-writes the whole one.
 */
class BitLockerBlockDevice internal constructor(
    private val device: RawBlockDevice,
    private val startBlock: Long,
    private val partitionBlocks: Long,
    val header: BitLockerHeader,
    fvek: ByteArray,
) : RawBlockDevice, BlockDeviceDriver {

    private val bs = device.blockSize
    private val es = header.sectorSize

    @Volatile private var handle: Long = BitLockerNative.newContext(header.mode, fvek, es).also {
        fvek.fill(0)
        if (it == 0L) throw IOException("could not set up the ${header.cipherName} key")
    }

    /** [from, to) byte ranges that read as zeros and refuse writes, sorted. */
    private val holes: List<LongRange> = (header.metadataOffsets.map {
        it until it + BitLockerHeader.METADATA_AREA_SIZE
    } + listOf(header.volumeHeaderOffset until header.volumeHeaderOffset + header.volumeHeaderSize))
        .sortedBy { it.first }

    override val blockSize: Int get() = bs
    override val blockCount: Long get() = partitionBlocks
    override val blocks: Long get() = partitionBlocks

    override fun init() {}

    /** One stretch of plaintext with a single mapping: zeros, or ciphertext at +delta. */
    private class Run(val start: Long, val end: Long, val zero: Boolean, val delta: Long)

    /** Splits plaintext byte range [from, to) — sector-aligned — into runs. */
    private fun runs(from: Long, to: Long): List<Run> {
        val out = mutableListOf<Run>()
        var p = from
        while (p < to) {
            val hdrEnd = header.volumeHeaderSize
            if (p < hdrEnd) {
                val e = minOf(to, hdrEnd)
                out += Run(p, e, zero = false, delta = header.volumeHeaderOffset)
                p = e
                continue
            }
            val hole = holes.firstOrNull { p in it }
            if (hole != null) {
                val e = minOf(to, hole.last + 1)
                out += Run(p, e, zero = true, delta = 0)
                p = e
                continue
            }
            val nextHole = holes.map { it.first }.filter { it > p }.minOrNull() ?: Long.MAX_VALUE
            val e = minOf(to, nextHole)
            out += Run(p, e, zero = false, delta = 0)
            p = e
        }
        return out
    }

    /**
     * Crypt calls hold the read side, [close] the write side, so the native key
     * schedules cannot be freed under a decryption in flight. FAT32 has no
     * filesystem lock of its own to rule that out.
     */
    private val lifecycle = ReentrantReadWriteLock()

    private fun checkOpen(): Long {
        val h = handle
        if (h == 0L) throw IOException("BitLocker volume is closed (it was unmounted)")
        return h
    }

    /** Reads and decrypts whole encryption sectors covering plaintext [from, to). */
    private fun readPlain(from: Long, to: Long): ByteArray {
        val h = checkOpen()
        val out = ByteArray((to - from).toInt())
        for (r in runs(from, to)) {
            if (r.zero) continue
            val phys = r.start + r.delta
            val data = device.readBlocks(startBlock + phys / bs, ((r.end - r.start) / bs).toInt())
            val rc = BitLockerNative.cryptSectors(h, false, phys / es, data, 0, data.size)
            if (rc != 0) throw IOException("BitLocker decryption failed at byte $phys (rc=$rc)")
            System.arraycopy(data, 0, out, (r.start - from).toInt(), data.size)
        }
        return out
    }

    override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray = lifecycle.read {
        val from = startBlock * bs
        val to = from + blockCount.toLong() * bs
        // Widen to whole encryption sectors (only differs when es > bs).
        val aFrom = from / es * es
        val aTo = (to + es - 1) / es * es
        val plain = readPlain(aFrom, aTo)
        if (aFrom == from && aTo == to) plain
        else plain.copyOfRange((from - aFrom).toInt(), (to - aFrom).toInt())
    }

    override fun writeBlocks(startBlock: Long, data: ByteArray): Unit = lifecycle.read {
        val h = checkOpen()
        val from = startBlock * bs
        val to = from + data.size
        val aFrom = from / es * es
        val aTo = (to + es - 1) / es * es
        val plain = if (aFrom == from && aTo == to) data.copyOf()
            else readPlain(aFrom, aTo).also { System.arraycopy(data, 0, it, (from - aFrom).toInt(), data.size) }
        for (r in runs(aFrom, aTo)) {
            if (r.zero) throw IOException(
                "refusing a write into BitLocker's own metadata at byte ${r.start}: the filesystem " +
                    "should never address it, and writing it would destroy the keys")
            val phys = r.start + r.delta
            val chunk = plain.copyOfRange((r.start - aFrom).toInt(), (r.end - aFrom).toInt())
            val rc = BitLockerNative.cryptSectors(h, true, phys / es, chunk, 0, chunk.size)
            if (rc != 0) throw IOException("BitLocker encryption failed at byte $phys (rc=$rc)")
            device.writeBlocks(this.startBlock + phys / bs, chunk)
        }
    }

    override fun read(deviceOffset: Long, buffer: ByteBuffer) {
        val n = buffer.remaining()
        require(n % bs == 0) { "buffer must be a whole number of blocks" }
        buffer.put(readBlocks(deviceOffset, n / bs))
    }

    override fun write(deviceOffset: Long, buffer: ByteBuffer) {
        val n = buffer.remaining()
        require(n % bs == 0) { "buffer must be a whole number of blocks" }
        val data = ByteArray(n).also { buffer.get(it) }
        writeBlocks(deviceOffset, data)
    }

    /** Frees and zeroes the key schedules; the shared USB device stays open. */
    override fun close() = lifecycle.write {
        val h = handle
        handle = 0L
        if (h != 0L) BitLockerNative.freeContext(h)
    }
}

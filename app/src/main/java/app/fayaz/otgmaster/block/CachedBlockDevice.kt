package app.fayaz.otgmaster.block

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.nio.ByteBuffer

/**
 * Read cache with readahead, sitting above the decrypted block device.
 *
 * Measured motivation (docs/IO_PERFORMANCE.md §5.4/§5.5): listing a 10,000-entry
 * exFAT directory issued **100,844 preads averaging 24 bytes** — only 2.3 MiB of
 * real data — and took 166 seconds, because libexfat reads directory and FAT
 * entries unbuffered and nothing below it cached anything. Every 24-byte read
 * became a full USB round trip plus a 512-byte AES-XTS sector decryption, at
 * ~1.65 ms each.
 *
 * Two properties do the work:
 *  - a hit skips both the USB transfer *and* the decryption, because what is
 *    cached is plaintext;
 *  - a miss fetches a whole [readAheadBytes] line rather than the blocks asked
 *    for, which is why 64 KiB is the default: the block layer sustains ~22 MB/s
 *    at 64 KiB spans versus ~7 MB/s at 4 KiB, so larger lines are close to free.
 *
 * Deliberately write-through, not write-back. There is no journaling anywhere in
 * this stack, and a mid-write disconnect on a VeraCrypt volume is not recoverable
 * by ordinary tools, so buffering writes would trade a real correctness risk for
 * throughput we do not need.
 *
 * Access is serialised. SCSI command/response pairs must not interleave on one
 * device, and DocumentsProvider calls arrive on a binder thread pool while the UI
 * reads on its own — libaums has no locking of its own (see §7.2).
 */
class CachedBlockDevice(
    private val delegate: RawBlockDevice,
    maxCacheBytes: Int = DEFAULT_CACHE_BYTES,
    readAheadBytes: Int = DEFAULT_READAHEAD_BYTES,
) : RawBlockDevice, BlockDeviceDriver {

    override val blockSize: Int get() = delegate.blockSize
    override val blockCount: Long get() = delegate.blockCount
    override val blocks: Long get() = delegate.blockCount

    /** The uncached device, so callers can measure both paths. */
    val uncached: RawBlockDevice get() = delegate

    private val blocksPerLine: Int = (readAheadBytes / delegate.blockSize).coerceAtLeast(1)
    private val maxLines: Int =
        (maxCacheBytes / (blocksPerLine * delegate.blockSize)).coerceAtLeast(4)

    private val lock = Any()

    /** Evicted plaintext is zeroed rather than left for the GC to recycle. */
    private val lines = object : LinkedHashMap<Long, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>): Boolean {
            if (size <= maxLines) return false
            eldest.value.fill(0)
            return true
        }
    }

    @Volatile var hits: Long = 0; private set
    @Volatile var misses: Long = 0; private set
    @Volatile var delegateBlocksRead: Long = 0; private set

    override fun init() { /* delegate is already initialised */ }

    override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
        require(blockCount >= 0) { "blockCount must be non-negative" }
        require(startBlock >= 0) { "startBlock must be non-negative" }
        require(startBlock + blockCount <= this.blockCount) {
            "read of $blockCount blocks at $startBlock exceeds device (${this.blockCount} blocks)"
        }
        if (blockCount == 0) return ByteArray(0)

        val out = ByteArray(blockCount * blockSize)
        synchronized(lock) {
            var done = 0
            while (done < blockCount) {
                val block = startBlock + done
                val lineIndex = block / blocksPerLine
                val line = lineFor(lineIndex)
                val blocksInLine = line.size / blockSize
                val offsetInLine = (block - lineIndex * blocksPerLine).toInt()
                // Short only for a final partial line at the end of the device.
                val available = blocksInLine - offsetInLine
                check(available > 0) { "cache line $lineIndex too short for block $block" }
                val take = minOf(available, blockCount - done)
                System.arraycopy(
                    line, offsetInLine * blockSize,
                    out, done * blockSize,
                    take * blockSize,
                )
                done += take
            }
        }
        return out
    }

    /** Caller must hold [lock]. */
    private fun lineFor(lineIndex: Long): ByteArray {
        lines[lineIndex]?.let {
            hits++
            return it
        }
        misses++
        val start = lineIndex * blocksPerLine
        val count = minOf(blocksPerLine.toLong(), blockCount - start).toInt()
        check(count > 0) { "cache line $lineIndex starts past the end of the device" }
        val data = delegate.readBlocks(start, count)
        delegateBlocksRead += count
        lines[lineIndex] = data
        return data
    }

    override fun writeBlocks(startBlock: Long, data: ByteArray) {
        require(data.size % blockSize == 0) { "write length must be block aligned" }
        synchronized(lock) {
            delegate.writeBlocks(startBlock, data)
            // Invalidate rather than patch in place: a partial-line write would
            // otherwise leave a line that looks complete but is stale elsewhere.
            val written = data.size / blockSize
            if (written > 0) {
                val first = startBlock / blocksPerLine
                val last = (startBlock + written - 1) / blocksPerLine
                for (i in first..last) lines.remove(i)?.fill(0)
            }
        }
    }

    // --- BlockDeviceDriver: deviceOffset is a block number, per ByteBlockDevice ---

    override fun read(deviceOffset: Long, buffer: ByteBuffer) {
        val bytes = buffer.remaining()
        require(bytes % blockSize == 0) { "buffer size must be block-aligned" }
        buffer.put(readBlocks(deviceOffset, bytes / blockSize))
    }

    override fun write(deviceOffset: Long, buffer: ByteBuffer) {
        val bytes = buffer.remaining()
        require(bytes % blockSize == 0) { "buffer size must be block-aligned" }
        val data = ByteArray(bytes)
        buffer.get(data)
        writeBlocks(deviceOffset, data)
    }

    fun stats(): String {
        val total = hits + misses
        val rate = if (total > 0) 100.0 * hits / total else 0.0
        return "cache: %d hits, %d misses (%.1f%% hit), %d blocks from device"
            .format(hits, misses, rate, delegateBlocksRead)
    }

    fun resetStats() {
        hits = 0; misses = 0; delegateBlocksRead = 0
    }

    /** Drops and zeroes every cached line. */
    fun invalidate() {
        synchronized(lock) {
            lines.values.forEach { it.fill(0) }
            lines.clear()
        }
    }

    override fun close() {
        // Cached lines are plaintext from a possibly-encrypted volume, so zero
        // them rather than relying on the GC.
        invalidate()
        delegate.close()
    }

    companion object {
        /** 8 MiB of plaintext; 128 lines at the default line size. */
        const val DEFAULT_CACHE_BYTES = 8 * 1024 * 1024

        /**
         * 64 KiB: the flat part of the measured throughput curve (~22 MB/s at
         * 64 KiB and 512 KiB spans, ~7 MB/s at 4 KiB), and within the 120 KiB
         * per-transfer limit in LibaumsRawBlockDevice.
         */
        const val DEFAULT_READAHEAD_BYTES = 64 * 1024
    }
}

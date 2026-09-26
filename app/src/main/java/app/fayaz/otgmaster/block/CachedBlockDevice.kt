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
    /** Requests that skipped the cache because they were at least a line long. */
    @Volatile var bypasses: Long = 0; private set

    override fun init() { /* delegate is already initialised */ }

    override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
        require(blockCount >= 0) { "blockCount must be non-negative" }
        require(startBlock >= 0) { "startBlock must be non-negative" }
        require(startBlock + blockCount <= this.blockCount) {
            "read of $blockCount blocks at $startBlock exceeds device (${this.blockCount} blocks)"
        }
        if (blockCount == 0) return ByteArray(0)

        // Requests at least a line long bypass the cache.
        //
        // Routing them through lines fragments one efficient transfer into many
        // line-sized ones, because the line size then caps the largest read that
        // ever reaches the device. With 1-block lines a 128-block request became
        // 128 separate 512-byte SCSI commands, measured at 0.48 MB/s against
        // 6.35 MB/s with no cache at all. It would also evict the metadata the
        // cache exists to hold, in favour of file data that is rarely re-read.
        if (blockCount >= blocksPerLine) {
            // Counters under the lock; the transfer itself outside it. A bypass reads
            // nothing from the cache and writes nothing to it, so there is no state to
            // protect — holding the lock here only blocked cache hits on other
            // threads behind a multi-second USB read. DocumentsProvider metadata calls
            // arrive on binder threads while file I/O runs on the proxy thread, so that
            // contention is real on FAT32, which has no filesystem-level lock of its
            // own to serialise them first.
            synchronized(lock) {
                bypasses++
                delegateBlocksRead += blockCount
            }
            return delegate.readBlocks(startBlock, blockCount)
        }

        val out = ByteArray(blockCount * blockSize)
        run {
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

    /**
     * Returns line [lineIndex], fetching it if absent.
     *
     * Takes [lock] only to look up and to publish; the device read happens with the
     * lock released, so a cache hit on another thread is not stuck behind it.
     *
     * The window that opens is a write landing on this line while the fetch is in
     * flight: publishing the fetched data would then discard that write and leave the
     * cache disagreeing with the disk for as long as the line survives. [fetching]
     * and [dirtiedWhileFetching] close it — a write to a line being fetched marks it,
     * and the fetch is then used for this read but not published.
     *
     * Publishing outside the lock, or relying on a ConcurrentHashMap, does not work:
     * the invariant is "device and cache agree", which spans two operations, and
     * making each one individually atomic does not make the pair atomic.
     */
    private fun lineFor(lineIndex: Long): ByteArray {
        synchronized(lock) {
            lines[lineIndex]?.let {
                hits++
                return it
            }
            misses++
            fetching.add(lineIndex)
        }

        val start = lineIndex * blocksPerLine
        val count = minOf(blocksPerLine.toLong(), blockCount - start).toInt()
        check(count > 0) { "cache line $lineIndex starts past the end of the device" }

        val data: ByteArray
        try {
            data = delegate.readBlocks(start, count)
        } catch (t: Throwable) {
            synchronized(lock) {
                fetching.remove(lineIndex)
                dirtiedWhileFetching.remove(lineIndex)
            }
            throw t
        }

        synchronized(lock) {
            delegateBlocksRead += count
            fetching.remove(lineIndex)
            val superseded = dirtiedWhileFetching.remove(lineIndex)
            if (superseded) {
                // A write changed these blocks while we were reading them. Whatever
                // the device held mid-write is not what the cache should serve, and
                // the writer has already patched or dropped the line.
                lines[lineIndex]?.let { return it }
            } else {
                lines[lineIndex] = data
            }
        }
        return data
    }

    /** Caller must hold [lock]. */
    private fun markFetchesDirty(startBlock: Long, blockCount: Int) {
        if (fetching.isEmpty() || blockCount <= 0) return
        val firstLine = startBlock / blocksPerLine
        val lastLine = (startBlock + blockCount - 1) / blocksPerLine
        var i = firstLine
        while (i <= lastLine) {
            if (i in fetching) dirtiedWhileFetching.add(i)
            i++
        }
    }

    /** Lines with a device read in flight, and those a write landed on meanwhile. */
    private val fetching = HashSet<Long>()
    private val dirtiedWhileFetching = HashSet<Long>()

    override fun writeBlocks(startBlock: Long, data: ByteArray) {
        require(data.size % blockSize == 0) { "write length must be block aligned" }
        synchronized(lock) {
            // Flag any line currently being fetched that this write overlaps, before
            // touching the device. lineFor() released the lock for its read, so a
            // fetch in flight is about to publish blocks that predate this write; it
            // must not. Flag first, so a write that fails partway is covered too.
            markFetchesDirty(startBlock, data.size / blockSize)
            try {
                delegate.writeBlocks(startBlock, data)
            } catch (e: Throwable) {
                // The write may still have changed part of the device, so keeping
                // the pre-write line would serve stale bytes for content that did
                // land. Dropping a line is always safe — worst case is a refetch.
                invalidateRange(startBlock, data.size / blockSize)
                throw e
            }
            // Patch in place rather than invalidating.
            //
            // We hold the lock and are the only writer, so once the write succeeds
            // the cached line with the new bytes applied is exactly what the device
            // holds. Invalidating instead forced the next read-modify-write to
            // refetch the whole line: measured at 1.08 MB/s writing versus
            // 1.85 MB/s uncached on FAT32, because ByteBlockDevice does a RMW per
            // unaligned write and each one dropped a 64 KiB line.
            patchRange(startBlock, data)
        }
    }

    /** Caller must hold [lock]. Applies written bytes to any cached lines. */
    private fun patchRange(startBlock: Long, data: ByteArray) {
        val written = data.size / blockSize
        if (written <= 0) return
        var done = 0
        while (done < written) {
            val block = startBlock + done
            val lineIndex = block / blocksPerLine
            val offsetInLine = (block - lineIndex * blocksPerLine).toInt()
            val take = minOf(blocksPerLine - offsetInLine, written - done)
            lines[lineIndex]?.let { line ->
                // A final line at the end of the device can be short.
                val copyable = minOf(take, line.size / blockSize - offsetInLine)
                if (copyable > 0) {
                    System.arraycopy(
                        data, done * blockSize,
                        line, offsetInLine * blockSize,
                        copyable * blockSize,
                    )
                }
            }
            done += take
        }
    }

    /** Caller must hold [lock]. */
    private fun invalidateRange(startBlock: Long, blocks: Int) {
        if (blocks <= 0) return
        val first = startBlock / blocksPerLine
        val last = (startBlock + blocks - 1) / blocksPerLine
        for (i in first..last) lines.remove(i)?.fill(0)
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

    /** Blocks fetched per miss; 1 means no readahead. */
    val lineBlocks: Int get() = blocksPerLine

    fun stats(): String {
        val total = hits + misses
        val rate = if (total > 0) 100.0 * hits / total else 0.0
        return "cache: line=%d blocks, %d hits, %d misses (%.1f%% hit), %d bypassed, %d blocks from device"
            .format(blocksPerLine, hits, misses, rate, bypasses, delegateBlocksRead)
    }

    fun resetStats() {
        hits = 0; misses = 0; delegateBlocksRead = 0; bypasses = 0
    }

    /** Drops and zeroes every cached line. */
    fun invalidate() {
        synchronized(lock) {
            // Any fetch in flight must not publish afterwards: its data predates this
            // call, and a caller invalidating to force a device read (the write
            // verification's "cache dropped" pass does exactly that) would otherwise
            // see a line reappear from before the drop.
            dirtiedWhileFetching.addAll(fetching)
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
         *
         * Right for exFAT, whose metadata sits in a small region read over and
         * over — 90x on a cold directory listing. Wrong for FAT32 at small
         * cluster sizes: see [NO_READAHEAD_BYTES].
         */
        const val DEFAULT_READAHEAD_BYTES = 64 * 1024

        /**
         * 4 KiB: the right line size for FAT32.
         *
         * Measured by sweeping readahead on a 57 GiB FAT32 volume with 4 KiB
         * clusters, against no cache at all in the same session
         * (docs/IO_PERFORMANCE.md §5.6):
         *
         *              off     4K      8K     16K     32K     64K
         *   list      1363   1185    1471    1512    1820    2355  ms
         *   listLFN   1672   1613    2688    3015    3755    5570  ms
         *   random    28.9   10.0     7.9     7.2     8.7     9.8  ms
         *   seq       2.91   2.94    4.41    6.35    8.50   10.31  MB/s
         *
         * Cold listing degrades monotonically as the line grows, because walking a
         * large FAT sparsely re-fetches most of each line for nothing. Sequential
         * read improves monotonically for the opposite reason. 4 KiB is the only
         * setting that beats no-cache on every metric and loses on none.
         */
        const val FAT_READAHEAD_BYTES = 4 * 1024

        /**
         * One block per line: cache without readahead.
         *
         * Readahead is a bet that neighbouring blocks will be wanted soon. FAT32
         * with 4 KiB clusters has a ~60 MB FAT walked sparsely, so the bet loses:
         * each miss fetched 64 KiB to satisfy ~512 bytes and the line was rarely
         * reused, making a cold 10,000-entry listing 2-4x *slower* than no cache
         * at all. Deduplicating repeat reads still helps, so the cache stays —
         * just without the prefetch.
         */
        const val NO_READAHEAD_BYTES = 512
    }
}

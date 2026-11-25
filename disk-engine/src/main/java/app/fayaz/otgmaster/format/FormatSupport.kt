package app.fayaz.otgmaster.format

import app.fayaz.otgmaster.block.RawBlockDevice
import java.security.SecureRandom
import java.util.Calendar

/**
 * Writes zeros over a range of sectors in 1 MiB requests, reporting progress 0..1. Sectors
 * in [skip] (inclusive ranges, as [RawBlockDevice.reservedBlocks]) are left alone: inside
 * BitLocker they are its metadata, which the device refuses to write.
 */
internal object ZeroWriter {
    private const val CHUNK_BYTES = 1 shl 20

    fun zero(device: RawBlockDevice, start: Long, end: Long, skip: List<LongRange> = emptyList(),
             progress: (Float) -> Unit = {}) {
        if (end <= start) { progress(1f); return }
        val perChunk = (CHUNK_BYTES / device.blockSize).toLong()
        val buf = ByteArray((perChunk * device.blockSize).toInt())
        val holes = skip.filter { it.last >= start && it.first < end }.sortedBy { it.first }
        var s = start
        while (s < end) {
            val hole = holes.firstOrNull { s in it }
            if (hole != null) { s = minOf(end, hole.last + 1); continue }
            val nextHole = holes.firstOrNull { it.first > s }?.first ?: end
            val n = minOf(perChunk, end - s, nextHole - s)
            device.writeBlocks(s, if (n == perChunk) buf else ByteArray((n * device.blockSize).toInt()))
            s += n
            progress((s - start).toFloat() / (end - start))
        }
        progress(1f)
    }
}

/** Where a formatter has to keep off: [RawBlockDevice.reservedBlocks] inside [0, totalSectors). */
internal object Reserved {
    fun within(reserved: List<LongRange>, totalSectors: Long): List<LongRange> =
        reserved.filter { it.first < totalSectors && it.last >= 0 }
            .map { maxOf(0L, it.first)..minOf(totalSectors - 1, it.last) }
            .sortedBy { it.first }

    /** Throws if any of [ranges] reaches below [end]: the filesystem's own structures are there. */
    fun requireNoneBefore(ranges: List<LongRange>, end: Long, fs: String) {
        ranges.firstOrNull { it.first < end }?.let {
            throw IllegalArgumentException("BitLocker keeps its own data at sector ${it.first}, where $fs " +
                "puts its own structures; this volume cannot be formatted as $fs here")
        }
    }

    /** Cluster numbers covering [ranges], for clusters of [spc] sectors from [heapStart] numbered from 2. */
    fun clusters(ranges: List<LongRange>, heapStart: Long, spc: Int, clusterCount: Long): List<Long> {
        val out = sortedSetOf<Long>()
        for (r in ranges) {
            if (r.last < heapStart) continue
            val first = (maxOf(r.first, heapStart) - heapStart) / spc
            val last = minOf((r.last - heapStart) / spc, clusterCount - 1)
            for (c in first..last) out += c + 2
        }
        return out.toList()
    }
}

/** DOS date and time, as FAT directory entries and exFAT timestamps encode them. */
internal object FatTime {
    /** (date, time) for now, in local time. */
    fun now(): Pair<Short, Short> {
        val c = Calendar.getInstance()
        val date = ((c.get(Calendar.YEAR) - 1980) shl 9) or
            ((c.get(Calendar.MONTH) + 1) shl 5) or c.get(Calendar.DAY_OF_MONTH)
        val time = (c.get(Calendar.HOUR_OF_DAY) shl 11) or
            (c.get(Calendar.MINUTE) shl 5) or (c.get(Calendar.SECOND) / 2)
        return date.toShort() to time.toShort()
    }
}

internal object FormatIds {
    private val random = SecureRandom()

    /** A volume serial number, random as current formatters make it. */
    fun volumeSerial(): Int = random.nextInt()
}

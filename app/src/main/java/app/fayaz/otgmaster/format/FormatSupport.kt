package app.fayaz.otgmaster.format

import app.fayaz.otgmaster.block.RawBlockDevice
import java.security.SecureRandom
import java.util.Calendar

/** Writes zeros over a range of sectors in 1 MiB requests, reporting progress 0..1. */
internal object ZeroWriter {
    private const val CHUNK_BYTES = 1 shl 20

    fun zero(device: RawBlockDevice, start: Long, end: Long, progress: (Float) -> Unit = {}) {
        if (end <= start) { progress(1f); return }
        val perChunk = (CHUNK_BYTES / device.blockSize).toLong()
        val buf = ByteArray((perChunk * device.blockSize).toInt())
        var s = start
        while (s < end) {
            val n = minOf(perChunk, end - s)
            device.writeBlocks(s, if (n == perChunk) buf else ByteArray((n * device.blockSize).toInt()))
            s += n
            progress((s - start).toFloat() / (end - start))
        }
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

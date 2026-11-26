package app.fayaz.containermaster

import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.block.requireInRange
import java.io.IOException

/**
 * A macOS sparse bundle (a ".sparsebundle" folder: what Time Machine writes to a network disk,
 * and what Disk Utility makes as a growable image) as a read-only [RawBlockDevice] of 512-byte
 * sectors.
 *
 * The folder holds Info.plist, which states the disk's size and its band size, and bands/,
 * one file per band named by its index in lowercase hex. A band that is missing, or shorter
 * than the band size, reads as zeros past its end: that is how the format stays sparse.
 * [files] reads a range of a file in the folder, or returns null when there is no such file.
 */
class SparseBundleDevice(
    private val files: Files,
    sizeBytes: Long,
    private val bandSize: Long,
) : RawBlockDevice {

    interface Files {
        /** [length] bytes of "bands/<name>" from [offset], fewer at its end; null if the band does not exist. */
        fun readBand(name: String, offset: Long, length: Int): ByteArray?
    }

    override val blockSize: Int get() = SECTOR
    override val blockCount: Long = sizeBytes / SECTOR

    init {
        require(bandSize >= SECTOR && bandSize % SECTOR == 0L) { "a band size of $bandSize bytes" }
    }

    override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
        requireInRange(startBlock, blockCount.toLong(), "read")
        val out = ByteArray(blockCount * SECTOR)
        var pos = startBlock * SECTOR
        val end = pos + out.size
        while (pos < end) {
            val band = pos / bandSize
            val within = pos % bandSize
            val n = minOf(end - pos, bandSize - within).toInt()
            val got = files.readBand(band.toString(16), within, n)
            if (got != null) System.arraycopy(got, 0, out, (pos - startBlock * SECTOR).toInt(), minOf(got.size, n))
            pos += n
        }
        return out
    }

    override fun writeBlocks(startBlock: Long, data: ByteArray) { throw IOException("Sparse bundles open read-only") }
    override fun close() {}

    companion object {
        const val SECTOR = 512

        /**
         * The disk size and band size from a sparse bundle's Info.plist: the integers after the
         * "size" and "band-size" keys. Throws if either is missing or not plausible.
         */
        fun parseInfo(plist: String): Pair<Long, Long> {
            fun int(key: String): Long? = Regex("<key>\\s*${Regex.escape(key)}\\s*</key>\\s*<integer>\\s*(\\d+)\\s*</integer>")
                .find(plist)?.groupValues?.get(1)?.toLongOrNull()
            val size = int("size") ?: throw IOException("Info.plist states no size: not a sparse bundle")
            val band = int("band-size") ?: throw IOException("Info.plist states no band size: not a sparse bundle")
            if (size <= 0 || band < SECTOR || band > (1L shl 34)) throw IOException("Info.plist states a size of $size and bands of $band bytes")
            if (!plist.contains("com.apple.diskimage.sparsebundle")) throw IOException("Info.plist is not a sparse bundle's")
            return size to band
        }
    }
}

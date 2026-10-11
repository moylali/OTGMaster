package app.fayaz.otgmaster.format

import app.fayaz.otgmaster.block.RawBlockDevice
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes an empty exFAT filesystem, per Microsoft's published exFAT specification:
 * the main and backup boot regions (boot sector, eight extended boot sectors, OEM
 * parameters, a reserved sector and the boot checksum), one FAT, and a cluster heap
 * holding the allocation bitmap, the up-case table and the root directory, in that order
 * from cluster 2. The root directory holds the volume label, bitmap and up-case entries.
 *
 * A quick format: nothing past the root directory's cluster is written. The result must
 * pass `fsck.exfat -n`; ExFatFormatterTest checks exactly that.
 *
 * Written from the specification rather than ported from mkexfat, so nothing is vendored.
 */
object ExFatFormatter {

    private const val BOOT_REGION_SECTORS = 12
    private const val FIRST_CLUSTER = 2L

    data class Layout(
        val bytesPerSector: Int,
        val totalSectors: Long,
        val sectorsPerCluster: Int,
        val fatOffset: Long,
        val fatLength: Long,
        val clusterHeapOffset: Long,
        val clusterCount: Long,
    ) {
        val clusterBytes: Int get() = sectorsPerCluster * bytesPerSector
        fun clusterSector(cluster: Long): Long = clusterHeapOffset + (cluster - FIRST_CLUSTER) * sectorsPerCluster
    }

    /** Microsoft's defaults: 4 KiB clusters up to 256 MiB, 32 KiB up to 32 GiB, 128 KiB above. */
    fun defaultClusterBytes(totalBytes: Long): Int = when {
        totalBytes <= 256L shl 20 -> 4096
        totalBytes <= 32L shl 30 -> 32768
        else -> 131072
    }

    fun layout(
        bytesPerSector: Int,
        totalSectors: Long,
        clusterBytes: Int = defaultClusterBytes(totalSectors * bytesPerSector),
    ): Layout {
        require(bytesPerSector in setOf(512, 1024, 2048, 4096)) { "unsupported sector size $bytesPerSector" }
        require(clusterBytes >= bytesPerSector && Integer.bitCount(clusterBytes) == 1 && clusterBytes <= (32 shl 20)) {
            "unsupported cluster size $clusterBytes"
        }
        require(totalSectors * bytesPerSector >= 1L shl 20) { "exFAT needs a volume of at least 1 MiB" }
        val spc = clusterBytes / bytesPerSector
        // Both boot regions, then the FAT on a cluster boundary, then the heap on one too.
        val fatOffset = alignUp(2L * BOOT_REGION_SECTORS, spc.toLong())
        // The smallest cluster-aligned FAT that covers the clusters it leaves (see
        // Fat32Formatter.layout for why iterating fat = f(clusters) alone is not enough).
        fun clustersFor(fat: Long): Long =
            minOf((totalSectors - alignUp(fatOffset + fat, spc.toLong())) / spc, 0xFFFF_FFF5L)
        fun covers(fat: Long): Boolean = fat * bytesPerSector / 4 >= clustersFor(fat) + 2
        var fatLength = alignUp(((totalSectors / spc + 2) * 4 + bytesPerSector - 1) / bytesPerSector, spc.toLong())
        var step = alignUp(fatLength / 2, spc.toLong())
        while (step >= spc) {
            if (fatLength - step > 0 && covers(fatLength - step)) fatLength -= step
            else step = if (step == spc.toLong()) 0 else alignUp(step / 2, spc.toLong())
        }
        val heap = alignUp(fatOffset + fatLength, spc.toLong())
        val clusters = clustersFor(fatLength)
        require(clusters > 3) { "too small for exFAT with $clusterBytes-byte clusters" }
        check(fatLength * bytesPerSector / 4 >= clusters + 2)
        return Layout(bytesPerSector, totalSectors, spc, fatOffset, fatLength, heap, clusters)
    }

    /**
     * Formats the first [totalSectors] sectors of [device]. [partitionOffset] is the
     * volume's first sector on its disk (0 inside an encrypted container); [label] is up
     * to 11 UTF-16 characters.
     */
    fun format(
        device: RawBlockDevice,
        totalSectors: Long = device.blockCount,
        label: String = "",
        clusterBytes: Int? = null,
        partitionOffset: Long = 0,
        volumeSerial: Int = FormatIds.volumeSerial(),
        progress: (Float) -> Unit = {},
    ): Layout {
        require(totalSectors <= device.blockCount) {
            "volume of $totalSectors sectors on a device of ${device.blockCount}"
        }
        val bps = device.blockSize
        val l = if (clusterBytes != null) layout(bps, totalSectors, clusterBytes) else layout(bps, totalSectors)

        // The system structures, in cluster order from 2.
        val bitmapBytes = (l.clusterCount + 7) / 8
        val bitmapClusters = clustersFor(bitmapBytes, l)
        val upcase = UpcaseTable.compressed
        val upcaseClusters = clustersFor(upcase.size.toLong(), l)
        val bitmapCluster = FIRST_CLUSTER
        val upcaseCluster = bitmapCluster + bitmapClusters
        val rootCluster = upcaseCluster + upcaseClusters
        val usedClusters = bitmapClusters + upcaseClusters + 1

        // Boot regions and the FAT start as zeros; the FAT is the bulk of the writing.
        ZeroWriter.zero(device, 0, l.clusterHeapOffset) { progress(0.9f * it) }

        // FAT: the two reserved entries, then a chain for each structure.
        val fatBytes = ByteBuffer.allocate(((usedClusters + 2) * 4).toInt()).order(ByteOrder.LITTLE_ENDIAN)
        fatBytes.putInt(0xFFFF_FFF8.toInt()).putInt(0xFFFF_FFFF.toInt())
        for ((first, count) in listOf(bitmapCluster to bitmapClusters, upcaseCluster to upcaseClusters, rootCluster to 1L)) {
            for (c in first until first + count) {
                fatBytes.putInt(if (c == first + count - 1) 0xFFFF_FFFF.toInt() else (c + 1).toInt())
            }
        }
        device.writeBlocks(l.fatOffset, padToSectors(fatBytes.array(), bps))

        // Allocation bitmap: the used clusters' bits set, the rest of its clusters zero.
        val bitmap = ByteArray((bitmapClusters * l.clusterBytes).toInt())
        for (i in 0 until usedClusters) bitmap[(i / 8).toInt()] = (bitmap[(i / 8).toInt()].toInt() or (1 shl (i % 8).toInt())).toByte()
        device.writeBlocks(l.clusterSector(bitmapCluster), bitmap)

        val upcaseArea = ByteArray((upcaseClusters * l.clusterBytes).toInt())
        upcase.copyInto(upcaseArea)
        device.writeBlocks(l.clusterSector(upcaseCluster), upcaseArea)

        val root = ByteArray(l.clusterBytes)
        val entries = ByteBuffer.wrap(root).order(ByteOrder.LITTLE_ENDIAN)
        val labelChars = label.trim().take(11)
        entries.put(0, 0x83.toByte())                                   // volume label
        entries.put(1, labelChars.length.toByte())
        labelChars.forEachIndexed { i, ch -> entries.putShort(2 + 2 * i, ch.code.toShort()) }
        entries.put(32, 0x81.toByte())                                  // allocation bitmap
        entries.putInt(32 + 20, bitmapCluster.toInt())
        entries.putLong(32 + 24, bitmapBytes)
        entries.put(64, 0x82.toByte())                                  // up-case table
        entries.putInt(64 + 4, checksum32(upcase))
        entries.putInt(64 + 20, upcaseCluster.toInt())
        entries.putLong(64 + 24, upcase.size.toLong())
        device.writeBlocks(l.clusterSector(rootCluster), root)

        // Boot regions last: until they exist, nothing reads the half-written volume as exFAT.
        val region = bootRegion(l, rootCluster, partitionOffset, volumeSerial,
            percentInUse = (usedClusters * 100 / l.clusterCount).toInt())
        device.writeBlocks(BOOT_REGION_SECTORS.toLong(), region)
        device.writeBlocks(0, region)
        progress(1f)
        return l
    }

    private fun bootRegion(l: Layout, rootCluster: Long, partitionOffset: Long, serial: Int, percentInUse: Int): ByteArray {
        val bps = l.bytesPerSector
        val region = ByteArray(BOOT_REGION_SECTORS * bps)
        val b = ByteBuffer.wrap(region, 0, bps).order(ByteOrder.LITTLE_ENDIAN)
        region[0] = 0xEB.toByte(); region[1] = 0x76; region[2] = 0x90.toByte()
        "EXFAT   ".toByteArray(Charsets.US_ASCII).copyInto(region, 3)
        b.putLong(64, partitionOffset)
        b.putLong(72, l.totalSectors)
        b.putInt(80, l.fatOffset.toInt())
        b.putInt(84, l.fatLength.toInt())
        b.putInt(88, l.clusterHeapOffset.toInt())
        b.putInt(92, l.clusterCount.toInt())
        b.putInt(96, rootCluster.toInt())
        b.putInt(100, serial)
        b.putShort(104, 0x0100)                                         // revision 1.00
        b.putShort(106, 0)                                              // VolumeFlags: clean
        region[108] = Integer.numberOfTrailingZeros(bps).toByte()
        region[109] = Integer.numberOfTrailingZeros(l.sectorsPerCluster).toByte()
        region[110] = 1                                                 // one FAT
        region[111] = 0x80.toByte()                                     // drive select
        region[112] = percentInUse.toByte()
        for (i in 120 until 510) region[i] = 0xF4.toByte()              // boot code: hlt
        region[510] = 0x55; region[511] = 0xAA.toByte()
        // Extended boot sectors 1-8 end with their signature; 9 (OEM parameters) and
        // 10 (reserved) stay zero.
        for (s in 1..8) {
            region[s * bps + bps - 2] = 0x55; region[s * bps + bps - 1] = 0xAA.toByte()
        }
        // Sector 11: the checksum of sectors 0-10, skipping VolumeFlags and PercentInUse,
        // repeated to fill the sector.
        var sum = 0
        for (i in 0 until 11 * bps) {
            if (i == 106 || i == 107 || i == 112) continue
            sum = rotr(sum) + (region[i].toInt() and 0xFF)
        }
        val tail = ByteBuffer.wrap(region, 11 * bps, bps).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until bps / 4) tail.putInt(11 * bps + i * 4, sum)
        return region
    }

    private fun rotr(v: Int): Int = (if (v and 1 != 0) 0x8000_0000.toInt() else 0) + (v ushr 1)

    /** The exFAT checksum the up-case table entry carries. */
    internal fun checksum32(data: ByteArray): Int {
        var sum = 0
        for (b in data) sum = rotr(sum) + (b.toInt() and 0xFF)
        return sum
    }

    private fun clustersFor(bytes: Long, l: Layout): Long = maxOf(1, (bytes + l.clusterBytes - 1) / l.clusterBytes)

    private fun alignUp(v: Long, a: Long): Long = (v + a - 1) / a * a

    private fun padToSectors(data: ByteArray, bps: Int): ByteArray =
        data.copyOf((data.size + bps - 1) / bps * bps)

    /**
     * The up-case table, compressed as the specification allows: a run of characters that
     * map to themselves is stored as 0xFFFF and the run's length. The mapping is Java's
     * single-character upper case, which, like Windows', leaves characters whose upper
     * case is longer than one character as they are.
     */
    internal object UpcaseTable {
        val compressed: ByteArray by lazy {
            val out = ByteArrayOutputStream()
            fun put(v: Int) { out.write(v and 0xFF); out.write(v ushr 8 and 0xFF) }
            var c = 0
            while (c <= 0xFFFF) {
                var run = 0
                while (c + run <= 0xFFFF && upper(c + run) == c + run) run++
                if (run >= 3 || (run > 0 && c + run > 0xFFFF)) {
                    // 0xFFFF marks a run; the character 0xFFFF itself is only ever in one.
                    put(0xFFFF); put(run); c += run
                } else if (run > 0) {
                    repeat(run) { put(c); c++ }
                } else {
                    put(upper(c)); c++
                }
            }
            out.toByteArray()
        }

        private fun upper(c: Int): Int {
            if (c in 0xD800..0xDFFF) return c
            val u = Character.toUpperCase(c.toChar()).code
            return u
        }
    }
}

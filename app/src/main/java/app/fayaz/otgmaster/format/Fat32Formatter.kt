package app.fayaz.otgmaster.format

import app.fayaz.otgmaster.block.RawBlockDevice
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes an empty FAT32 filesystem: boot sector and its backup, FSInfo and its backup,
 * two FATs, and a root directory holding only the volume label. A quick format — the data
 * area is not touched beyond the root directory's cluster.
 *
 * Laid out per Microsoft's FAT specification (fatgen103): 32 reserved sectors (more, to
 * align the data area to a cluster), FSInfo in sector 1, the backup boot sector in sector 6.
 * The result must pass `fsck.vfat -n`; Fat32FormatterTest checks exactly that.
 */
object Fat32Formatter {

    /** Clusters a FAT32 volume must have at least, or readers take it for FAT16. */
    const val MIN_CLUSTERS = 65_525L
    private const val MAX_CLUSTERS = 0x0FFF_FFF4L
    private const val RESERVED_SECTORS = 32
    private const val FAT_COUNT = 2

    data class Layout(
        val bytesPerSector: Int,
        val totalSectors: Long,
        val sectorsPerCluster: Int,
        val reservedSectors: Int,
        val fatSectors: Long,
        val clusterCount: Long,
    ) {
        val dataStartSector: Long get() = reservedSectors + FAT_COUNT * fatSectors
    }

    /**
     * Microsoft's default cluster size for a FAT32 volume of [totalBytes], as Windows
     * formats it: 512 B up to 64 MiB, doubling to 4 KiB by 256 MiB, 4 KiB to 8 GiB,
     * 8 KiB to 16 GiB, 16 KiB to 32 GiB, and 32 KiB above.
     */
    fun defaultClusterBytes(totalBytes: Long): Int = when {
        totalBytes <= 64L shl 20 -> 512
        totalBytes <= 128L shl 20 -> 1024
        totalBytes <= 256L shl 20 -> 2048
        totalBytes <= 8L shl 30 -> 4096
        totalBytes <= 16L shl 30 -> 8192
        totalBytes <= 32L shl 30 -> 16384
        else -> 32768
    }

    /** The layout for [totalSectors], or an IllegalArgumentException naming why FAT32 cannot hold it. */
    fun layout(
        bytesPerSector: Int,
        totalSectors: Long,
        clusterBytes: Int = defaultClusterBytes(totalSectors * bytesPerSector),
    ): Layout {
        require(bytesPerSector in setOf(512, 1024, 2048, 4096)) { "unsupported sector size $bytesPerSector" }
        require(totalSectors <= 0xFFFF_FFFFL) { "FAT32 cannot address more than 2^32 sectors" }
        val spc = maxOf(1, clusterBytes / bytesPerSector)
        require(spc in setOf(1, 2, 4, 8, 16, 32, 64, 128)) { "unsupported cluster size $clusterBytes" }

        // The FAT has to describe the clusters left after the FATs themselves. Start from a
        // FAT big enough for every sector, then shrink it while the smaller one still
        // covers what it leaves; the reserved area grows so the data area starts on a
        // cluster boundary, which keeps every cluster aligned on the flash underneath.
        // (Iterating fat = f(clusters) alone can stop one step short: a FAT sized for the
        // clusters a bigger FAT left is then too small for the clusters it leaves itself.)
        fun reservedFor(fat: Long): Int {
            val dataStart = RESERVED_SECTORS + FAT_COUNT * fat
            return RESERVED_SECTORS + ((spc - dataStart % spc) % spc).toInt()
        }
        fun clustersFor(fat: Long): Long = (totalSectors - reservedFor(fat) - FAT_COUNT * fat) / spc
        fun covers(fat: Long): Boolean = fat * bytesPerSector / 4 >= clustersFor(fat) + 2
        var fatSectors = ((totalSectors / spc + 2) * 4 + bytesPerSector - 1) / bytesPerSector
        var step = fatSectors / 2
        while (step > 0) {
            if (fatSectors - step > 0 && covers(fatSectors - step)) fatSectors -= step else step /= 2
        }
        val reserved = reservedFor(fatSectors)
        val clusters = clustersFor(fatSectors)
        require(clusters >= MIN_CLUSTERS) {
            "Too small for FAT32 with ${spc * bytesPerSector}-byte clusters: $clusters clusters, " +
                "FAT32 needs at least $MIN_CLUSTERS"
        }
        require(clusters <= MAX_CLUSTERS) { "too many clusters for FAT32: $clusters" }
        check(fatSectors * bytesPerSector / 4 >= clusters + 2) { "FAT too small for $clusters clusters" }
        check((reserved + FAT_COUNT * fatSectors) % spc == 0L) { "data area not cluster aligned" }
        return Layout(bytesPerSector, totalSectors, spc, reserved, fatSectors, clusters)
    }

    /**
     * Formats the first [totalSectors] sectors of [device].
     *
     * [hiddenSectors] is where the volume starts on its disk (the partition's first LBA),
     * which only boot code reads; 0 inside an encrypted container. [label] is up to 11
     * characters, upper-cased as FAT stores it. [clusterBytes] null means Microsoft's
     * default for the size.
     */
    fun format(
        device: RawBlockDevice,
        totalSectors: Long = device.blockCount,
        label: String = "",
        clusterBytes: Int? = null,
        hiddenSectors: Long = 0,
        volumeId: Int = FormatIds.volumeSerial(),
        progress: (Float) -> Unit = {},
    ): Layout {
        require(totalSectors <= device.blockCount) {
            "volume of $totalSectors sectors on a device of ${device.blockCount}"
        }
        val bps = device.blockSize
        val l = if (clusterBytes != null) layout(bps, totalSectors, clusterBytes) else layout(bps, totalSectors)
        val labelBytes = fatLabel(label)

        // The reserved area and both FATs start as zeros; the FATs are the bulk of the writing.
        ZeroWriter.zero(device, 0, l.dataStartSector) { progress(0.95f * it) }

        val boot = bootSector(l, labelBytes, hiddenSectors, volumeId)
        val fsInfo = fsInfoSector(l)
        val third = ByteArray(bps).also { it[510] = 0x55; it[511] = 0xAA.toByte() }
        device.writeBlocks(0, boot + fsInfo + third)
        device.writeBlocks(6, boot + fsInfo + third)

        // FAT entries 0 and 1 are reserved (media byte, clean-shutdown bits); cluster 2 is
        // the root directory, a chain of one.
        val fatHead = ByteBuffer.allocate(bps).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x0FFF_FFF8).putInt(0x0FFF_FFFF).putInt(0x0FFF_FFFF).array()
        for (i in 0 until FAT_COUNT) device.writeBlocks(l.reservedSectors + i * l.fatSectors, fatHead)

        val root = ByteArray(l.sectorsPerCluster * bps)
        if (labelBytes != null) volumeLabelEntry(labelBytes).copyInto(root, 0)
        device.writeBlocks(l.dataStartSector, root)
        progress(1f)
        return l
    }

    /** The label as FAT stores it — 11 bytes, upper case, space padded — or null for none. */
    internal fun fatLabel(label: String): ByteArray? {
        val clean = label.trim().uppercase()
            .filter { it.code in 0x20..0x7E && it !in "\"*+,./:;<=>?[\\]|" }
        if (clean.isEmpty()) return null
        return clean.take(11).padEnd(11, ' ').toByteArray(Charsets.US_ASCII)
    }

    private fun bootSector(l: Layout, label: ByteArray?, hidden: Long, volumeId: Int): ByteArray {
        val b = ByteBuffer.allocate(l.bytesPerSector).order(ByteOrder.LITTLE_ENDIAN)
        b.put(byteArrayOf(0xEB.toByte(), 0x58, 0x90.toByte()))      // jump over the BPB
        b.put("MSWIN4.1".toByteArray(Charsets.US_ASCII))           // the OEM name readers expect
        b.putShort(l.bytesPerSector.toShort())
        b.put(l.sectorsPerCluster.toByte())
        b.putShort(l.reservedSectors.toShort())
        b.put(FAT_COUNT.toByte())
        b.putShort(0)                                              // root entries: 0 on FAT32
        b.putShort(0)                                              // 16-bit total: 0 on FAT32
        b.put(0xF8.toByte())                                       // media: fixed disk
        b.putShort(0)                                              // 16-bit FAT size: 0 on FAT32
        b.putShort(63); b.putShort(255)                            // CHS geometry, unused
        b.putInt(hidden.coerceAtMost(0xFFFF_FFFFL).toInt())
        b.putInt(l.totalSectors.toInt())
        b.putInt(l.fatSectors.toInt())
        b.putShort(0)                                              // both FATs active, mirrored
        b.putShort(0)                                              // version 0.0
        b.putInt(2)                                                // root directory cluster
        b.putShort(1)                                              // FSInfo sector
        b.putShort(6)                                              // backup boot sector
        b.put(ByteArray(12))
        b.put(0x80.toByte())                                       // drive number
        b.put(0)
        b.put(0x29)                                                // extended boot signature
        b.putInt(volumeId)
        b.put(label ?: "NO NAME    ".toByteArray(Charsets.US_ASCII))
        b.put("FAT32   ".toByteArray(Charsets.US_ASCII))
        val out = b.array()
        out[510] = 0x55; out[511] = 0xAA.toByte()
        return out
    }

    private fun fsInfoSector(l: Layout): ByteArray {
        val b = ByteBuffer.allocate(l.bytesPerSector).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, 0x4161_5252)
        b.putInt(484, 0x6141_7272)
        b.putInt(488, (l.clusterCount - 1).toInt())                // all free but the root's
        b.putInt(492, 3)                                           // next free cluster
        b.putInt(508, 0xAA55_0000.toInt())
        return b.array()
    }

    private fun volumeLabelEntry(label: ByteArray): ByteArray {
        val e = ByteArray(32)
        label.copyInto(e, 0)
        e[11] = 0x08                                               // ATTR_VOLUME_ID
        val (date, time) = FatTime.now()
        ByteBuffer.wrap(e).order(ByteOrder.LITTLE_ENDIAN).putShort(22, time).putShort(24, date)
        return e
    }
}

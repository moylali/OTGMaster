package app.fayaz.otgmaster.format

import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.ntfs.NtfsNative
import java.io.IOException

/**
 * Writes an empty NTFS with ntfs-3g's own `mkntfs`, built into the app as a function
 * (docs/VENDOR_FIXES.md, ntfs-3g N1) — the same code `mkntfs -Q` runs on Linux, writing
 * through the device the volume was mounted from. Laying NTFS out by hand ($MFT, $MFTMirr,
 * $LogFile, $Secure, $UpCase, the root index) is the kind of write that destroys volumes;
 * mkntfs has made them for fifteen years.
 */
object NtfsFormatter {

    /** mkntfs refuses anything smaller; it also keeps the last sector for the backup boot sector. */
    const val MIN_BYTES = 2L shl 20

    /**
     * Throws unless NTFS fits in [bytes]. [reserved] sectors (of [totalSectors]) may lie
     * anywhere but on the boot sector and its backup in the volume's last sector: mkntfs
     * puts both at fixed places, and moves everything else around them.
     */
    fun check(bytes: Long, totalSectors: Long = 0, reserved: List<LongRange> = emptyList()) {
        require(bytes >= MIN_BYTES) { "NTFS needs at least ${MIN_BYTES shr 20} MiB" }
        Reserved.requireNoneBefore(reserved, 1, "NTFS")
        require(reserved.none { totalSectors - 1 in it }) {
            "BitLocker keeps its own data in the volume's last sector, where NTFS keeps its backup " +
                "boot sector; this volume cannot be formatted as NTFS here"
        }
    }

    /**
     * NTFS over the first [totalSectors] sectors of [device]. [partitionOffset] is the
     * partition's first sector (the boot sector records it), or 0 inside a container;
     * [clusterBytes] null takes mkntfs's default for the size. [reserved] sectors are kept
     * out of every structure and listed in `$BadClus` (docs/VENDOR_FIXES.md N3).
     */
    fun format(device: RawBlockDevice, totalSectors: Long, label: String, partitionOffset: Long,
               clusterBytes: Int? = null, reserved: List<LongRange> = emptyList()) {
        val bytes = totalSectors * device.blockSize
        val holes = Reserved.within(reserved, totalSectors)
        check(bytes, totalSectors, holes)
        // Byte ranges, start and length in turn: mkntfs picks the cluster size itself.
        val ranges = holes.flatMap { listOf(it.first * device.blockSize, (it.last - it.first + 1) * device.blockSize) }
        val rc = NtfsNative.format(device, bytes, device.blockSize, partitionOffset, label, clusterBytes ?: 0,
            ranges.toLongArray())
        if (rc != 0) throw IOException("mkntfs could not write NTFS (code $rc); the log has its reason")
    }
}

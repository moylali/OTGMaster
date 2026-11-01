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

    fun check(bytes: Long) {
        require(bytes >= MIN_BYTES) { "NTFS needs at least ${MIN_BYTES shr 20} MiB" }
    }

    /**
     * NTFS over the first [totalSectors] sectors of [device]. [partitionOffset] is the
     * partition's first sector (the boot sector records it), or 0 inside a container;
     * [clusterBytes] null takes mkntfs's default for the size.
     */
    fun format(device: RawBlockDevice, totalSectors: Long, label: String, partitionOffset: Long,
               clusterBytes: Int? = null) {
        val bytes = totalSectors * device.blockSize
        check(bytes)
        val rc = NtfsNative.format(device, bytes, device.blockSize, partitionOffset, label, clusterBytes ?: 0)
        if (rc != 0) throw IOException("mkntfs could not write NTFS (code $rc); the log has its reason")
    }
}

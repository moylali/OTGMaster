package app.fayaz.otgmaster.format

import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.veracrypt.VeraCryptCipher
import app.fayaz.otgmaster.veracrypt.VeraCryptHash
import app.fayaz.otgmaster.veracrypt.VeraCryptVolumeCreator
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The two erasing operations a mounted card offers, independent of Android so host tests
 * drive exactly the code the app runs:
 *
 * - [reformat]: an empty filesystem of the same kind, size, cluster size and label, written
 *   through the device the volume was mounted from — so a volume inside a container stays
 *   in that container, with the same password.
 * - [encrypt]: a new VeraCrypt volume over the whole partition, holding a new exFAT or FAT32.
 *
 * Both erase. "Full" overwrites every sector of the volume first (through the container,
 * so on disk it is ciphertext); "quick" writes only the structures, and the old contents
 * stay physically on the drive until overwritten.
 */
object DriveFormatter {

    enum class Filesystem(val displayName: String) { EXFAT("exFAT"), FAT32("FAT32") }

    /** What the volume being reformatted looks like, read from its boot sector. */
    data class Existing(
        val filesystem: Filesystem,
        val totalSectors: Long,
        val clusterBytes: Int,
        val label: String,
        /** FAT32's hidden sectors or exFAT's PartitionOffset, carried over unchanged. */
        val partitionOffset: Long,
    )

    /** Phases reported to the UI, with progress 0..1 within each. */
    fun interface Progress { fun update(phase: String, fraction: Float) }

    /**
     * Reads the boot sector of the volume on [device], or explains why it cannot be
     * reformatted in place.
     */
    fun readExisting(device: RawBlockDevice, label: String): Existing {
        val boot = device.readBlocks(0, 1)
        val b = ByteBuffer.wrap(boot).order(ByteOrder.LITTLE_ENDIAN)
        val oem = String(boot, 3, 8, Charsets.US_ASCII)
        if (oem == "EXFAT   ") {
            val bps = 1 shl (boot[108].toInt() and 0xFF)
            require(bps == device.blockSize) { "exFAT sector size $bps on a ${device.blockSize}-byte device" }
            val spc = 1 shl (boot[109].toInt() and 0xFF)
            return Existing(Filesystem.EXFAT, b.getLong(72), bps * spc, label, b.getLong(64))
        }
        if (String(boot, 82, 8, Charsets.US_ASCII) == "FAT32   ") {
            val bps = b.getShort(11).toInt() and 0xFFFF
            require(bps == device.blockSize) { "FAT32 sector size $bps on a ${device.blockSize}-byte device" }
            val spc = boot[13].toInt() and 0xFF
            val total16 = b.getShort(19).toInt() and 0xFFFF
            val total = if (total16 != 0) total16.toLong() else b.getInt(32).toLong() and 0xFFFF_FFFFL
            return Existing(Filesystem.FAT32, total, bps * spc, label, b.getInt(28).toLong() and 0xFFFF_FFFFL)
        }
        throw IllegalArgumentException("Only exFAT and FAT32 volumes can be formatted for now.")
    }

    /**
     * Writes an empty filesystem like [existing] over the first [Existing.totalSectors]
     * sectors of [device]. The caller has unmounted the old filesystem and holds the device.
     */
    fun reformat(device: RawBlockDevice, existing: Existing, full: Boolean, progress: Progress = Progress { _, _ -> }) {
        require(existing.totalSectors <= device.blockCount) {
            "the volume claims ${existing.totalSectors} sectors on a device of ${device.blockCount}"
        }
        // The layout is checked before anything is written, so a volume the formatter
        // cannot lay out the same way is refused intact rather than half erased.
        when (existing.filesystem) {
            Filesystem.FAT32 -> Fat32Formatter.layout(device.blockSize, existing.totalSectors, existing.clusterBytes)
            Filesystem.EXFAT -> ExFatFormatter.layout(device.blockSize, existing.totalSectors, existing.clusterBytes)
        }
        if (full) {
            ZeroWriter.zero(device, 0, existing.totalSectors) { progress.update("Overwriting", it) }
        }
        writeFilesystem(device, existing.filesystem, existing.totalSectors, existing.label,
            existing.clusterBytes, existing.partitionOffset, progress)
    }

    /**
     * Turns [partition] (the whole partition, raw) into a VeraCrypt volume holding an
     * empty [filesystem], and returns the decrypted data area to mount. [password] is wiped.
     */
    fun encrypt(
        partition: RawBlockDevice,
        password: CharArray,
        pim: Int?,
        cipher: VeraCryptCipher,
        hash: VeraCryptHash,
        filesystem: Filesystem,
        label: String,
        full: Boolean,
        progress: Progress = Progress { _, _ -> },
    ): RawBlockDevice {
        val dataSectors = VeraCryptVolumeCreator.dataBytes(partition.blockCount) / 512
        // Checked before the headers go on, so a size FAT32 cannot take leaves the drive alone.
        if (filesystem == Filesystem.FAT32) Fat32Formatter.layout(512, dataSectors)
        else ExFatFormatter.layout(512, dataSectors)
        val volume = VeraCryptVolumeCreator.create(partition, password, pim, cipher, hash) {
            progress.update(it, 0f)
        }
        if (full) {
            ZeroWriter.zero(volume, 0, dataSectors) { progress.update("Overwriting", it) }
        }
        writeFilesystem(volume, filesystem, dataSectors, label, null, 0, progress)
        return volume
    }

    private fun writeFilesystem(
        device: RawBlockDevice, filesystem: Filesystem, totalSectors: Long, label: String,
        clusterBytes: Int?, partitionOffset: Long, progress: Progress,
    ) {
        val phase = "Writing ${filesystem.displayName}"
        when (filesystem) {
            Filesystem.FAT32 -> Fat32Formatter.format(device, totalSectors, label, clusterBytes,
                hiddenSectors = partitionOffset) { progress.update(phase, it) }
            Filesystem.EXFAT -> ExFatFormatter.format(device, totalSectors, label, clusterBytes,
                partitionOffset = partitionOffset) { progress.update(phase, it) }
        }
    }
}

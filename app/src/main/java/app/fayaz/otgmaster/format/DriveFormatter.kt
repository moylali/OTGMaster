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
 * - [reformat]: an empty exFAT, FAT32, ext4 or NTFS of the same size and label (and, for
 *   the same kind, cluster size), written through the device the volume was mounted from —
 *   so a volume inside a container stays in that container, with the same password.
 * - [encrypt]: a new VeraCrypt or LUKS2 volume over the whole partition, holding a new
 *   exFAT, FAT32, ext4 or NTFS.
 *
 * Both erase. "Full" overwrites every sector of the volume first (through the container,
 * so on disk it is ciphertext); "quick" writes only the structures, and the old contents
 * stay physically on the drive until overwritten.
 */
object DriveFormatter {

    /** [displayName] is also what FilesystemDetector calls a mounted volume of that kind. */
    enum class Filesystem(val displayName: String) { EXFAT("exFAT"), FAT32("FAT32"), EXT4("ext4"), NTFS("NTFS") }

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
    fun readExisting(device: RawBlockDevice, label: String, partitionStart: Long = 0): Existing {
        val boot = device.readBlocks(0, minOf(device.blockCount, maxOf(1L, 4096L / device.blockSize)).toInt())
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
        if (String(boot, 3, 8, Charsets.US_ASCII) == "NTFS    ") {
            val bps = b.getShort(11).toInt() and 0xFFFF
            require(bps == device.blockSize) { "NTFS sector size $bps on a ${device.blockSize}-byte device" }
            // Sectors per cluster: a power of two, or 2^(256-n) above 128 (64 KiB+ clusters).
            val raw = boot[13].toInt() and 0xFF
            val spc = if (raw <= 128) raw else 1 shl (256 - raw)
            return Existing(Filesystem.NTFS, device.blockCount, bps * spc, label, b.getInt(28).toLong() and 0xFFFF_FFFFL)
        }
        // ext2/3/4 (all become ext4): the volume fills the device it was mounted from.
        if (boot.size >= 1082 && app.fayaz.otgmaster.ext4.Ext4FileSystem.hasExtMagic(boot)) {
            return Existing(Filesystem.EXT4, device.blockCount, Ext4Formatter.BLOCK_SIZE, label, partitionStart)
        }
        throw IllegalArgumentException("Only exFAT, FAT32, ext4 and NTFS volumes can be formatted.")
    }

    /**
     * Writes an empty [target] filesystem over the first [Existing.totalSectors] sectors of
     * [device], with [existing]'s size, label and partition offset. The same filesystem keeps
     * the cluster size too; a different one takes its own default for that size, since
     * exFAT's and FAT32's ranges differ. The caller has unmounted the old filesystem and
     * holds the device.
     */
    fun reformat(
        device: RawBlockDevice,
        existing: Existing,
        full: Boolean,
        progress: Progress = Progress { _, _ -> },
        target: Filesystem = existing.filesystem,
    ) {
        require(existing.totalSectors <= device.blockCount) {
            "the volume claims ${existing.totalSectors} sectors on a device of ${device.blockCount}"
        }
        val cluster = existing.clusterBytes.takeIf { target == existing.filesystem }
        // The layout is checked before anything is written, so a volume the formatter
        // cannot lay out (FAT32 under 33 MiB, say) is refused intact rather than half erased.
        checkLayout(target, device.blockSize, existing.totalSectors, cluster)
        if (full) {
            ZeroWriter.zero(device, 0, existing.totalSectors) { progress.update("Overwriting", it) }
        }
        writeFilesystem(device, target, existing.totalSectors, existing.label,
            cluster, existing.partitionOffset, progress, full)
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
        keyfiles: List<() -> java.io.InputStream?> = emptyList(),
    ): RawBlockDevice {
        val dataSectors = VeraCryptVolumeCreator.dataBytes(partition.blockCount) / 512
        // Checked before the headers go on, so a size FAT32 cannot take leaves the drive alone.
        checkLayout(filesystem, 512, dataSectors, null)
        val volume = VeraCryptVolumeCreator.create(partition, password, pim, cipher, hash, keyfiles = keyfiles) {
            progress.update(it, 0f)
        }
        if (full) {
            ZeroWriter.zero(volume, 0, dataSectors) { progress.update("Overwriting", it) }
        }
        writeFilesystem(volume, filesystem, dataSectors, label, null, 0, progress, full)
        return volume
    }

    /**
     * Turns [partition] (the whole partition, raw, 512-byte sectors) into a LUKS2 volume
     * holding an empty [filesystem], and returns the decrypted data segment. [password] is
     * wiped. Linux opens it with `cryptsetup open` (see [app.fayaz.otgmaster.luks.LuksVolumeCreator]).
     */
    fun encryptLuks2(
        partition: RawBlockDevice,
        password: CharArray,
        filesystem: Filesystem,
        label: String,
        full: Boolean,
        progress: Progress = Progress { _, _ -> },
    ): RawBlockDevice {
        val dataSectors = app.fayaz.otgmaster.luks.LuksVolumeCreator.dataBytes(partition.blockCount * 512) / 512
        checkLayout(filesystem, 512, dataSectors, null)
        // UTF-8, as cryptsetup takes a passphrase typed on Linux and as the unlock form sends it.
        val bytes = String(password).toByteArray(Charsets.UTF_8)
        password.fill('\u0000')
        val volume = try {
            app.fayaz.otgmaster.luks.LuksVolumeCreator.create(partition, bytes, label) { progress.update(it, 0f) }
        } finally {
            bytes.fill(0)
        }
        if (full) {
            ZeroWriter.zero(volume, 0, dataSectors) { progress.update("Overwriting", it) }
        }
        writeFilesystem(volume, filesystem, dataSectors, label, null, 0, progress, full)
        return volume
    }

    /** Throws, writing nothing, if [fs] cannot be laid out on [sectors] sectors of [sectorSize]. */
    internal fun checkLayout(fs: Filesystem, sectorSize: Int, sectors: Long, cluster: Int?) {
        when (fs) {
            Filesystem.FAT32 -> if (cluster != null) Fat32Formatter.layout(sectorSize, sectors, cluster)
                else Fat32Formatter.layout(sectorSize, sectors)
            Filesystem.EXFAT -> if (cluster != null) ExFatFormatter.layout(sectorSize, sectors, cluster)
                else ExFatFormatter.layout(sectorSize, sectors)
            Filesystem.EXT4 -> {
                require(Ext4Formatter.BLOCK_SIZE % sectorSize == 0) { "ext4 needs sectors of 4 KiB or less" }
                Ext4Formatter.layout(sectors * sectorSize)
            }
            Filesystem.NTFS -> NtfsFormatter.check(sectors * sectorSize)
        }
    }

    internal fun writeFilesystem(
        device: RawBlockDevice, filesystem: Filesystem, totalSectors: Long, label: String,
        clusterBytes: Int?, partitionOffset: Long, progress: Progress, full: Boolean,
    ) {
        val phase = "Writing ${filesystem.displayName}"
        when (filesystem) {
            Filesystem.FAT32 -> Fat32Formatter.format(device, totalSectors, label, clusterBytes,
                hiddenSectors = partitionOffset) { progress.update(phase, it) }
            Filesystem.EXFAT -> ExFatFormatter.format(device, totalSectors, label, clusterBytes,
                partitionOffset = partitionOffset) { progress.update(phase, it) }
            // A full format has already zeroed the volume; ext4 then marks every inode table zeroed.
            Filesystem.EXT4 -> Ext4Formatter.format(device, totalSectors, label, full) { progress.update(phase, it) }
            Filesystem.NTFS -> {
                progress.update(phase, 0f)
                NtfsFormatter.format(device, totalSectors, label, partitionOffset, clusterBytes)
                progress.update(phase, 1f)
            }
        }
    }
}

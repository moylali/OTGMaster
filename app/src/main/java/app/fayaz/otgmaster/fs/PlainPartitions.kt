package app.fayaz.otgmaster.fs

import app.fayaz.otgmaster.PlainPartition
import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.veracrypt.ContainerType
import app.fayaz.otgmaster.veracrypt.VolumeCandidate

/** Which partitions of a drive mount on their own, without a password. */
object PlainPartitions {

    /**
     * The candidates the probe classified as [ContainerType.UNENCRYPTED] whose
     * filesystem the app supports.
     *
     * The probe's verdict decides, not a second look at the bytes. A BitLocker To Go
     * volume begins with a genuine FAT32 boot sector (its discovery volume, "MSWIN4.1"
     * with "FAT32   " at byte 82), so the filesystem check alone took it for a plain
     * FAT32 partition: the scan mounted it as FAT32, which failed with "invalid fs info
     * structure", and dropped it from the unlock form, so its password was never asked
     * for (#184). The probe tests for BitLocker before it tests for a filesystem, and
     * every container type other than UNENCRYPTED needs the unlock form. Encrypted APFS
     * is the other case: it carries the same container superblock as plain APFS, and
     * was excluded here by name before this check replaced that.
     */
    fun detect(device: RawBlockDevice, candidates: List<VolumeCandidate>): List<PlainPartition> =
        candidates.mapNotNull { candidate ->
            if (candidate.containerType != ContainerType.UNENCRYPTED) return@mapNotNull null
            val startBlock = candidate.startBlock
            val available = device.blockCount - startBlock
            if (available <= 0) return@mapNotNull null
            val data = try {
                device.readBlocks(startBlock, minOf(4, available).toInt())
            } catch (_: Exception) { return@mapNotNull null }
            val fs = FilesystemDetector.detectFromBytes(data)
            if (fs is DetectedFilesystem.Supported) {
                PlainPartition(
                    label = candidate.label,
                    startBlock = startBlock,
                    blockCount = candidate.blockCount ?: available,
                    filesystemName = fs.displayName,
                )
            } else null
        }
}

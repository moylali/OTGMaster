package app.fayaz.otgmaster.engine

import app.fayaz.otgmaster.MountedDrive
import app.fayaz.otgmaster.veracrypt.ContainerType

/**
 * What the screen shows of a mounted drive: plain values copied from the engine's MountedDrive,
 * with no filesystem or device behind them (architecture proposal, Milestone 2b step 2). The
 * screen reads these and acts by [id]; in step 2's last slice they cross from the engine's
 * process to the screen's.
 *
 * Capacity, free space and the volume label are not here: reading them takes the filesystem's
 * lock, which a long write holds for minutes, so the screen asks for them off its main thread.
 */
data class DriveSnapshot(
    val id: String,
    val name: String,
    val sourceDeviceName: String?,
    val sourceDeviceDisplayName: String?,
    val isPlain: Boolean,
    val partitionLabel: String,
    val filesystemName: String,
    /** The container the partition was unlocked from; null for a plain partition or a backup. */
    val containerType: ContainerType?,
    val readOnlyReason: String?,
    val isHiddenOrOuter: Boolean,
    val volumeTypeLabel: String?,
    val partitionKey: String,
    /** A restic backup mounted from Manage backups, not a partition. */
    val isBackup: Boolean,
    /** Browsed, not used as a drive: a phone or camera over MTP, or a backup. */
    val browseOnly: Boolean,
    /** An ext4 journal that needs replaying before the volume can be written. */
    val needsJournalRecovery: Boolean,
) {
    val isReadOnly: Boolean get() = readOnlyReason != null

    companion object {
        /** The snapshot of [d] now. Cheap: nothing here reads the drive. */
        fun of(d: MountedDrive): DriveSnapshot = DriveSnapshot(
            id = d.id,
            name = d.name,
            sourceDeviceName = d.sourceDeviceName,
            sourceDeviceDisplayName = d.sourceDeviceDisplayName,
            isPlain = d.isPlain,
            partitionLabel = d.partitionLabel,
            filesystemName = d.filesystemName,
            containerType = d.sourceVolumeCandidate?.containerType,
            readOnlyReason = d.readOnlyReason,
            isHiddenOrOuter = d.isHiddenOrOuter,
            volumeTypeLabel = d.volumeTypeLabel,
            partitionKey = d.partitionKey,
            isBackup = d.fileSystem is app.fayaz.otgmaster.backup.restic.ResticFileSystem,
            browseOnly = d.fileSystem is app.fayaz.otgmaster.mtp.MtpStorageFileSystem ||
                d.fileSystem is app.fayaz.otgmaster.backup.restic.ResticFileSystem,
            needsJournalRecovery = (d.fileSystem as? app.fayaz.otgmaster.ext4.Ext4FileSystem)?.needsJournalRecovery == true,
        )
    }
}

/**
 * What the unlock form shows of a drive offered for unlocking: its key and name, the partitions
 * it can unlock, and how many it leaves out, with no USB connection behind it (2b step 2).
 *
 * [connection] is the identity of the connection the engine holds for it. The form keys its
 * selection on the list, and a drive re-probed through a new connection used to compare unequal
 * (UsbDeviceCandidate compares its block device); carrying the identity keeps that.
 */
data class DeviceSnapshot(
    val deviceName: String,
    val displayName: String,
    val candidates: List<app.fayaz.otgmaster.veracrypt.VolumeCandidate>,
    val hiddenPartitions: Int,
    val connection: Int,
) {
    companion object {
        fun of(d: app.fayaz.otgmaster.UsbDeviceCandidate) = DeviceSnapshot(
            d.deviceName, d.displayName, d.candidates, d.hiddenPartitions, System.identityHashCode(d.blockDevice))
    }
}

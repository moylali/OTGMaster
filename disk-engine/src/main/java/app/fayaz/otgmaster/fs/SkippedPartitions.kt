package app.fayaz.otgmaster.fs

import app.fayaz.otgmaster.veracrypt.ContainerType
import app.fayaz.otgmaster.veracrypt.VolumeCandidate

/**
 * One app-log line for each partition a scan found but neither mounted nor offered.
 *
 * The unlock form lists only partitions it can act on, and plain ones with a supported
 * filesystem are mounted straight away. Everything else used to vanish silently: a
 * partition with an unsupported filesystem was mentioned only if someone tried to mount
 * it, and one the probe could not identify never at all. A user looking at a drive with
 * "missing" partitions, and a report sent from it, had nothing to go on.
 */
object SkippedPartitions {

    /**
     * [candidates] are everything the probe found; [mountedPlain] the start blocks of the
     * plain partitions being mounted; [detect] reads a plain partition's filesystem.
     */
    fun lines(
        candidates: List<VolumeCandidate>,
        mountedPlain: Set<Long>,
        blockSize: Int,
        formatSize: (Long) -> String,
        detect: (VolumeCandidate) -> DetectedFilesystem,
    ): List<String> = candidates.mapNotNull { c ->
        if (c.startBlock in mountedPlain) return@mapNotNull null
        val size = c.blockCount?.let { " (${formatSize(it * blockSize)})" } ?: ""
        when (c.containerType) {
            ContainerType.UNENCRYPTED -> when (val fs = detect(c)) {
                is DetectedFilesystem.Unsupported ->
                    "${c.label}$size: not mounted, ${fs.displayName} is not supported (${fs.reason})"
                is DetectedFilesystem.Supported -> null // mounted elsewhere, or about to be
                DetectedFilesystem.Unknown ->
                    "${c.label}$size: not mounted, no filesystem the app recognises"
            }
            ContainerType.UNKNOWN ->
                "${c.label}$size: not offered, it could not be read or identified"
            else -> null // offered in the unlock form
        }
    }
}

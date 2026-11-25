package app.fayaz.otgmaster

/** A partition that mounts without a password: what fs/PlainPartitions.detect returns. */
data class PlainPartition(
    val label: String,
    val startBlock: Long,
    val blockCount: Long,
    val filesystemName: String
)

package app.fayaz.otgmaster.veracrypt

// UNENCRYPTED is a positive identification, not the absence of one: the volume
// starts with a readable filesystem signature, which rules out every container
// format here. UNKNOWN means the probe could not tell.
/** APFS is an encrypted APFS volume; a plain one is UNENCRYPTED with APFS inside. */
enum class ContainerType { VERACRYPT, LUKS1, LUKS2, BITLOCKER, APFS, UNENCRYPTED, UNKNOWN }

data class VolumeCandidate(
    val label: String,
    val startBlock: Long,
    val blockCount: Long?,
    val containerType: ContainerType = ContainerType.UNKNOWN,
    /**
     * Which volume of an APFS container this is; 0 for everything else. An APFS container
     * can hold several volumes in one partition, so a volume after the first shares its
     * partition's [startBlock] and needs this to tell it apart (review of 2026-11-09).
     */
    val apfsVolume: Int = 0,
) {
    /** Identifies the candidate on its device: the start block, plus the volume past the first. */
    val slotKey: String get() = if (apfsVolume == 0) "$startBlock" else "$startBlock#v$apfsVolume"

    /**
     * Whether this is one of the plain partitions starting at [plainStarts]. A later APFS
     * volume never is: the plain mount opens the partition's first volume only.
     */
    fun isIn(plainStarts: Set<Long>): Boolean = apfsVolume == 0 && startBlock in plainStarts
}

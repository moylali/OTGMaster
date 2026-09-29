package app.fayaz.otgmaster.veracrypt

// UNENCRYPTED is a positive identification, not the absence of one: the volume
// starts with a readable filesystem signature, which rules out every container
// format here. UNKNOWN means the probe could not tell.
enum class ContainerType { VERACRYPT, LUKS1, LUKS2, UNENCRYPTED, UNKNOWN }

data class VolumeCandidate(
    val label: String,
    val startBlock: Long,
    val blockCount: Long?,
    val containerType: ContainerType = ContainerType.UNKNOWN
)

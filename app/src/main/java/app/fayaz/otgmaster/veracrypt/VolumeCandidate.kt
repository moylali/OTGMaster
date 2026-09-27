package app.fayaz.otgmaster.veracrypt

enum class ContainerType { VERACRYPT, LUKS1, LUKS2, UNKNOWN }

data class VolumeCandidate(
    val label: String,
    val startBlock: Long,
    val blockCount: Long?,
    val containerType: ContainerType = ContainerType.UNKNOWN
)

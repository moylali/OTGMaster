package app.fayaz.otgmaster.partition

data class PartitionEntry(
    val index: Int,
    val type: Int,
    val firstLba: Long,
    val sectorCount: Long,
) {
    val isEmpty: Boolean = type == 0 || sectorCount == 0L
}

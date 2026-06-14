package app.fayaz.otgmaster.partition

object MbrParser {
    private const val MBR_SIZE = 512
    private const val SIGNATURE_OFFSET = 510
    private const val PARTITION_TABLE_OFFSET = 446
    private const val PARTITION_ENTRY_SIZE = 16
    private const val PARTITION_COUNT = 4

    fun parse(sector0: ByteArray): List<PartitionEntry> {
        require(sector0.size >= MBR_SIZE) { "MBR sector must be at least 512 bytes" }
        if (sector0[SIGNATURE_OFFSET] != 0x55.toByte() || sector0[SIGNATURE_OFFSET + 1] != 0xAA.toByte()) {
            return emptyList()
        }

        return (0 until PARTITION_COUNT).mapNotNull { index ->
            val offset = PARTITION_TABLE_OFFSET + (index * PARTITION_ENTRY_SIZE)
            val type = sector0[offset + 4].toInt() and 0xFF
            val firstLba = sector0.readUInt32Le(offset + 8)
            val sectors = sector0.readUInt32Le(offset + 12)
            PartitionEntry(index = index, type = type, firstLba = firstLba, sectorCount = sectors)
                .takeUnless { it.isEmpty }
        }
    }

    private fun ByteArray.readUInt32Le(offset: Int): Long {
        return (this[offset].toLong() and 0xFF) or
            ((this[offset + 1].toLong() and 0xFF) shl 8) or
            ((this[offset + 2].toLong() and 0xFF) shl 16) or
            ((this[offset + 3].toLong() and 0xFF) shl 24)
    }
}

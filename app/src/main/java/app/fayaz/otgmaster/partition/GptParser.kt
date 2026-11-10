package app.fayaz.otgmaster.partition

import app.fayaz.otgmaster.block.RawBlockDevice
import java.nio.ByteBuffer
import java.nio.ByteOrder

object GptParser {
    fun parse(device: RawBlockDevice): List<PartitionEntry> {
        val sector1 = device.readBlocks(startBlock = 1, blockCount = 1)
        val headerBuf = ByteBuffer.wrap(sector1).order(ByteOrder.LITTLE_ENDIAN)
        
        val signature = ByteArray(8)
        headerBuf.get(signature)
        if (String(signature, Charsets.US_ASCII) != "EFI PART") {
            return emptyList()
        }

        headerBuf.position(72)
        val partEntryLba = headerBuf.long
        val numPartEntries = headerBuf.int
        val partEntrySize = headerBuf.int
        
        // The header comes from whatever drive is plugged in. Its counts sized the read as an
        // Int with no cap: a bad header overflowed it (and the parse threw), or asked for a
        // table bigger than the drive. The spec makes the entry size 128 x 2^n; 128 entries
        // is usual, and MAX_TABLE_BYTES allows 64 times that. Review, 2026-11-09.
        if (numPartEntries <= 0 || partEntrySize < 128 || partEntrySize % 128 != 0 ||
            partEntrySize > MAX_ENTRY_SIZE) return emptyList()
        val totalBytes = numPartEntries.toLong() * partEntrySize
        if (totalBytes > MAX_TABLE_BYTES) return emptyList()
        val blocksToRead = (totalBytes + device.blockSize - 1) / device.blockSize
        if (partEntryLba < 2 || partEntryLba + blocksToRead > device.blockCount) return emptyList()

        val entriesData = device.readBlocks(startBlock = partEntryLba, blockCount = blocksToRead.toInt())
        val entriesBuf = ByteBuffer.wrap(entriesData).order(ByteOrder.LITTLE_ENDIAN)
        
        val partitions = mutableListOf<PartitionEntry>()
        for (i in 0 until numPartEntries) {
            entriesBuf.position(i * partEntrySize)
            
            val typeGuid1 = entriesBuf.long
            val typeGuid2 = entriesBuf.long
            if (typeGuid1 == 0L && typeGuid2 == 0L) continue
            
            entriesBuf.position((i * partEntrySize) + 32)
            val firstLba = entriesBuf.long
            val lastLba = entriesBuf.long
            
            val sectorCount = (lastLba - firstLba) + 1
            // An entry must lie on the drive; one that does not is skipped, not probed.
            if (firstLba >= 1 && sectorCount > 0 && lastLba < device.blockCount) {
                partitions.add(PartitionEntry(
                    index = i,
                    type = 0xEE,
                    firstLba = firstLba,
                    sectorCount = sectorCount
                ))
            }
        }
        return partitions
    }

    /** Largest entry size accepted; the spec allows 128 x 2^n, and nothing uses more than 512. */
    private const val MAX_ENTRY_SIZE = 4096

    /** Largest entry table read: 1 MiB, 64 times the usual 128 entries of 128 bytes. */
    private const val MAX_TABLE_BYTES = 1L shl 20
}

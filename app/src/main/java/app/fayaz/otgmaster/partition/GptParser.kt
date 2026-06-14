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
        
        if (numPartEntries <= 0 || partEntrySize < 128) return emptyList()

        val totalBytes = numPartEntries * partEntrySize
        val blocksToRead = (totalBytes + device.blockSize - 1) / device.blockSize
        
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
            if (sectorCount > 0) {
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
}

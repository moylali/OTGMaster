package com.otgmaster.fs

import com.otgmaster.block.RawBlockDevice
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Fat32Reader(private val device: RawBlockDevice) {

    fun listRootDirectory(): List<String> {
        val bootSector = device.readBlocks(0, 1)
        val buffer = ByteBuffer.wrap(bootSector).order(ByteOrder.LITTLE_ENDIAN)

        val bytesPerSector = buffer.getShort(11).toInt() and 0xFFFF
        val sectorsPerCluster = buffer.get(13).toInt() and 0xFF
        val reservedSectors = buffer.getShort(14).toInt() and 0xFFFF
        val numFats = buffer.get(16).toInt() and 0xFF
        val sectorsPerFat = buffer.getInt(36)
        val rootCluster = buffer.getInt(44)

        if (bytesPerSector == 0 || sectorsPerCluster == 0) {
            return listOf("Error: Invalid FAT32 boot sector parameters")
        }

        val fatStartSector = reservedSectors.toLong()
        val dataStartSector = fatStartSector + (numFats * sectorsPerFat)

        // For root cluster, assuming it's contiguous for simplicity in this prototype
        val rootSectorOffset = dataStartSector + (rootCluster - 2) * sectorsPerCluster
        
        val rootData = device.readBlocks(rootSectorOffset, sectorsPerCluster)
        
        val files = mutableListOf<String>()
        for (i in 0 until rootData.size step 32) {
            val entry = rootData.copyOfRange(i, i + 32)
            if (entry[0] == 0x00.toByte()) break // End of directory
            if (entry[0] == 0xE5.toByte()) continue // Deleted file

            val attr = entry[11].toInt() and 0xFF
            if (attr == 0x0F) continue // Long File Name entry (skip for prototype)
            if (attr and 0x08 != 0) continue // Volume ID

            val name = String(entry.copyOfRange(0, 8), Charsets.US_ASCII).trimEnd()
            val ext = String(entry.copyOfRange(8, 11), Charsets.US_ASCII).trimEnd()

            val fullName = if (ext.isNotEmpty()) "$name.$ext" else name
            val type = if (attr and 0x10 != 0) "[DIR]" else "[FILE]"
            files.add("$type $fullName")
        }

        return files
    }
}

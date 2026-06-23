package app.fayaz.otgmaster.exfat

import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.FileSystemCreator
import me.jahnen.libaums.core.partition.PartitionTableEntry
import java.nio.ByteBuffer

class ExFatFileSystemCreator : FileSystemCreator {
    override fun read(entry: PartitionTableEntry, blockDevice: BlockDeviceDriver): FileSystem? {
        try {
            // First check if it's exfat by reading the boot sector
            val buffer = ByteBuffer.allocate(512)
            blockDevice.read(0, buffer)
            buffer.clear()
            
            // "EXFAT   " starts at offset 3
            val oemName = ByteArray(8)
            buffer.position(3)
            buffer.get(oemName)
            if (String(oemName) != "EXFAT   ") {
                return null
            }
            
            val rawBlockDevice = object : RawBlockDevice {
                override val blockSize: Int = blockDevice.blockSize
                override val blockCount: Long = blockDevice.blocks
                
                override fun readBlocks(startBlock: Long, count: Int): ByteArray {
                    val bytes = ByteArray(count * blockSize)
                    val bb = ByteBuffer.wrap(bytes)
                    blockDevice.read(startBlock * blockSize, bb)
                    return bytes
                }

                override fun writeBlocks(startBlock: Long, data: ByteArray) {
                    val bb = ByteBuffer.wrap(data)
                    blockDevice.write(startBlock * blockSize, bb)
                }

                override fun close() {}
            }
            
            val exfatPtr = ExFatNative.mount(rawBlockDevice)
            android.util.Log.d("OTG_EXFAT", "ExFatNative.mount returned $exfatPtr")
            if (exfatPtr == 0L) return null
            
            return ExFatFileSystem(rawBlockDevice, exfatPtr)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}

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
                
                override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
                    val bytes = ByteArray(blockCount * blockSize)
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
            
            val readOnly = blockDevice is app.fayaz.otgmaster.block.ReadOnlyBlockDeviceDriver
            ExFatNative.takeMessages()  // drop anything left from an earlier volume
            val exfatPtr = ExFatNative.mount(rawBlockDevice, readOnly)
            val mode = if (readOnly) "read-only mount" else "read-write mount"
            ExFatNative.reportMessages(mode)
            if (exfatPtr == 0L) {
                app.fayaz.otgmaster.OtgMasterState.log("exFAT: libexfat refused the $mode (reason above, if it gave one)")
                return null
            }
            
            return ExFatFileSystem(rawBlockDevice, exfatPtr, readOnly)
        } catch (e: Exception) {
            e.printStackTrace()
            // Swallowed here, so the caller only knew "no filesystem accepted it".
            app.fayaz.otgmaster.OtgMasterState.log("exFAT: mount failed: ${e.javaClass.simpleName}: ${e.message}" +
                (e.cause?.let { " (cause: ${it.javaClass.simpleName}: ${it.message})" } ?: ""))
            ExFatNative.reportMessages("mount")
            return null
        }
    }
}

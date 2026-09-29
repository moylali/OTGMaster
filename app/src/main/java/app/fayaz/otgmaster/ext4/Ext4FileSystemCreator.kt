package app.fayaz.otgmaster.ext4

import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.FileSystemCreator
import me.jahnen.libaums.core.partition.PartitionTableEntry
import java.nio.ByteBuffer

/**
 * Registers the ext2/3/4 driver with libaums [FileSystemFactory].
 *
 * Detection: reads the first 2 KiB (covering the superblock at offset 1024)
 * and checks for the ext magic 0xEF53.  Registration priority must be higher
 * than the default FAT32 handler; use slot 2 (ExFAT uses slot 1).
 */
class Ext4FileSystemCreator : FileSystemCreator {

    override fun read(entry: PartitionTableEntry, blockDevice: BlockDeviceDriver): FileSystem? {
        return try {
            // Read 4 sectors = 2 KiB to cover the superblock.
            val sectors = 4
            val buf = ByteBuffer.allocate(sectors * blockDevice.blockSize)
            blockDevice.read(0, buf)
            if (!Ext4FileSystem.hasExtMagic(buf.array())) return null

            val rawDevice = object : RawBlockDevice {
                override val blockSize: Int  = blockDevice.blockSize
                override val blockCount: Long = blockDevice.blocks

                override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
                    val bytes = ByteArray(blockCount * blockSize)
                    val bb    = ByteBuffer.wrap(bytes)
                    blockDevice.read(startBlock * blockSize, bb)
                    return bytes
                }

                override fun writeBlocks(startBlock: Long, data: ByteArray) {
                    val bb = ByteBuffer.wrap(data)
                    blockDevice.write(startBlock * blockSize, bb)
                }

                override fun close() {}
            }

            Ext4FileSystem.create(
                rawDevice,
                readOnly = blockDevice is app.fayaz.otgmaster.block.ReadOnlyBlockDeviceDriver,
            )
        } catch (e: Exception) {
            android.util.Log.e("Ext4", "Failed to mount ext filesystem", e)
            null
        }
    }
}

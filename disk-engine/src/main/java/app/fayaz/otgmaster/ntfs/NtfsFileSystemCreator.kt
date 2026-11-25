package app.fayaz.otgmaster.ntfs

import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.block.ReadOnlyBlockDeviceDriver
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.FileSystemCreator
import me.jahnen.libaums.core.partition.PartitionTableEntry
import java.nio.ByteBuffer

/** Registers the libntfs-3g driver with libaums' FileSystemFactory. */
class NtfsFileSystemCreator : FileSystemCreator {

    override fun read(entry: PartitionTableEntry, blockDevice: BlockDeviceDriver): FileSystem? {
        val boot = ByteBuffer.allocate(blockDevice.blockSize.coerceAtLeast(512))
        blockDevice.read(0, boot)
        if (!NtfsFileSystem.hasNtfsSignature(boot.array())) return null

        val raw = object : RawBlockDevice {
            override val blockSize: Int = blockDevice.blockSize
            override val blockCount: Long = blockDevice.blocks

            override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
                val bytes = ByteArray(blockCount * blockSize)
                blockDevice.read(startBlock * blockSize, ByteBuffer.wrap(bytes))
                return bytes
            }

            override fun writeBlocks(startBlock: Long, data: ByteArray) {
                blockDevice.write(startBlock * blockSize, ByteBuffer.wrap(data))
            }

            override fun close() {}
        }
        // Thrown rather than returning null, so the caller's log says why NTFS
        // would not mount instead of falling through to "unrecognised filesystem".
        return NtfsFileSystem.mount(raw, readOnly = blockDevice is ReadOnlyBlockDeviceDriver)
    }
}

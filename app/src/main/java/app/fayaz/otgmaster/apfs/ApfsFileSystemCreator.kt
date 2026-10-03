package app.fayaz.otgmaster.apfs

import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.FileSystemCreator
import me.jahnen.libaums.core.partition.PartitionTableEntry
import java.nio.ByteBuffer

/**
 * Registers the libfsapfs driver with libaums' FileSystemFactory, for plain APFS.
 *
 * An encrypted volume needs its password, which the factory has no way to pass, so
 * the unlock form mounts those through [mount] directly. Through the factory an
 * encrypted volume fails with [ApfsFileSystem.WrongPasswordException], which says so.
 */
class ApfsFileSystemCreator : FileSystemCreator {

    override fun read(entry: PartitionTableEntry, blockDevice: BlockDeviceDriver): FileSystem? {
        val block0 = ByteBuffer.allocate(blockDevice.blockSize.coerceAtLeast(512))
        blockDevice.read(0, block0)
        if (!ApfsFileSystem.hasSignature(block0.array())) return null
        return mount(blockDevice, password = null)
    }

    companion object {
        fun mount(blockDevice: BlockDeviceDriver, password: String?): ApfsFileSystem =
            ApfsFileSystem.mount(rawOf(blockDevice), password)

        private fun rawOf(blockDevice: BlockDeviceDriver) = object : RawBlockDevice {
            override val blockSize: Int = blockDevice.blockSize
            override val blockCount: Long = blockDevice.blocks

            override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
                val bytes = ByteArray(blockCount * blockSize)
                blockDevice.read(startBlock * blockSize, ByteBuffer.wrap(bytes))
                return bytes
            }

            override fun writeBlocks(startBlock: Long, data: ByteArray) =
                throw java.io.IOException("APFS is mounted read-only")

            override fun close() {}
        }
    }
}

package app.fayaz.otgmaster.fs.apfs

import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.fs.FilesystemDetector
import app.fayaz.otgmaster.fs.DetectedFilesystem
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.FileSystemCreator
import me.jahnen.libaums.core.partition.PartitionTableEntry
import java.nio.ByteBuffer

class ApfsFileSystemCreator : FileSystemCreator {

    override fun read(entry: PartitionTableEntry, blockDevice: BlockDeviceDriver): FileSystem? {
        return try {
            val buf = ByteBuffer.allocate(4 * blockDevice.blockSize)
            blockDevice.read(0, buf)
            val detected = FilesystemDetector.detectFromBytes(buf.array())
            if (detected !is DetectedFilesystem.Supported || detected.displayName != "APFS") {
                return null
            }

            val rawDevice = object : RawBlockDevice {
                override val blockSize: Int  = blockDevice.blockSize
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

            // For now, assume password is null
            var contextPtr = ApfsNative.mount(rawDevice, null)
            if (contextPtr == 0L) contextPtr = ApfsNative.mount(rawDevice, "password123")
            if (contextPtr == 0L) return null
            ApfsFileSystem(rawDevice, contextPtr)
        } catch (e: Exception) {
            android.util.Log.e("APFS", "Failed to mount APFS filesystem", e)
            null
        }
    }
}

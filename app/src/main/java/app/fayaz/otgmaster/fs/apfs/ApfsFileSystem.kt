package app.fayaz.otgmaster.fs.apfs

import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.partition.PartitionTypes

class ApfsFileSystem(
    private val blockDevice: RawBlockDevice,
    val contextPtr: Long
) : FileSystem {

    override val rootDirectory: UsbFile = ApfsFile(this, "/")
    override val volumeLabel: String = ApfsNative.getVolumeName(contextPtr)
    
    override val capacity: Long = blockDevice.blockCount * blockDevice.blockSize
    override val occupiedSpace: Long = 0L // Estimate
    override val freeSpace: Long = 0L
    override val chunkSize: Int = blockDevice.blockSize
    override val type: Int = PartitionTypes.UNKNOWN

    fun unmount() {
        ApfsNative.unmount(contextPtr)
    }
}

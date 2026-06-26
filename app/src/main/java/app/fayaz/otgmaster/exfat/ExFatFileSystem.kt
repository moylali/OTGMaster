package app.fayaz.otgmaster.exfat

import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile

class ExFatFileSystem(private val blockDevice: RawBlockDevice, val exfatPtr: Long) : FileSystem {
    @Volatile
    var isUnmounted = false
        private set
    private val _rootDirectory: ExFatFile by lazy {
        val rootNode = ExFatNative.getRootNode(exfatPtr)
        ExFatFile(this, null, rootNode!!)
    }

    override val rootDirectory: UsbFile
        get() = _rootDirectory

    override val volumeLabel: String
        get() = "exFAT" // TODO: Fetch label from libexfat if needed

    override val capacity: Long
        get() = blockDevice.blockCount * blockDevice.blockSize

    override val occupiedSpace: Long
        get() = capacity - freeSpace

    override val freeSpace: Long
        get() = synchronized(this) {
            if (isUnmounted) 0L else ExFatNative.getFreeSpace(exfatPtr)
        }

    override val chunkSize: Int
        get() = blockDevice.blockSize

    override val type: Int
        get() = 0 // exfat partition type, maybe just 0 or custom

    fun unmount() {
        synchronized(this) {
            if (isUnmounted) return
            isUnmounted = true
            ExFatNative.unmount(exfatPtr)
        }
    }
}

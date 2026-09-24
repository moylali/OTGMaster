package app.fayaz.otgmaster.exfat

import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ExFatFileSystem(private val blockDevice: RawBlockDevice, val exfatPtr: Long) : FileSystem {
    /**
     * Guards every libexfat call. Was an intrinsic monitor (`synchronized(this)`
     * / `synchronized(fileSystem)`), but ExFatFile.finalize() has to acquire it
     * too, and a finalizer that blocks is fatal: Android's
     * FinalizerWatchdogDaemon kills the process when a single finalize()
     * exceeds 10 seconds. Listing a 10,000-entry directory reliably did that,
     * because the finalizer thread waited on this lock behind multi-second USB
     * reads. A ReentrantLock lets the finalizer make a bounded attempt and back
     * off instead of hanging.
     */
    val lock = ReentrantLock()
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
        get() = lock.withLock {
            if (isUnmounted) 0L else ExFatNative.getFreeSpace(exfatPtr)
        }

    override val chunkSize: Int
        get() = blockDevice.blockSize

    override val type: Int
        get() = 0 // exfat partition type, maybe just 0 or custom

    fun unmount() {
        lock.withLock {
            if (isUnmounted) return
            isUnmounted = true
            ExFatNative.flush(exfatPtr)
            ExFatNative.unmount(exfatPtr)
        }
    }
}

package app.fayaz.otgmaster.exfat

import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import java.io.IOException
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
        val rootNode = withNative { ExFatNative.getRootNode(exfatPtr) }
        ExFatFile(this, null, rootNode!!)
    }

    /**
     * Runs [body] with the libexfat lock held, refusing to enter native code once
     * unmount() has freed the `struct exfat`.
     *
     * Every native call must go through here. Without the guard, anything still
     * holding an ExFatFile across an unmount — a SAF client with an open
     * ProxyFileDescriptor, a listing on a worker thread, a copy in flight when the
     * stick is pulled — hands libexfat a dangling pointer, and the process dies
     * with SIGSEGV instead of the caller seeing an IOException it can handle:
     *
     *   signal 11 (SIGSEGV), fault addr 0x1a00000060
     *     exfat_utf16_to_utf8 / exfat_get_name / ExFatNative_getRootNode
     *
     * Testing the flag inside the lock is what makes it sound: unmount() sets the
     * flag under this same lock, so it cannot flip between the check and the call.
     */
    fun <T> withNative(body: () -> T): T = lock.withLock {
        if (isUnmounted) throw IOException("exFAT filesystem is unmounted")
        body()
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

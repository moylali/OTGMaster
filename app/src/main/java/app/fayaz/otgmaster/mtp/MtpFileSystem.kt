package app.fayaz.otgmaster.mtp

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.mtp.MtpObjectInfo
import me.jahnen.libaums.core.fs.AbstractUsbFile
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Another phone or a camera connected over USB (MTP or PTP), shown as a mounted drive:
 * one [MtpStorageFileSystem] per storage it reports ("Internal shared storage", an SD
 * card), so it appears in the Files app and in More actions like a USB stick.
 *
 * Read-only for now. MTP has no partial write: a changed file is sent whole, which the
 * Files app's random-access writes cannot express, so writing waits for a copy-in path
 * of its own. Reads use GetPartialObject64, which Android and most cameras implement.
 *
 * All calls to one device go through [lock]: an MTP session handles one operation at a
 * time, and the Files app reads from several threads.
 */
class MtpSession(val usbDevice: UsbDevice, val device: MtpDevice) {
    val lock = Any()
    val name: String = listOfNotNull(device.deviceInfo?.manufacturer, device.deviceInfo?.model).joinToString(" ").ifBlank { "MTP device" }

    fun storages(): List<MtpStorageFileSystem> = synchronized(lock) {
        device.storageIds?.toList().orEmpty().map { MtpStorageFileSystem(this, it) }
    }

    fun close() = synchronized(lock) { device.close() }

    companion object {
        /** A still-image (PTP) or MTP interface: class 6, subclass 1, protocol 1, or a vendor interface named "MTP". */
        fun isMtpDevice(d: UsbDevice): Boolean = (0 until d.interfaceCount).any { i ->
            val itf = d.getInterface(i)
            (itf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE && itf.interfaceSubclass == 1 && itf.interfaceProtocol == 1) ||
                (itf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && itf.name?.contains("MTP", ignoreCase = true) == true)
        }
    }
}

class MtpStorageFileSystem(val session: MtpSession, val storageId: Int) : FileSystem {
    private val info = synchronized(session.lock) { session.device.getStorageInfo(storageId) }

    override val rootDirectory: UsbFile = MtpFile(this, ROOT, null, null)
    override val volumeLabel: String get() = info?.description ?: "Storage"
    override val capacity: Long get() = info?.maxCapacity ?: 0L
    override val freeSpace: Long get() = synchronized(session.lock) { session.device.getStorageInfo(storageId)?.freeSpace } ?: 0L
    override val occupiedSpace: Long get() = capacity - freeSpace
    override val chunkSize: Int get() = 1 shl 20
    override val type: Int get() = -1

    companion object {
        /** The parent handle MTP uses for a storage's top level. */
        const val ROOT = -1 // 0xFFFFFFFF
    }
}

class MtpFile(
    private val fs: MtpStorageFileSystem,
    private val handle: Int,
    private val info: MtpObjectInfo?,
    override val parent: UsbFile?,
) : AbstractUsbFile() {
    private val session get() = fs.session

    override val isDirectory: Boolean get() = handle == MtpStorageFileSystem.ROOT || info?.format == MtpConstants.FORMAT_ASSOCIATION
    override var name: String
        get() = if (isRoot) "" else info?.name ?: "?"
        set(_) = throw IOException("this phone is connected read-only")
    override var length: Long
        get() = info?.compressedSizeLong ?: 0L
        set(_) = throw IOException("this phone is connected read-only")
    override val isRoot: Boolean get() = handle == MtpStorageFileSystem.ROOT

    override fun createdAt(): Long = (info?.dateCreated ?: 0L)
    override fun lastModified(): Long = (info?.dateModified ?: 0L)
    override fun lastAccessed(): Long = lastModified()

    override fun list(): Array<String> = listFiles().map { it.name }.toTypedArray()

    override fun listFiles(): Array<UsbFile> {
        if (!isDirectory) throw IOException("$name is not a folder")
        return synchronized(session.lock) {
            val handles = session.device.getObjectHandles(fs.storageId, 0, handle)
                ?: throw IOException("the device did not list $absolutePath (unlocked, and set to File transfer?)")
            handles.toList().mapNotNull { h -> session.device.getObjectInfo(h)?.let { MtpFile(fs, h, it, this@MtpFile) } }
        }.toTypedArray<UsbFile>()
    }

    override fun read(offset: Long, destination: ByteBuffer) {
        if (isDirectory) throw IOException("$name is a folder")
        var at = offset
        val tmp = ByteArray(minOf(destination.remaining(), 1 shl 20).coerceAtLeast(1))
        while (destination.hasRemaining()) {
            val want = minOf(destination.remaining(), tmp.size)
            val n = synchronized(session.lock) { session.device.getPartialObject64(handle, at, want.toLong(), tmp) }
            if (n <= 0) throw IOException("the device returned no data for $name at $at")
            destination.put(tmp, 0, n.toInt())
            at += n
        }
    }

    override fun write(offset: Long, source: ByteBuffer) = throw IOException("this phone is connected read-only")
    override fun flush() {}
    override fun close() {}
    override fun createDirectory(name: String): UsbFile = throw IOException("this phone is connected read-only")
    override fun createFile(name: String): UsbFile = throw IOException("this phone is connected read-only")
    override fun moveTo(destination: UsbFile) = throw IOException("this phone is connected read-only")
    override fun delete() = throw IOException("this phone is connected read-only")
}

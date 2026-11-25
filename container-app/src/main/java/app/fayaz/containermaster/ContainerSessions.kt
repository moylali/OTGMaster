package app.fayaz.containermaster

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import app.fayaz.otgmaster.block.CachedBlockDevice
import app.fayaz.otgmaster.block.ChannelBlockDevice
import app.fayaz.otgmaster.block.ReadOnlyBlockDeviceDriver
import app.fayaz.otgmaster.core.DocumentSource
import app.fayaz.otgmaster.core.MountRegistry
import app.fayaz.otgmaster.core.MountedTree
import app.fayaz.otgmaster.disk.DiskMounter
import app.fayaz.otgmaster.disk.UsbFileDocumentSource
import app.fayaz.otgmaster.provider.TreeDocumentsProvider
import app.fayaz.otgmaster.veracrypt.ContainerType
import app.fayaz.otgmaster.veracrypt.NativeDecryptedBlockDevice
import app.fayaz.otgmaster.veracrypt.VeraCryptUnlocker
import app.fayaz.otgmaster.veracrypt.VolumeCandidate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.jahnen.libaums.core.driver.ByteBlockDevice
import me.jahnen.libaums.core.fs.FileSystem
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The containers this process has open: Container Master's [MountRegistry]. A session is
 * published only once its filesystem is mounted, and withdrawn first when it is locked, so
 * the provider never serves a half-open or half-closed container.
 */
object ContainerSessions : MountRegistry {

    /** Container Master opens every container read-only in this first release. */
    const val READ_ONLY_REASON = "Container Master opens containers read-only"

    class Session internal constructor(
        override val id: String,
        override val name: String,
        val uri: Uri,
        private val fileSystem: FileSystem,
        private val decrypted: NativeDecryptedBlockDevice,
        private val descriptor: ParcelFileDescriptor,
    ) : MountedTree {
        override val readOnlyReason: String get() = READ_ONLY_REASON
        override val tree: DocumentSource = UsbFileDocumentSource(fileSystem) { READ_ONLY_REASON }
        override val session: Any get() = fileSystem
        // Unknown for a read-only container; the root shows none rather than a guess.
        override fun freeBytes(): Long = 0L

        internal fun close() {
            runCatching {
                when (fileSystem) {
                    is app.fayaz.otgmaster.exfat.ExFatFileSystem -> fileSystem.unmount()
                    is app.fayaz.otgmaster.ntfs.NtfsFileSystem -> fileSystem.unmount()
                    is app.fayaz.otgmaster.ext4.Ext4FileSystem -> fileSystem.unmount()
                    is app.fayaz.otgmaster.apfs.ApfsFileSystem -> fileSystem.unmount()
                    else -> Unit   // libaums' FAT32 holds nothing to release on a read-only mount
                }
            }
            // Zeroes the volume key, then the picked file's descriptor goes.
            runCatching { decrypted.close() }
            runCatching { descriptor.close() }
        }
    }

    private val sessions = CopyOnWriteArrayList<Session>()
    private val _open = MutableStateFlow<List<Session>>(emptyList())

    /** The open sessions, for the activity and the service. */
    val open: StateFlow<List<Session>> = _open

    override fun mounted(): List<MountedTree> = sessions.toList()

    class AlreadyOpenException : Exception("already open")

    /**
     * The public ID of a VeraCrypt container: a document ID's first part, so a client's grant on
     * a folder inside it still resolves after a lock and a fresh unlock. Taken from the header's
     * salt, its first 64 bytes, which VeraCrypt draws at random for each volume: the picker can
     * return a different URI for the same file each time (a search and a Downloads listing gave
     * two on the Pixel), so the URI is not an identity. It changes when the password is changed,
     * since that writes a new salt. Hashed, so the ID does not carry the salt.
     */
    internal fun idForSalt(salt: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("veracrypt-salt:".toByteArray())
        md.update(salt)
        return md.digest().joinToString("") { "%02x".format(it) }.take(10)
    }

    /**
     * Opens the VeraCrypt container [uri] with [password] and [pim]: reads it through its
     * descriptor, unlocks it, mounts its filesystem read-only and publishes it. Runs the key
     * derivation, so call it off the main thread. [password] is cleared by the unlocker.
     *
     * @throws IllegalArgumentException for a wrong password or PIM, or a file that is not one
     * @throws AlreadyOpenException if the same file is open
     */
    fun openVeraCrypt(context: Context, uri: Uri, displayName: String, password: CharArray, pim: Int?): Session {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw java.io.FileNotFoundException("The file could not be opened")
        try {
            val channel = FileInputStream(descriptor.fileDescriptor).channel
            val length = descriptor.statSize.takeIf { it >= 0 }
            val device = ChannelBlockDevice(channel, length)
            val id = idForSalt(device.readBlocks(0, 1).copyOf(64))
            // Before the key derivation: the same file picked through another URI is refused here.
            if (sessions.any { it.id == id }) throw AlreadyOpenException()
            val candidate = VolumeCandidate(displayName, 0, device.blockCount, ContainerType.VERACRYPT)
            val decrypted = VeraCryptUnlocker().unlock(device, candidate, password, pim = pim,
                headers = VeraCryptUnlocker.Headers.STANDARD_ONLY)
            try {
                val cached = CachedBlockDevice(decrypted, readAheadBytes = CachedBlockDevice.DEFAULT_READAHEAD_BYTES)
                val fs = DiskMounter.mount(ReadOnlyBlockDeviceDriver(ByteBlockDevice(cached)))
                val session = Session(id, displayName, uri, fs, decrypted, descriptor)
                synchronized(this) {
                    if (sessions.any { it.id == id }) { session.close(); throw AlreadyOpenException() }
                    sessions += session
                    _open.value = sessions.toList()
                }
                notifyRoots(context)
                return session
            } catch (e: Throwable) {
                runCatching { decrypted.close() }
                throw e
            }
        } catch (e: Throwable) {
            runCatching { descriptor.close() }
            throw e
        }
    }

    /**
     * Locks [id]: withdraws its root first, so no new open or read is admitted and a descriptor
     * a client still holds fails with EIO from then on, then waits for callbacks already queued,
     * then unmounts under the tree's lock, zeroes the key and closes the file.
     */
    fun lock(context: Context, id: String) {
        val session = synchronized(this) {
            val s = sessions.firstOrNull { it.id == id } ?: return
            sessions.remove(s)
            _open.value = sessions.toList()
            s
        }
        notifyRoots(context)
        TreeDocumentsProvider.drainCallbacks(id)
        TreeDocumentsProvider.onDrive("$id:/", "lock") { session.close() }
    }

    fun lockAll(context: Context) = sessions.map { it.id }.forEach { lock(context, it) }

    private fun notifyRoots(context: Context) {
        context.contentResolver.notifyChange(
            DocumentsContract.buildRootsUri(context.packageName + ".documents"), null)
    }
}

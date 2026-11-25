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

    /**
     * One open container: its tree, how the screen and Report issue describe it, and how it is
     * closed. [session] is the identity a handle and a cached row are tied to (MountedTree).
     */
    class Session internal constructor(
        override val id: String,
        override val name: String,
        val uri: Uri,
        /** "VeraCrypt" or "ZIP". */
        val format: String,
        /** What is inside: "exFAT", "ext4", ... for a volume, "ZIP" for an archive. */
        val fileSystemName: String,
        val capacityBytes: Long?,
        override val tree: DocumentSource,
        override val session: Any,
        private val closer: () -> Unit,
    ) : MountedTree {
        override val readOnlyReason: String get() = READ_ONLY_REASON
        // Unknown for a read-only container; the root shows none rather than a guess.
        override fun freeBytes(): Long = 0L
        internal fun close() = closer()
    }

    private fun veraCryptSession(id: String, name: String, uri: Uri, fs: FileSystem,
                                 decrypted: java.io.Closeable, descriptor: ParcelFileDescriptor, format: String = "VeraCrypt") =
        Session(id, name, uri, format,
            fileSystemName = when (fs) {
                is app.fayaz.otgmaster.exfat.ExFatFileSystem -> "exFAT"
                is app.fayaz.otgmaster.ntfs.NtfsFileSystem -> "NTFS"
                is app.fayaz.otgmaster.ext4.Ext4FileSystem -> "ext4"
                is app.fayaz.otgmaster.apfs.ApfsFileSystem -> "APFS"
                else -> "FAT32"
            },
            capacityBytes = runCatching { fs.capacity }.getOrNull(),
            tree = UsbFileDocumentSource(fs) { READ_ONLY_REASON },
            session = fs,
        ) {
            runCatching {
                when (fs) {
                    is app.fayaz.otgmaster.exfat.ExFatFileSystem -> fs.unmount()
                    is app.fayaz.otgmaster.ntfs.NtfsFileSystem -> fs.unmount()
                    is app.fayaz.otgmaster.ext4.Ext4FileSystem -> fs.unmount()
                    is app.fayaz.otgmaster.apfs.ApfsFileSystem -> fs.unmount()
                    else -> Unit   // libaums' FAT32 holds nothing to release on a read-only mount
                }
            }
            // Zeroes the volume key, then the picked file's descriptor goes.
            runCatching { decrypted.close() }
            runCatching { descriptor.close() }
        }

    private val sessions = CopyOnWriteArrayList<Session>()
    private val _open = MutableStateFlow<List<Session>>(emptyList())

    /** The open sessions, for the activity and the service. */
    val open: StateFlow<List<Session>> = _open

    override fun mounted(): List<MountedTree> = sessions.toList().filter { !expired(it.id) }

    /**
     * Every provider operation asks the registry for its tree, so this is where an idle
     * container is refused: before the operation is admitted, also for a descriptor a client
     * already holds (OpenFile.isLive asks find), and also after the phone slept through the
     * timeout without the service's check running (architecture proposal, N2).
     */
    override fun find(id: String): MountedTree? {
        val s = sessions.firstOrNull { it.id == id } ?: return null
        if (expired(id)) { appContext?.let { ctx -> Thread { lock(ctx, id, idle = true) }.start() }; return null }
        return s
    }

    @Volatile internal var appContext: Context? = null
    private val lastUse = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Idle minutes before a container locks; 0 never. Set from Settings. */
    @Volatile var idleMinutes: Int = 15

    /** Content I/O or a user action on [id]: renews its idle time. */
    fun touch(id: String) { lastUse[id] = android.os.SystemClock.elapsedRealtime() }

    fun expired(id: String): Boolean {
        val minutes = idleMinutes
        if (minutes <= 0) return false
        val last = lastUse[id] ?: return false
        return android.os.SystemClock.elapsedRealtime() - last > minutes * 60_000L
    }

    /** Locks every container idle past the timeout; the service calls it periodically. */
    fun lockExpired(context: Context) = sessions.filter { expired(it.id) }.forEach { lock(context, it.id, idle = true) }

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
        val masked = app.fayaz.otgmaster.feedback.PathMask.mark(displayName)
        CmLog.log("Opening $masked as VeraCrypt (PIM ${if (pim == null) "default" else "custom"})")
        val t0 = android.os.SystemClock.elapsedRealtime()
        try {
            return openVeraCryptLogged(context, uri, displayName, password, pim).also {
                CmLog.log("Unlocked and mounted $masked (${it.fileSystemName}, read-only) in ${android.os.SystemClock.elapsedRealtime() - t0} ms; open as ${it.id}")
            }
        } catch (e: IllegalArgumentException) {
            CmLog.log("Could not unlock $masked: wrong password or PIM, or not a VeraCrypt volume")
            throw e
        } catch (e: AlreadyOpenException) {
            CmLog.log("$masked is already open")
            throw e
        } catch (e: Exception) {
            CmLog.log("Could not open $masked: ${e.message ?: e.javaClass.simpleName}")
            throw e
        }
    }

    private fun openVeraCryptLogged(context: Context, uri: Uri, displayName: String, password: CharArray, pim: Int?): Session {
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
                val session = veraCryptSession(id, displayName, uri, fs, decrypted, descriptor)
                synchronized(this) {
                    if (sessions.any { it.id == id }) { session.close(); throw AlreadyOpenException() }
                    touch(id)
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
     * Opens the LUKS1 or LUKS2 volume [uri] with [password] (architecture proposal, Milestone 5):
     * the same path as VeraCrypt from the unlocked device on (cache, read-only guard, DiskMounter,
     * the provider). The ID comes from the header's UUID, which a password change keeps. The
     * phone's free memory is passed so an Argon2 keyslot that cannot fit is refused, not tried.
     */
    fun openLuks(context: Context, uri: Uri, displayName: String, password: CharArray): Session {
        val masked = app.fayaz.otgmaster.feedback.PathMask.mark(displayName)
        CmLog.log("Opening $masked as LUKS")
        val t0 = android.os.SystemClock.elapsedRealtime()
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw java.io.FileNotFoundException("The file could not be opened")
        val passwordBytes = String(password).toByteArray(Charsets.UTF_8)
        password.fill('\u0000')
        try {
            val channel = FileInputStream(descriptor.fileDescriptor).channel
            val device = ChannelBlockDevice(channel, descriptor.statSize.takeIf { it >= 0 })
            val head = device.readBlocks(0, 1)
            val id = MessageDigest.getInstance("SHA-256").apply { update("luks-uuid:".toByteArray()); update(head, 168, 40) }
                .digest().joinToString("") { "%02x".format(it) }.take(10)
            if (sessions.any { it.id == id }) { CmLog.log("$masked is already open"); throw AlreadyOpenException() }
            val version = app.fayaz.otgmaster.luks.LuksUnlocker().luksVersion(device, 0)
            val mem = android.app.ActivityManager.MemoryInfo().also {
                context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }.availMem
            val decrypted = app.fayaz.otgmaster.luks.LuksUnlocker().unlock(device, 0, device.blockCount, passwordBytes, availableMemoryBytes = mem)
            try {
                val cached = CachedBlockDevice(decrypted, readAheadBytes = CachedBlockDevice.DEFAULT_READAHEAD_BYTES)
                val fs = DiskMounter.mount(ReadOnlyBlockDeviceDriver(ByteBlockDevice(cached)))
                val session = veraCryptSession(id, displayName, uri, fs, decrypted, descriptor, format = "LUKS$version")
                synchronized(this) {
                    if (sessions.any { it.id == id }) { session.close(); throw AlreadyOpenException() }
                    touch(id)
                    sessions += session
                    _open.value = sessions.toList()
                }
                notifyRoots(context)
                CmLog.log("Unlocked and mounted $masked (LUKS$version, ${session.fileSystemName}, read-only) in ${android.os.SystemClock.elapsedRealtime() - t0} ms; open as $id")
                return session
            } catch (e: Throwable) {
                runCatching { decrypted.close() }; throw e
            }
        } catch (e: app.fayaz.otgmaster.luks.LuksUnlocker.WrongPasswordException) {
            runCatching { descriptor.close() }
            CmLog.log("Could not unlock $masked: wrong password"); throw IllegalArgumentException(e.message, e)
        } catch (e: AlreadyOpenException) {
            runCatching { descriptor.close() }; throw e
        } catch (e: Exception) {
            runCatching { descriptor.close() }
            CmLog.log("Could not open $masked: ${e.message ?: e.javaClass.simpleName}"); throw e
        } finally {
            passwordBytes.fill(0)
        }
    }

    /**
     * Opens the ZIP archive [uri]: reads its central directory through the descriptor and
     * publishes its tree. No password. Its ID comes from the archive's own bytes (its length,
     * first 4 KiB and end record), so a client's grant survives a fresh pick, as for VeraCrypt.
     */
    fun openZip(context: Context, uri: Uri, displayName: String): Session {
        val masked = app.fayaz.otgmaster.feedback.PathMask.mark(displayName)
        CmLog.log("Opening $masked as a ZIP archive")
        val t0 = android.os.SystemClock.elapsedRealtime()
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw java.io.FileNotFoundException("The file could not be opened")
        try {
            val channel = FileInputStream(descriptor.fileDescriptor).channel
            val length = descriptor.statSize.takeIf { it >= 0 } ?: channel.size()
            val md = MessageDigest.getInstance("SHA-256")
            md.update("zip:$length:".toByteArray())
            fun part(pos: Long, len: Int) = java.nio.ByteBuffer.allocate(len).also { b ->
                var p = pos; while (b.hasRemaining()) { if (channel.read(b, p).also { p += it } < 0) break } }.array()
            md.update(part(0, minOf(4096L, length).toInt())); md.update(part(maxOf(0, length - 22), minOf(22L, length).toInt()))
            val id = md.digest().joinToString("") { "%02x".format(it) }.take(10)
            if (sessions.any { it.id == id }) { CmLog.log("$masked is already open"); throw AlreadyOpenException() }
            val archive = app.fayaz.otgmaster.zip.ZipArchive.open(channel, length)
            val tree = app.fayaz.otgmaster.zip.ZipDocumentSource(archive)
            val session = Session(id, displayName, uri, "ZIP", "ZIP", length, tree, archive) {
                runCatching { descriptor.close() }
            }
            synchronized(this) {
                if (sessions.any { it.id == id }) { session.close(); throw AlreadyOpenException() }
                touch(id)
                sessions += session
                _open.value = sessions.toList()
            }
            notifyRoots(context)
            val files = archive.entries.count { !it.isDirectory }
            CmLog.log("Opened $masked: $files files" +
                (if (archive.skippedUnsafe > 0) ", ${archive.skippedUnsafe} entries with unsafe names left out" else "") +
                " in ${android.os.SystemClock.elapsedRealtime() - t0} ms; open as $id")
            return session
        } catch (e: AlreadyOpenException) {
            runCatching { descriptor.close() }; throw e
        } catch (e: Exception) {
            runCatching { descriptor.close() }
            CmLog.log("Could not open $masked: ${e.message ?: e.javaClass.simpleName}")
            throw e
        }
    }

    /**
     * Opens the ISO 9660 image [uri] (Milestone 6): its volume descriptors, then its folders as
     * they are listed. No password. Its ID comes from its primary volume descriptor (label,
     * dates, sizes), so a client's grant survives a fresh pick.
     */
    fun openIso(context: Context, uri: Uri, displayName: String): Session {
        val masked = app.fayaz.otgmaster.feedback.PathMask.mark(displayName)
        CmLog.log("Opening $masked as an ISO 9660 image")
        val t0 = android.os.SystemClock.elapsedRealtime()
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw java.io.FileNotFoundException("The file could not be opened")
        try {
            val channel = FileInputStream(descriptor.fileDescriptor).channel
            val length = descriptor.statSize.takeIf { it >= 0 } ?: channel.size()
            val pvd = java.nio.ByteBuffer.allocate(2048).also { b -> var p = 16L * 2048
                while (b.hasRemaining()) { if (channel.read(b, p).also { p += it } < 0) break } }.array()
            val id = MessageDigest.getInstance("SHA-256").apply { update("iso-pvd:".toByteArray()); update(pvd) }
                .digest().joinToString("") { "%02x".format(it) }.take(10)
            if (sessions.any { it.id == id }) { CmLog.log("$masked is already open"); throw AlreadyOpenException() }
            val tree = app.fayaz.otgmaster.iso.IsoDocumentSource.open(channel, length)
            val session = Session(id, displayName, uri, "ISO", "ISO 9660", length, tree, tree) { runCatching { descriptor.close() } }
            synchronized(this) {
                if (sessions.any { it.id == id }) { session.close(); throw AlreadyOpenException() }
                touch(id); sessions += session; _open.value = sessions.toList()
            }
            notifyRoots(context)
            CmLog.log("Opened $masked (${tree.volumeLabel.ifEmpty { "no label" }}) in ${android.os.SystemClock.elapsedRealtime() - t0} ms; open as $id")
            return session
        } catch (e: AlreadyOpenException) {
            runCatching { descriptor.close() }; throw e
        } catch (e: Exception) {
            runCatching { descriptor.close() }
            CmLog.log("Could not open $masked: ${e.message ?: e.javaClass.simpleName}"); throw e
        }
    }

    /**
     * Locks [id]: withdraws its root first, so no new open or read is admitted and a descriptor
     * a client still holds fails with EIO from then on, then waits for callbacks already queued,
     * then unmounts under the tree's lock, zeroes the key and closes the file.
     */
    fun lock(context: Context, id: String, idle: Boolean = false) {
        val session = synchronized(this) {
            val s = sessions.firstOrNull { it.id == id } ?: return
            sessions.remove(s)
            _open.value = sessions.toList()
            s
        }
        notifyRoots(context)
        TreeDocumentsProvider.drainCallbacks(id)
        TreeDocumentsProvider.onDrive("$id:/", "lock") { session.close() }
        lastUse.remove(id)
        CmLog.log("Locked ${app.fayaz.otgmaster.feedback.PathMask.mark(session.name)} ($id)" +
            if (idle) ", idle for more than $idleMinutes min" else "")
    }

    /** The format a picked file looks like, from its first 4 KiB; logged. */
    fun detect(context: Context, uri: Uri, displayName: String): ContainerFormat {
        // The first 4 KiB, and the start of sector 16 (byte 32,768), where an ISO image says CD001.
        val first = context.contentResolver.openInputStream(uri)?.use { s ->
            val b = ByteArray(32_768 + 8); var n = 0
            while (n < b.size) { val r = s.read(b, n, b.size - n); if (r < 0) break; n += r }
            b.copyOf(n)
        } ?: ByteArray(0)
        val head = first.copyOf(minOf(first.size, 4096))
        val s16 = if (first.size > 32_768) first.copyOfRange(32_768, first.size) else ByteArray(0)
        return ContainerFormat.detect(head, s16).also {
            CmLog.log("Chose ${app.fayaz.otgmaster.feedback.PathMask.mark(displayName)}: " + when (it) {
                ContainerFormat.VERACRYPT -> "no known signature, so it is offered as VeraCrypt"
                ContainerFormat.LUKS -> "a LUKS volume"
                ContainerFormat.ZIP -> "a ZIP archive, opened without a password"
                ContainerFormat.ISO -> "an ISO 9660 disc image, opened without a password"
            })
        }
    }

    fun lockAll(context: Context) = sessions.map { it.id }.forEach { lock(context, it) }

    private fun notifyRoots(context: Context) {
        context.contentResolver.notifyChange(
            DocumentsContract.buildRootsUri(context.packageName + ".documents"), null)
    }
}

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

    /** Why a container is not writable: Read-only mode, or a format Container Master only reads. */
    const val READ_ONLY_REASON = "Container Master opens containers read-only"
    /** A VeraCrypt or LUKS file asked for read and write whose provider gave read access only. */
    const val NO_WRITE_ACCESS = "The app that holds this file gave read access only"

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
        /** Null when the container is open for writing (Read-only mode off, a VeraCrypt or LUKS file). */
        override val readOnlyReason: String? = READ_ONLY_REASON,
        private val free: () -> Long = { 0L },
        private val closer: () -> Unit,
    ) : MountedTree {
        // Unknown for a read-only container; the root shows none rather than a guess.
        override fun freeBytes(): Long = free()
        internal fun close() = closer()
    }

    private fun veraCryptSession(id: String, name: String, uri: Uri, fs: FileSystem,
                                 decrypted: java.io.Closeable, descriptor: ParcelFileDescriptor, format: String = "VeraCrypt",
                                 device: ChannelBlockDevice? = null, readOnlyReason: String? = READ_ONLY_REASON) =
        Session(id, name, uri, format,
            fileSystemName = when (fs) {
                is app.fayaz.otgmaster.exfat.ExFatFileSystem -> "exFAT"
                is app.fayaz.otgmaster.ntfs.NtfsFileSystem -> "NTFS"
                is app.fayaz.otgmaster.ext4.Ext4FileSystem -> "ext4"
                is app.fayaz.otgmaster.apfs.ApfsFileSystem -> "APFS"
                else -> "FAT32"
            },
            capacityBytes = runCatching { fs.capacity }.getOrNull(),
            tree = UsbFileDocumentSource(fs) { readOnlyReason },
            session = fs,
            readOnlyReason = readOnlyReason,
            free = { if (readOnlyReason == null) runCatching { fs.freeSpace }.getOrDefault(0L) else 0L },
        ) {
            runCatching {
                when (fs) {
                    is app.fayaz.otgmaster.exfat.ExFatFileSystem -> fs.unmount()
                    is app.fayaz.otgmaster.ntfs.NtfsFileSystem -> fs.unmount()
                    is app.fayaz.otgmaster.ext4.Ext4FileSystem -> fs.unmount()
                    is app.fayaz.otgmaster.apfs.ApfsFileSystem -> fs.unmount()
                    else -> Unit   // libaums' FAT32 writes through at each file's close; nothing to release
                }
            }
            // Zeroes the volume key; then what was written is forced to the file's storage, and
            // the picked file's descriptor goes.
            runCatching { decrypted.close() }
            device?.let { d -> runCatching { d.close() }.onFailure { CmLog.log("Could not finish writing ${app.fayaz.otgmaster.feedback.PathMask.mark(name)}: ${it.message}") } }
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

    /** The ID of a LUKS volume: from its UUID, bytes 168 to 208 of the header. */
    internal fun idForLuks(head: ByteArray): String =
        MessageDigest.getInstance("SHA-256").apply { update("luks-uuid:".toByteArray()); update(head, 168, 40) }
            .digest().joinToString("") { "%02x".format(it) }.take(10)

    /** The ID of an AES Crypt file: from its first 256 bytes (header, extensions and IV). */
    internal fun idForAesCrypt(head: ByteArray): String =
        MessageDigest.getInstance("SHA-256").apply { update("aescrypt:".toByteArray()); update(head) }
            .digest().joinToString("") { "%02x".format(it) }.take(10)

    /**
     * The ID of a 7z archive: from its 32-byte signature header, which holds the CRC, offset and
     * size of the archive's own index, so it differs between archives and survives a re-pick.
     */
    internal fun idFor7z(head: ByteArray): String =
        MessageDigest.getInstance("SHA-256").apply { update("7z:".toByteArray()); update(head, 0, 32) }
            .digest().joinToString("") { "%02x".format(it) }.take(10)

    /**
     * The ID a file of [format] will open under, from its first bytes [head], for the formats
     * that take a password; null for the others. The screen looks a saved password up by it
     * when the same file is picked again, whatever URI the picker gives this time.
     */
    internal fun passwordIdFor(format: ContainerFormat, head: ByteArray): String? = when (format) {
        ContainerFormat.VERACRYPT -> if (head.size >= 64) idForSalt(head.copyOf(64)) else null
        ContainerFormat.LUKS -> if (head.size >= 208) idForLuks(head) else null
        ContainerFormat.AESCRYPT -> idForAesCrypt(head.copyOf(minOf(256, head.size)))
        ContainerFormat.SEVENZIP -> if (head.size >= 32) idFor7z(head) else null
        else -> null   // ZIP, ISO and TAR have no password; a restic repository's ID comes from detectFolder
    }

    /**
     * The picked file's descriptor, for reading and writing when [writable] and its provider
     * allows it, else for reading. Returns the descriptor and why it is read-only (null when
     * writable). A provider that refuses "rw" (a handed-over file often has a read grant only)
     * leaves the container read-only rather than unopened, and the log says so.
     */
    private fun openDescriptor(context: Context, uri: Uri, writable: Boolean, masked: String): Pair<ParcelFileDescriptor, String?> {
        if (writable) {
            val rw = runCatching { context.contentResolver.openFileDescriptor(uri, "rw") }.getOrNull()
            if (rw != null) return rw to null
            CmLog.log("$masked can only be read: the app that holds it gave no write access, so it opens read-only")
        }
        val r = context.contentResolver.openFileDescriptor(uri, "r") ?: throw java.io.FileNotFoundException("The file could not be opened")
        return r to (if (writable) NO_WRITE_ACCESS else READ_ONLY_REASON)
    }

    private fun deviceOver(descriptor: ParcelFileDescriptor, readOnlyReason: String?) = ChannelBlockDevice(
        FileInputStream(descriptor.fileDescriptor).channel, descriptor.statSize.takeIf { it >= 0 },
        writeChannel = if (readOnlyReason == null) java.io.FileOutputStream(descriptor.fileDescriptor).channel else null)

    private fun mountOver(cached: CachedBlockDevice, readOnlyReason: String?): FileSystem =
        DiskMounter.mount(if (readOnlyReason == null) ByteBlockDevice(cached) else ReadOnlyBlockDeviceDriver(ByteBlockDevice(cached)))

    /**
     * Opens the VeraCrypt container [uri] with [password] and [pim]: reads it through its
     * descriptor, unlocks it, mounts its filesystem (for writing when [writable] and the file's
     * provider allows it, read-only otherwise) and publishes it. Runs the key
     * derivation, so call it off the main thread. [password] is cleared by the unlocker.
     *
     * @throws IllegalArgumentException for a wrong password or PIM, or a file that is not one
     * @throws AlreadyOpenException if the same file is open
     */
    fun openVeraCrypt(context: Context, uri: Uri, displayName: String, password: CharArray, pim: Int?, writable: Boolean = false): Session {
        val masked = app.fayaz.otgmaster.feedback.PathMask.mark(displayName)
        CmLog.log("Opening $masked as VeraCrypt (PIM ${if (pim == null) "default" else "custom"})")
        val t0 = android.os.SystemClock.elapsedRealtime()
        try {
            return openVeraCryptLogged(context, uri, displayName, password, pim, writable, masked).also {
                CmLog.log("Unlocked and mounted $masked (${it.fileSystemName}, ${if (it.readOnlyReason == null) "read and write" else "read-only"}) in ${android.os.SystemClock.elapsedRealtime() - t0} ms; open as ${it.id}")
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

    private fun openVeraCryptLogged(context: Context, uri: Uri, displayName: String, password: CharArray, pim: Int?,
                                    writable: Boolean, masked: String): Session {
        val (descriptor, readOnlyReason) = openDescriptor(context, uri, writable, masked)
        try {
            val device = deviceOver(descriptor, readOnlyReason)
            val id = idForSalt(device.readBlocks(0, 1).copyOf(64))
            // Before the key derivation: the same file picked through another URI is refused here.
            if (sessions.any { it.id == id }) throw AlreadyOpenException()
            val candidate = VolumeCandidate(displayName, 0, device.blockCount, ContainerType.VERACRYPT)
            val decrypted = VeraCryptUnlocker().unlock(device, candidate, password, pim = pim,
                headers = VeraCryptUnlocker.Headers.STANDARD_ONLY)
            try {
                val cached = CachedBlockDevice(decrypted, readAheadBytes = CachedBlockDevice.DEFAULT_READAHEAD_BYTES)
                val fs = mountOver(cached, readOnlyReason)
                val session = veraCryptSession(id, displayName, uri, fs, decrypted, descriptor, device = device, readOnlyReason = readOnlyReason)
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
    fun openLuks(context: Context, uri: Uri, displayName: String, password: CharArray, writable: Boolean = false): Session {
        val masked = app.fayaz.otgmaster.feedback.PathMask.mark(displayName)
        CmLog.log("Opening $masked as LUKS")
        val t0 = android.os.SystemClock.elapsedRealtime()
        val (descriptor, readOnlyReason) = openDescriptor(context, uri, writable, masked)
        val passwordBytes = String(password).toByteArray(Charsets.UTF_8)
        password.fill('\u0000')
        try {
            val device = deviceOver(descriptor, readOnlyReason)
            val head = device.readBlocks(0, 1)
            val id = idForLuks(head)
            if (sessions.any { it.id == id }) { CmLog.log("$masked is already open"); throw AlreadyOpenException() }
            val version = app.fayaz.otgmaster.luks.LuksUnlocker().luksVersion(device, 0)
            val mem = android.app.ActivityManager.MemoryInfo().also {
                context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }.availMem
            val decrypted = app.fayaz.otgmaster.luks.LuksUnlocker().unlock(device, 0, device.blockCount, passwordBytes, availableMemoryBytes = mem)
            try {
                val cached = CachedBlockDevice(decrypted, readAheadBytes = CachedBlockDevice.DEFAULT_READAHEAD_BYTES)
                val fs = mountOver(cached, readOnlyReason)
                val session = veraCryptSession(id, displayName, uri, fs, decrypted, descriptor, format = "LUKS$version",
                    device = device, readOnlyReason = readOnlyReason)
                synchronized(this) {
                    if (sessions.any { it.id == id }) { session.close(); throw AlreadyOpenException() }
                    touch(id)
                    sessions += session
                    _open.value = sessions.toList()
                }
                notifyRoots(context)
                CmLog.log("Unlocked and mounted $masked (LUKS$version, ${session.fileSystemName}, ${if (readOnlyReason == null) "read and write" else "read-only"}) in ${android.os.SystemClock.elapsedRealtime() - t0} ms; open as $id")
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
    /**
     * Opens a TAR (plain or gzip-compressed) or 7z archive read-only and publishes it; [password]
     * for an encrypted 7z, empty otherwise. Reads the archive's whole index first.
     *
     * @throws IllegalArgumentException for a 7z whose index is encrypted and the password wrong or missing
     * @throws AlreadyOpenException if the same file is open
     */
    fun openArchive(context: Context, uri: Uri, displayName: String, format: ContainerFormat, password: CharArray = CharArray(0)): Session {
        val masked = app.fayaz.otgmaster.feedback.PathMask.mark(displayName)
        val label = when (format) { ContainerFormat.TAR -> "TAR"; ContainerFormat.TARGZ -> "TAR.GZ"; else -> "7z" }
        CmLog.log("Opening $masked as a $label archive")
        val t0 = android.os.SystemClock.elapsedRealtime()
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw java.io.FileNotFoundException("The file could not be opened")
        try {
            val channel = FileInputStream(descriptor.fileDescriptor).channel
            val length = descriptor.statSize.takeIf { it >= 0 } ?: channel.size()
            val head = java.nio.ByteBuffer.allocate(minOf(4096L, length).toInt()).also { b -> var p = 0L
                while (b.hasRemaining()) { if (channel.read(b, p).also { p += it } < 0) break } }.array()
            val id = if (format == ContainerFormat.SEVENZIP) idFor7z(head) else
                MessageDigest.getInstance("SHA-256").apply { update("tar:$length:".toByteArray()); update(head) }
                    .digest().joinToString("") { "%02x".format(it) }.take(10)
            if (sessions.any { it.id == id }) { CmLog.log("$masked is already open"); throw AlreadyOpenException() }
            val files: Int
            val skipped: Int
            val tree: app.fayaz.otgmaster.core.DocumentSource
            val closer: () -> Unit
            if (format == ContainerFormat.SEVENZIP) {
                val sz = try {
                    app.fayaz.otgmaster.sevenzip.SevenZipDocumentSource.open(channel, password)
                } catch (e: java.io.IOException) {
                    // A 7z whose list of files is encrypted cannot be read at all without its password,
                    // and a wrong one decrypts it to garbage ("Compressed data is corrupt", from XZ).
                    if (password.isNotEmpty() || e.javaClass.simpleName == "PasswordRequiredException")
                        throw IllegalArgumentException("wrong or missing password", e)
                    throw e
                }
                tree = sz; files = -1; skipped = sz.skippedUnsafe
                closer = { runCatching { sz.close() }; runCatching { descriptor.close() } }
            } else {
                val tar = app.fayaz.otgmaster.tar.TarDocumentSource.open(channel, gzip = format == ContainerFormat.TARGZ)
                tree = tar; files = tar.archive.entries.count { !it.isDirectory }; skipped = tar.archive.skippedUnsafe + tar.archive.skippedOther
                closer = { runCatching { descriptor.close() } }
            }
            val session = Session(id, displayName, uri, label, label, length, tree, tree, closer = closer)
            synchronized(this) {
                if (sessions.any { it.id == id }) { session.close(); throw AlreadyOpenException() }
                touch(id)
                sessions += session
                _open.value = sessions.toList()
            }
            notifyRoots(context)
            CmLog.log("Opened $masked" + (if (files >= 0) ": $files files" else "") +
                (if (skipped > 0) ", $skipped entries with unsafe names, links or devices left out" else "") +
                " in ${android.os.SystemClock.elapsedRealtime() - t0} ms; open as $id")
            return session
        } catch (e: AlreadyOpenException) {
            runCatching { descriptor.close() }; throw e
        } catch (e: IllegalArgumentException) {
            runCatching { descriptor.close() }
            CmLog.log("Could not open $masked: the password is wrong or missing"); throw e
        } catch (e: Exception) {
            runCatching { descriptor.close() }
            CmLog.log("Could not open $masked: ${e.message ?: e.javaClass.simpleName}")
            throw e
        } finally {
            password.fill('\u0000')
        }
    }

    /**
     * Opens a macOS disk image (UDIF) read-only: finds the first partition holding a filesystem
     * Container Master reads (APFS, exFAT, FAT32, NTFS, ext4) and mounts it. A partition comes
     * from the image's own table (one "blkx" entry each, as hdiutil writes them) or, for an
     * image of a whole disk, from the GPT inside it. [password] opens an encrypted APFS volume.
     *
     * @throws IllegalArgumentException for an encrypted APFS volume and a wrong or missing password
     */
    fun openDmg(context: Context, uri: Uri, displayName: String, password: CharArray = CharArray(0)): Session {
        val masked = app.fayaz.otgmaster.feedback.PathMask.mark(displayName)
        CmLog.log("Opening $masked as a macOS disk image")
        val t0 = android.os.SystemClock.elapsedRealtime()
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw java.io.FileNotFoundException("The file could not be opened")
        try {
            val channel = FileInputStream(descriptor.fileDescriptor).channel
            val length = descriptor.statSize.takeIf { it >= 0 } ?: channel.size()
            val img = app.fayaz.otgmaster.dmg.UdifImage.open(channel, length) { data, size -> app.fayaz.otgmaster.apfs.Lzfse.decompress(data, size) }
            val id = MessageDigest.getInstance("SHA-256").apply {
                update("dmg:$length:".toByteArray()); update(img.readBlocks(0, minOf(8L, img.blockCount).toInt()))
            }.digest().joinToString("") { "%02x".format(it) }.take(10)
            if (sessions.any { it.id == id }) { CmLog.log("$masked is already open"); throw AlreadyOpenException() }
            val candidates = (img.partitions.map { Triple(it.name, it.firstSector, it.sectorCount) } + gptPartitions(img))
                .filter { it.third >= 64 }.distinctBy { it.second to it.third }
            var unsupported: String? = null
            for ((name, first, count) in candidates) {
                val part: app.fayaz.otgmaster.block.RawBlockDevice =
                    if (first == 0L && count >= img.blockCount) img else app.fayaz.otgmaster.block.SlicedBlockDevice(img, first, count)
                val detected = runCatching { app.fayaz.otgmaster.fs.FilesystemDetector.detect(part) }.getOrNull() ?: continue
                if (detected is app.fayaz.otgmaster.fs.DetectedFilesystem.Unsupported) { unsupported = detected.reason; continue }
                if (detected !is app.fayaz.otgmaster.fs.DetectedFilesystem.Supported) continue
                val cached = CachedBlockDevice(part, readAheadBytes = CachedBlockDevice.DEFAULT_READAHEAD_BYTES)
                val driver = ReadOnlyBlockDeviceDriver(ByteBlockDevice(cached))
                val fs = if (detected.displayName == "APFS") {
                    try {
                        app.fayaz.otgmaster.apfs.ApfsFileSystemCreator.mount(driver, String(password).ifEmpty { null })
                    } catch (e: app.fayaz.otgmaster.apfs.ApfsFileSystem.WrongPasswordException) {
                        CmLog.log("Could not open $masked: its APFS volume is encrypted, and the password is wrong or missing")
                        throw IllegalArgumentException("wrong or missing password", e)
                    }
                } else DiskMounter.mount(driver)
                val session = veraCryptSession(id, displayName, uri, fs, java.io.Closeable {}, descriptor, format = "DMG")
                synchronized(this) {
                    if (sessions.any { it.id == id }) { session.close(); throw AlreadyOpenException() }
                    touch(id)
                    sessions += session
                    _open.value = sessions.toList()
                }
                notifyRoots(context)
                CmLog.log("Opened $masked: partition \"$name\", ${session.fileSystemName}, read-only, in ${android.os.SystemClock.elapsedRealtime() - t0} ms; open as $id")
                return session
            }
            throw java.io.IOException(unsupported ?: "No partition in this disk image holds a filesystem Container Master reads")
        } catch (e: AlreadyOpenException) {
            runCatching { descriptor.close() }; throw e
        } catch (e: IllegalArgumentException) {
            runCatching { descriptor.close() }; throw e
        } catch (e: Exception) {
            runCatching { descriptor.close() }
            CmLog.log("Could not open $masked: ${e.message ?: e.javaClass.simpleName}")
            throw e
        } finally {
            password.fill('\u0000')
        }
    }

    /** The partitions of a GPT on [disk] (first sector, count), named by their GPT names; empty without a GPT. */
    private fun gptPartitions(disk: app.fayaz.otgmaster.block.RawBlockDevice): List<Triple<String, Long, Long>> = runCatching {
        val h = java.nio.ByteBuffer.wrap(disk.readBlocks(1, 1)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (String(h.array(), 0, 8, Charsets.US_ASCII) != "EFI PART") return emptyList()
        val entriesLba = h.getLong(72); val n = h.getInt(80); val size = h.getInt(84)
        if (n !in 1..256 || size !in 128..512) return emptyList()
        val bytes = disk.readBlocks(entriesLba, ((n.toLong() * size + 511) / 512).toInt())
        val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        (0 until n).mapNotNull { i ->
            val o = i * size
            if ((0 until 16).all { bytes[o + it].toInt() == 0 }) return@mapNotNull null
            val first = b.getLong(o + 32); val last = b.getLong(o + 40)
            val name = String(bytes, o + 56, 72, Charsets.UTF_16LE).trimEnd('\u0000')
            if (first <= 0 || last < first || last >= disk.blockCount) null else Triple(name.ifEmpty { "GPT partition ${i + 1}" }, first, last - first + 1)
        }
    }.getOrDefault(emptyList())

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
     * Opens the AES Crypt file [uri] with [password] (Milestone 6): one file inside, named
     * after the container without ".aes". The whole ciphertext's HMAC is checked before the
     * file is published, so a damaged or altered file opens nothing. Its ID comes from its
     * header (which holds its random IV), so a grant survives a fresh pick.
     */
    fun openAesCrypt(context: Context, uri: Uri, displayName: String, password: CharArray): Session {
        val masked = app.fayaz.otgmaster.feedback.PathMask.mark(displayName)
        CmLog.log("Opening $masked as AES Crypt")
        val t0 = android.os.SystemClock.elapsedRealtime()
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw java.io.FileNotFoundException("The file could not be opened")
        try {
            val channel = FileInputStream(descriptor.fileDescriptor).channel
            val length = descriptor.statSize.takeIf { it >= 0 } ?: channel.size()
            val head = java.nio.ByteBuffer.allocate(minOf(256L, length).toInt()).also { b -> var p = 0L
                while (b.hasRemaining()) { if (channel.read(b, p).also { p += it } < 0) break } }.array()
            val id = idForAesCrypt(head)
            if (sessions.any { it.id == id }) { CmLog.log("$masked is already open"); throw AlreadyOpenException() }
            val tree = try {
                app.fayaz.otgmaster.aescrypt.AesCryptSource.open(channel, length, password, displayName)
            } finally { password.fill('\u0000') }
            val session = Session(id, displayName, uri, "AES Crypt", "AES Crypt", length, tree, tree) {
                tree.close(); runCatching { descriptor.close() }
            }
            synchronized(this) {
                if (sessions.any { it.id == id }) { session.close(); throw AlreadyOpenException() }
                touch(id); sessions += session; _open.value = sessions.toList()
            }
            notifyRoots(context)
            CmLog.log("Opened $masked (verified) in ${android.os.SystemClock.elapsedRealtime() - t0} ms; open as $id")
            return session
        } catch (e: app.fayaz.otgmaster.aescrypt.AesCryptSource.WrongPasswordException) {
            runCatching { descriptor.close() }
            CmLog.log("Could not open $masked: wrong password"); throw IllegalArgumentException(e.message, e)
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

    /** The ID of a restic repository: from its config file, which holds the repository's own ID. */
    internal fun idForRestic(config: ByteArray): String =
        MessageDigest.getInstance("SHA-256").apply { update("restic:".toByteArray()); update(config) }
            .digest().joinToString("") { "%02x".format(it) }.take(10)

    /**
     * What a picked folder is: a restic repository (a config file and a keys folder) or nothing
     * Container Master opens.
     */
    fun detectFolder(context: Context, tree: Uri, displayName: String): Detected {
        val b = SafTreeBackend(context, tree)
        val masked = app.fayaz.otgmaster.feedback.PathMask.mark(displayName)
        if (b.exists("config") && b.exists("keys")) {
            CmLog.log("Chose $masked: a restic repository")
            return Detected(ContainerFormat.RESTIC, idForRestic(b.read("config")))
        }
        CmLog.log("Chose $masked: not a restic repository (no config file and keys folder)")
        throw java.io.IOException("This folder is not a restic repository: it has no config file and keys folder")
    }

    /**
     * Opens the restic repository in the picked folder [tree] with [password], read-only: every
     * snapshot is a folder at its root, named by its time and host, as in OTG Master's Backups.
     *
     * @throws IllegalArgumentException for a wrong password
     * @throws AlreadyOpenException if the same repository is open
     */
    fun openRestic(context: Context, tree: Uri, displayName: String, password: CharArray): Session {
        val masked = app.fayaz.otgmaster.feedback.PathMask.mark(displayName)
        CmLog.log("Opening $masked as a restic repository")
        val t0 = android.os.SystemClock.elapsedRealtime()
        val pw = String(password).toByteArray(Charsets.UTF_8)
        password.fill('\u0000')
        try {
            val backend = SafTreeBackend(context, tree)
            val id = idForRestic(backend.read("config"))
            if (sessions.any { it.id == id }) { CmLog.log("$masked is already open"); throw AlreadyOpenException() }
            val repo = app.fayaz.otgmaster.backup.restic.ResticRepository.open(backend, pw)
                ?: run { CmLog.log("Could not open $masked: wrong password"); throw IllegalArgumentException("wrong password") }
            val fs = app.fayaz.otgmaster.backup.restic.ResticFileSystem(repo, displayName)
            val snapshots = runCatching { repo.snapshots().size }.getOrDefault(-1)
            val session = Session(id, displayName, tree, "restic", "restic", null,
                UsbFileDocumentSource(fs) { "Container Master mounts backups read-only" }, fs) {}
            synchronized(this) {
                if (sessions.any { it.id == id }) throw AlreadyOpenException()
                touch(id)
                sessions += session
                _open.value = sessions.toList()
            }
            notifyRoots(context)
            CmLog.log("Opened $masked: $snapshots snapshots in ${android.os.SystemClock.elapsedRealtime() - t0} ms; open as $id")
            return session
        } catch (e: IllegalArgumentException) { throw e
        } catch (e: AlreadyOpenException) { throw e
        } catch (e: Exception) {
            CmLog.log("Could not open $masked: ${e.message ?: e.javaClass.simpleName}"); throw e
        } finally {
            pw.fill(0)
        }
    }

    /** What a picked file looks like: its format, and for a format with a password its ID. */
    data class Detected(val format: ContainerFormat, val passwordId: String?)

    /** The format a picked file looks like, from its first 4 KiB, and its ID; logged. */
    fun detect(context: Context, uri: Uri, displayName: String): Detected {
        // The first 4 KiB, and the start of sector 16 (byte 32,768), where an ISO image says CD001.
        val first = context.contentResolver.openInputStream(uri)?.use { s ->
            val b = ByteArray(32_768 + 8); var n = 0
            while (n < b.size) { val r = s.read(b, n, b.size - n); if (r < 0) break; n += r }
            b.copyOf(n)
        } ?: ByteArray(0)
        val head = first.copyOf(minOf(first.size, 4096))
        val s16 = if (first.size > 32_768) first.copyOfRange(32_768, first.size) else ByteArray(0)
        // A disk image says what it is at its end: the koly trailer in the last 512 bytes.
        val tail = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val ch = FileInputStream(pfd.fileDescriptor).channel
            val len = pfd.statSize.takeIf { it >= 0 } ?: ch.size()
            if (len < 512) ByteArray(0) else java.nio.ByteBuffer.allocate(512).also { b -> var p = len - 512
                while (b.hasRemaining()) { if (ch.read(b, p).also { p += it } < 0) break } }.array()
        } }.getOrNull() ?: ByteArray(0)
        val format = if (app.fayaz.otgmaster.dmg.UdifImage.looksLikeUdif(tail)) ContainerFormat.DMG else ContainerFormat.detect(head, s16)
        CmLog.log("Chose ${app.fayaz.otgmaster.feedback.PathMask.mark(displayName)}: " + when (format) {
            ContainerFormat.VERACRYPT -> "no known signature, so it is offered as VeraCrypt"
            ContainerFormat.LUKS -> "a LUKS volume"
            ContainerFormat.ZIP -> "a ZIP archive, opened without a password"
            ContainerFormat.ISO -> "an ISO 9660 disc image, opened without a password"
            ContainerFormat.AESCRYPT -> "an AES Crypt file"
            ContainerFormat.TAR -> "a TAR archive, opened without a password"
            ContainerFormat.TARGZ -> "a gzip-compressed TAR archive, opened without a password"
            ContainerFormat.SEVENZIP -> "a 7z archive"
            ContainerFormat.RESTIC -> "a restic repository"
            ContainerFormat.DMG -> "a macOS disk image"
        })
        return Detected(format, passwordIdFor(format, head))
    }

    fun lockAll(context: Context) = sessions.map { it.id }.forEach { lock(context, it) }

    private fun notifyRoots(context: Context) {
        context.contentResolver.notifyChange(
            DocumentsContract.buildRootsUri(context.packageName + ".documents"), null)
    }
}

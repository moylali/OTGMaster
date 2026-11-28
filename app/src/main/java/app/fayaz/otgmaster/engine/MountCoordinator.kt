package app.fayaz.otgmaster.engine

import android.content.Context
import android.net.Uri
import app.fayaz.otgmaster.MountedDrive
import app.fayaz.otgmaster.OtgMasterState
import app.fayaz.otgmaster.R
import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.fs.DetectedFilesystem
import app.fayaz.otgmaster.fs.FilesystemDetector
import app.fayaz.otgmaster.PlainPartition
import app.fayaz.otgmaster.reason
import app.fayaz.otgmaster.veracrypt.ContainerType
import app.fayaz.otgmaster.veracrypt.VeraCryptUnlocker
import app.fayaz.otgmaster.veracrypt.VolumeCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The work that changes what is mounted, outside MainActivity (architecture proposal,
 * Milestone 2b step 1; docs/ENGINE_SPLIT.md).
 *
 * The screen asks for an operation and gets its result; the coordinator does the device
 * work, adds or removes the drive in [OtgMasterState], and writes the log through
 * [OtgMasterState.log], which reaches the screen's log while one is alive and the report's
 * history always. What the screen shows about it (toasts, the form, the cards, saved
 * passwords) stays in the screen. Same process for now: step 2 puts the screens in `:ui`
 * and these calls behind a door, as Container Master's EngineProvider is.
 */
object MountCoordinator {
    private lateinit var appContext: Context

    /** Called once from OtgMasterApp.onCreate, before any activity. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun log(line: String) = OtgMasterState.log(line)

    // ── Device state (2b step 2) ─────────────────────────────────────────────────────────────
    // What MainActivity kept per instance and the engine owns: the USB connections, the form's
    // candidates and the work in flight. Moved here unchanged; the screen still drives them, and
    // reads them through properties of the old names. They outlive an activity instance now, which
    // is why MainActivity.onDestroy leaves them to a successor that is already up.

    /** USB connections by device key, kept open while their partitions are offered or mounted. */
    val openedDevices: MutableMap<String, RawBlockDevice> = java.util.Collections.synchronizedMap(LinkedHashMap())
    /** In-flight unmounts per USB device, so the shared connection is closed by the last one. */
    val pendingUnmounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** The drives and partitions the unlock form offers. */
    val deviceCandidates = androidx.compose.runtime.mutableStateOf<List<app.fayaz.otgmaster.UsbDeviceCandidate>>(emptyList())
    /** A probe is running; set and read on the main thread. */
    @Volatile var isProbingDevices = false
    @Volatile var isQemuProbing = false
    /** Partitions with an unlock started and not finished. */
    val unlockingPartitions = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    class EjectInFlight(val title: String, val androidCanRead: Boolean, val notify: Boolean)
    val ejectsInFlight = HashMap<String, EjectInFlight>()
    /** Candidates withheld from the form while an auto-mount prompt is up. */
    val deferredCandidates = LinkedHashMap<String, app.fayaz.otgmaster.UsbDeviceCandidate>()
    val mtpInFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    val reofferInFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    val plainMountsInFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Forgets what a screen's own coroutines tracked: probes, unlocks, plain and MTP mounts and
     * re-offers in flight, and candidates held behind its auto-mount prompt. Those run in the
     * activity's scope and are cancelled with it, often before they could clear their entry, so a
     * new screen starts from none, as it did when this state was the activity's. Unmounts and
     * Ejects run in the process scope and are kept.
     */
    fun forgetScreenWork() {
        isProbingDevices = false
        isQemuProbing = false
        unlockingPartitions.value = emptySet()
        plainMountsInFlight.clear()
        mtpInFlight.clear()
        reofferInFlight.clear()
        deferredCandidates.clear()
    }

    /** What an unlock ended with, when it did not throw. A throw is an unlock failure. */
    sealed interface UnlockOutcome {
        /** Mounted and listed in [OtgMasterState.mountedDrives]. */
        class Mounted(val drive: MountedDrive) : UnlockOutcome
        /** Not mounted, and the log says why: no container, or a filesystem that could not mount. */
        data object NotMounted : UnlockOutcome
    }

    class UnlockRequest(
        val deviceName: String,
        val deviceDisplayName: String,
        val device: RawBlockDevice,
        val candidate: VolumeCandidate,
        val password: String,
        val pim: Int?,
        val keyfiles: List<Uri>,
        val cipher: app.fayaz.otgmaster.veracrypt.VeraCryptCipher,
        val hash: app.fayaz.otgmaster.veracrypt.VeraCryptHash,
        val readOnly: Boolean,
        val protectHidden: app.fayaz.otgmaster.veracrypt.HiddenVolumeCredentials?,
        val veraCryptHeaders: VeraCryptUnlocker.Headers,
        val useBackupHeader: Boolean,
        /** A write reached the protected hidden volume; called with the volume's title. */
        val onProtectionTriggered: (String) -> Unit,
    )

    /**
     * Unlocks [r]'s candidate and mounts the filesystem inside it.
     *
     * The caller holds [OtgMasterState.deviceMutex] across this and whatever it does with the
     * result, as MainActivity did before the move: one unlock sequence at a time per process,
     * because every partition of a drive shares one USB command pipe.
     *
     * Throws when the container does not open (a wrong password, a format the app refuses,
     * an I/O error): the caller decides what that means for saved credentials.
     */
    fun unlockAndMount(r: UnlockRequest): UnlockOutcome {
        val device = r.device
        val candidate = r.candidate
        val password = r.password
        // An UNENCRYPTED candidate has a readable filesystem at its first
        // sector, so there is no header to derive a key from. Falling
        // through to the VeraCrypt branch ran 16,000 PBKDF2 iterations
        // against an NTFS boot sector and then reported "failed to
        // unlock", which reads as a wrong password rather than the truth.
        if (candidate.containerType == ContainerType.UNENCRYPTED) {
            val available = device.blockCount - candidate.startBlock
            val plain = if (available <= 0) DetectedFilesystem.Unknown else
                FilesystemDetector.detectFromBytes(
                    device.readBlocks(candidate.startBlock, minOf(4L, available).toInt()))
            log(appContext.getString(R.string.log_volume_not_encrypted, candidate.label, plain.displayName))
            return UnlockOutcome.NotMounted
        }
        val decryptedDevice = when (candidate.containerType) {
            ContainerType.LUKS1, ContainerType.LUKS2 -> {
                val passwordBytes = password.toByteArray(Charsets.UTF_8)
                try {
                    app.fayaz.otgmaster.luks.LuksUnlocker().unlock(
                        device, candidate.startBlock, candidate.blockCount, passwordBytes,
                        availableMemoryBytes = availableMemoryBytes(),
                    )
                } finally {
                    passwordBytes.fill(0)
                }
            }
            ContainerType.BITLOCKER ->
                app.fayaz.otgmaster.bitlocker.BitLockerUnlocker().unlock(
                    device, candidate.startBlock, candidate.blockCount, password.toCharArray(),
                    keyFiles = r.keyfiles.map { uri ->
                        // A .BEK file is a few hundred bytes; the cap keeps a wrong pick from filling memory.
                        (appContext.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open the key file"))
                            .use { input ->
                                val buf = ByteArray(64 * 1024 + 1)
                                var n = 0
                                while (n < buf.size) { val c = input.read(buf, n, buf.size - n); if (c < 0) break; n += c }
                                buf.copyOf(n)
                            }
                    },
                )
            // Nothing to decrypt at the block level: libfsapfs takes the
            // password when the filesystem is mounted, below.
            //
            // close() must stop here. Unmounting closes the mount's device
            // chain, and a bare SlicedBlockDevice passes close() on to the USB
            // connection itself — so unmounting an encrypted APFS volume closed
            // the whole stick under every other partition on it, the kernel
            // took it back, and Android mounted its plain partitions read-write
            // (device-matrix Drive 1, OnePlus 7: every run, at the first
            // remount). The other containers' devices do not close the raw one
            // either; its lifetime belongs to openedDevices / unmountDrive.
            ContainerType.APFS -> {
                val slice = app.fayaz.otgmaster.block.SlicedBlockDevice(device, candidate.startBlock,
                    candidate.blockCount ?: (device.blockCount - candidate.startBlock))
                object : RawBlockDevice by slice {
                    override fun close() {}
                }
            }
            else -> VeraCryptUnlocker().unlock(
                device, candidate, password.toCharArray(), r.pim, r.keyfiles, appContext.contentResolver,
                r.cipher, r.hash, protect = r.protectHidden.takeUnless { r.readOnly },
                headers = r.veraCryptHeaders, useBackupHeader = r.useBackupHeader,
            )
        }
        val veraCryptVolume = decryptedDevice as? app.fayaz.otgmaster.veracrypt.NativeDecryptedBlockDevice
        val protection = veraCryptVolume?.hiddenVolumeProtection
        // Set before the filesystem mounts: a mount can write too.
        protection?.onTriggered = {
            r.onProtectionTriggered("${r.deviceDisplayName} · ${candidate.label}")
        }
        val isApfs = candidate.containerType == ContainerType.APFS
        // APFS is not unlocked until it mounts.
        if (!isApfs) log(appContext.getString(R.string.log_unlock_successful))

        val detected = FilesystemDetector.detect(decryptedDevice)
        log(appContext.getString(R.string.log_detected_filesystem, detected.displayName))

        if (detected is DetectedFilesystem.Unsupported) {
            // S01: the decrypted device holds the volume key; nothing will mount it now.
            runCatching { decryptedDevice.close() }
            log(appContext.getString(R.string.log_cannot_mount_reason, detected.reason))
            return UnlockOutcome.NotMounted
        }

        if (detected is DetectedFilesystem.Unknown) {
            log(appContext.getString(R.string.log_filesystem_unrecognized))
        }

        // Read cache with readahead between the filesystem driver and the
        // decrypted device. libexfat reads directory and FAT entries
        // unbuffered — measured at 100,844 preads averaging 24 bytes to list
        // a 10,000-entry directory (docs/IO_PERFORMANCE.md §5.5) — and each
        // one was a USB round trip plus a sector decryption. Caching
        // plaintext means a hit skips both.
        // Readahead helps or hurts depending on the filesystem's metadata
        // layout — see CachedBlockDevice.NO_READAHEAD_BYTES.
        val cachedDevice = wrapWithCache(decryptedDevice, detected.displayName)
        val byteDevice = me.jahnen.libaums.core.driver.ByteBlockDevice(
            cachedDevice as me.jahnen.libaums.core.driver.BlockDeviceDriver
        )
        val fsDevice: me.jahnen.libaums.core.driver.BlockDeviceDriver =
            if (r.readOnly) app.fayaz.otgmaster.block.ReadOnlyBlockDeviceDriver(byteDevice) else byteDevice
        val fileSystem = try {
            if (isApfs) {
                app.fayaz.otgmaster.apfs.ApfsFileSystemCreator.mount(fsDevice, password, candidate.apfsVolume).also {
                    log(appContext.getString(R.string.log_unlock_successful))
                }
            } else {
                app.fayaz.otgmaster.disk.DiskMounter.mount(fsDevice)
            }
        } catch (e: app.fayaz.otgmaster.apfs.ApfsFileSystem.WrongPasswordException) {
            throw e   // an unlock failure, reported (and cached credentials dropped) by the caller
        } catch (e: Exception) {
            android.util.Log.e("OTG_MOUNT", "Failed to mount file system", e)
            // S01: release the volume key held by the decrypted device (its close
            // leaves the USB connection alone).
            runCatching { decryptedDevice.close() }
            log(if (detected is DetectedFilesystem.Unknown)
                appContext.getString(R.string.log_unrecognized_fs_not_mounted)
            else
                appContext.getString(R.string.log_failed_to_mount, detected.displayName, e.reason()))
            return UnlockOutcome.NotMounted
        }
        // Stable across mounts, so a companion app's persisted folder grant
        // still resolves next time (see ExtensionContract).
        val driveId = app.fayaz.otgmaster.extension.ExtensionContract.veraCryptDriveId(
            r.deviceName, candidate.startBlock, veraCryptVolume?.volumeType, OtgMasterState.mountedDrives.map { it.id },
            apfsVolume = candidate.apfsVolume)
        val mountedDrive = MountedDrive(
            id = driveId,
            name = appContext.getString(R.string.mounted_drive_name, r.deviceDisplayName, driveId),
            fileSystem = fileSystem,
            blockDevice = cachedDevice,
            sourceDeviceName = r.deviceName,
            sourceDeviceDisplayName = r.deviceDisplayName,
            rawBlockDevice = device,
            sourceVolumeCandidate = candidate,
            partitionLabel = candidate.label,
            filesystemName = detected.displayName,
            mountedReadOnly = r.readOnly,
            partitionStartBlock = candidate.startBlock,
            partitionBlockCount = candidate.blockCount,
            veraCryptVolumeType = veraCryptVolume?.volumeType,
            hiddenVolumeProtection = protection,
        )
        listMounted(mountedDrive)
        return UnlockOutcome.Mounted(mountedDrive)
    }

    /**
     * Mounts a plain partition of [rawDevice] and lists it. Throws when its filesystem does
     * not mount; the caller reports that.
     */
    fun mountPlain(
        deviceName: String,
        deviceDisplayName: String,
        rawDevice: RawBlockDevice,
        plain: PlainPartition,
        readOnly: Boolean,
    ): MountedDrive {
        val sliced: RawBlockDevice = if (plain.startBlock == 0L) rawDevice
            else app.fayaz.otgmaster.block.SlicedBlockDevice(rawDevice, plain.startBlock, plain.blockCount)
        val adapter = app.fayaz.otgmaster.block.RawBlockDeviceAdapter(sliced)
        // Same cache for plain volumes: libaums rebuilds directory state that
        // libexfat keeps parsed, so FAT32 re-reads blocks instead (its warm
        // listing is 556 ms against exFAT's 30 ms).
        val cachedDevice = wrapWithCache(adapter, plain.filesystemName)
        val byteDevice = me.jahnen.libaums.core.driver.ByteBlockDevice(
            cachedDevice as me.jahnen.libaums.core.driver.BlockDeviceDriver
        )
        val fsDevice: me.jahnen.libaums.core.driver.BlockDeviceDriver =
            if (readOnly) app.fayaz.otgmaster.block.ReadOnlyBlockDeviceDriver(byteDevice) else byteDevice
        val fileSystem = app.fayaz.otgmaster.disk.DiskMounter.mount(fsDevice)
        val driveId = app.fayaz.otgmaster.extension.ExtensionContract.stableDriveId(
            deviceName, plain.startBlock, OtgMasterState.mountedDrives.map { it.id })
        val mountedDrive = MountedDrive(
            id = driveId,
            name = appContext.getString(R.string.mounted_drive_name_plain, deviceDisplayName, plain.filesystemName, driveId),
            fileSystem = fileSystem,
            blockDevice = cachedDevice,
            sourceDeviceName = deviceName,
            sourceDeviceDisplayName = deviceDisplayName,
            isPlain = true,
            rawBlockDevice = rawDevice,
            partitionLabel = plain.label,
            filesystemName = plain.filesystemName,
            mountedReadOnly = readOnly,
            partitionStartBlock = plain.startBlock,
            partitionBlockCount = plain.blockCount,
        )
        listMounted(mountedDrive)
        return mountedDrive
    }

    /**
     * Takes [drive] off the list and tells the Files app, at once and on the caller's thread:
     * from here no new file opens on it. Returns when that was done (currentTimeMillis), for
     * the caller's timing log. The filesystem is still mounted; [tearDown] finishes it.
     */
    fun unlist(drive: MountedDrive): Long {
        OtgMasterState.removeDrive(drive.id)
        val removedAt = System.currentTimeMillis()
        appContext.contentResolver.notifyChange(
            android.provider.DocumentsContract.buildRootsUri(app.fayaz.otgmaster.provider.VeraCryptDocumentProvider.AUTHORITY), null
        )
        return removedAt
    }

    /**
     * Unmounts an unlisted [drive]'s filesystem and closes its decrypted device. Leaves the
     * USB connection open: whether it closes depends on the drive's other partitions, which
     * the caller knows. Throws if the unmount fails.
     */
    suspend fun tearDown(drive: MountedDrive) {
        OtgMasterState.unmountTeardownGate?.await()
        // Wait for any in-flight ProxyFileDescriptor onRelease() callbacks to finish
        // before unmounting — prevents a use-after-free if the OS is still flushing a
        // file write when exfat_unmount frees the ef pointer.
        app.fayaz.otgmaster.provider.VeraCryptDocumentProvider.drainCallbacks(drive.id)
        // Unmounting flushes filesystem metadata, so it writes to the same shared
        // USB device as every other partition.  Remounting empties the whole mount
        // list at once, and four concurrent single-block writes killed the
        // transport with "MAX_RECOVERY_ATTEMPTS Exceeded", which then failed the
        // unlocks that followed.  Only the device work is serialised —
        // drainCallbacks() above waits on in-flight file callbacks that can need
        // the device themselves, so holding the lock across it deadlocks every
        // later mount and unmount.
        OtgMasterState.deviceMutex.withLock {
            when (val fs = drive.fileSystem) {
                is app.fayaz.otgmaster.exfat.ExFatFileSystem -> fs.unmount()
                is app.fayaz.otgmaster.ntfs.NtfsFileSystem -> fs.unmount()
                is app.fayaz.otgmaster.apfs.ApfsFileSystem -> fs.unmount()
                // Leaves the clean flag set; a stream of writes keeps it clear (#36).
                is app.fayaz.otgmaster.ext4.Ext4FileSystem -> fs.unmount()
            }
            // drive.blockDevice is either NativeDecryptedBlockDevice (close zeros the key but
            // does NOT close the underlying USB connection) or RawBlockDeviceAdapter (noop close).
            // The raw USB connection is managed by the caller — closed only when no other partitions remain.
            drive.blockDevice?.close()
        }
    }

    /**
     * Takes [drive] off the list before Format or Encrypt erases it, and unmounts it.
     * [onUnlisted] runs on the main thread once it is off the list, for the screen.
     */
    private suspend fun detachForErase(drive: MountedDrive, onUnlisted: () -> Unit) {
        // Before the drive leaves the list: from here on, a descriptor still open on the
        // old filesystem fails its reads and writes, and its close writes nothing.
        app.fayaz.otgmaster.provider.VeraCryptDocumentProvider.retire(drive.fileSystem)
        withContext(Dispatchers.Main) {
            OtgMasterState.removeDrive(drive.id)
            appContext.contentResolver.notifyChange(
                android.provider.DocumentsContract.buildRootsUri(app.fayaz.otgmaster.extension.ExtensionContract.AUTHORITY), null)
            onUnlisted()
        }
        // As in tearDown: let in-flight file callbacks finish before the filesystem goes.
        app.fayaz.otgmaster.provider.VeraCryptDocumentProvider.drainCallbacks(drive.id)
        OtgMasterState.deviceMutex.withLock {
            when (val fs = drive.fileSystem) {
                is app.fayaz.otgmaster.exfat.ExFatFileSystem -> fs.unmount()
                is app.fayaz.otgmaster.ntfs.NtfsFileSystem -> fs.unmount()
                is app.fayaz.otgmaster.apfs.ApfsFileSystem -> fs.unmount()
            }
        }
    }

    /**
     * Format: an empty filesystem of the same kind, size, cluster size and label, written
     * through [device], the one the volume is mounted from, so a container stays as it is.
     * Returns the new filesystem, mounted and listed in the old one's place. [onMounting]
     * runs when the erase is done and the mount starts.
     */
    suspend fun format(
        drive: MountedDrive,
        device: RawBlockDevice,
        req: app.fayaz.otgmaster.ui.FormatRequest,
        progress: app.fayaz.otgmaster.format.DriveFormatter.Progress,
        onUnlisted: () -> Unit,
        onMounting: () -> Unit,
    ): MountedDrive {
        val label = runCatching { drive.fileSystem.volumeLabel.trim() }.getOrDefault("")
        // Read before anything is touched: a volume the formatter cannot lay out
        // again is refused with its files intact.
        val existing = OtgMasterState.deviceMutex.withLock {
            // A plain partition's boot sector records where it starts; ext4 has no
            // such field, so a change to FAT32 or NTFS takes it from the partition table.
            app.fayaz.otgmaster.format.DriveFormatter.readExisting(device, label,
                partitionStart = if (drive.isPlain) drive.partitionStartBlock ?: 0 else 0)
        }.copy(label = req.label)
        val target = req.filesystem
        detachForErase(drive, onUnlisted)
        OtgMasterState.deviceMutex.withLock {
            app.fayaz.otgmaster.format.DriveFormatter.reformat(device, existing, req.full, progress, target)
            (device as? app.fayaz.otgmaster.block.CachedBlockDevice)?.invalidate()
        }
        onMounting()
        return remountAfterErase(drive, device, target.displayName, drive.sourceVolumeCandidate, drive.isPlain)
    }

    /** Mounts the filesystem now on [device] in [old]'s place, and lists it again. */
    private suspend fun remountAfterErase(
        old: MountedDrive,
        device: RawBlockDevice,
        filesystemName: String,
        candidate: VolumeCandidate?,
        plain: Boolean,
    ): MountedDrive {
        val byteDevice = me.jahnen.libaums.core.driver.ByteBlockDevice(
            device as me.jahnen.libaums.core.driver.BlockDeviceDriver)
        val fileSystem = app.fayaz.otgmaster.disk.DiskMounter.mount(byteDevice)
        val displayName = old.sourceDeviceDisplayName ?: old.name
        val drive = old.copy(
            name = if (plain) appContext.getString(R.string.mounted_drive_name_plain, displayName, filesystemName, old.id)
                   else appContext.getString(R.string.mounted_drive_name, displayName, old.id),
            fileSystem = fileSystem,
            blockDevice = device,
            isPlain = plain,
            sourceVolumeCandidate = candidate,
            filesystemName = filesystemName,
            mountedReadOnly = false,
        )
        OtgMasterState.addDrive(drive)
        withContext(Dispatchers.Main) {
            appContext.contentResolver.notifyChange(
                android.provider.DocumentsContract.buildRootsUri(app.fayaz.otgmaster.extension.ExtensionContract.AUTHORITY), null)
        }
        return drive
    }

    /**
     * Encrypt: a new VeraCrypt or LUKS2 volume over the whole partition [start], [count] of
     * [raw], holding a new filesystem. Erases the partition. The new volume is closed, not
     * mounted; the returned candidate is what the unlock form offers in the old one's place.
     */
    suspend fun encrypt(
        drive: MountedDrive,
        raw: RawBlockDevice,
        start: Long,
        count: Long,
        req: app.fayaz.otgmaster.ui.EncryptRequest,
        progress: app.fayaz.otgmaster.format.DriveFormatter.Progress,
        onUnlisted: () -> Unit,
    ): VolumeCandidate {
        val luks2 = req.type == app.fayaz.otgmaster.ui.EncryptionType.LUKS2
        detachForErase(drive, onUnlisted)
        OtgMasterState.deviceMutex.withLock { drive.blockDevice?.close() }
        // The whole partition, raw. Not closed with the volume: the USB connection
        // under it belongs to openedDevices / unmountDrive, as for APFS.
        val slice = app.fayaz.otgmaster.block.SlicedBlockDevice(raw, start, count)
        val partition = object : RawBlockDevice by slice { override fun close() {} }
        val volume = OtgMasterState.deviceMutex.withLock {
            if (luks2) {
                app.fayaz.otgmaster.format.DriveFormatter.encryptLuks2(partition, req.password.copyOf(),
                    req.filesystem, req.label, req.full, progress)
            } else {
                app.fayaz.otgmaster.format.DriveFormatter.encrypt(partition, req.password, req.pim,
                    req.cipher, req.hash, req.filesystem, req.label, req.full, progress,
                    keyfiles = req.keyfiles.map { uri -> { appContext.contentResolver.openInputStream(uri) } })
            }
        }
        // Not mounted with the key still in memory. The new volume goes to the unlock
        // form instead, so the user proves the password, PIM and keyfiles work while
        // the drive is still empty, rather than after copying files onto it. Issue
        // #94's reporter encrypted, used the drive, and found later that what they
        // had kept would not open it.
        volume.close() // wipes the master key; the USB connection stays open
        return VolumeCandidate(drive.partitionLabel, start, count,
            if (luks2) ContainerType.LUKS2 else ContainerType.VERACRYPT)
    }

    /** Replays an ext4 volume's journal, under the device mutex. Throws if it fails. */
    suspend fun repairJournal(fs: app.fayaz.otgmaster.ext4.Ext4FileSystem) =
        OtgMasterState.deviceMutex.withLock { fs.replayJournal() }

    /**
     * Another phone or a camera over USB (MTP/PTP): opens an MTP session on [d], with
     * permission already granted, and lists each storage it reports as a read-only drive
     * (mtp/MtpFileSystem) under [key]. Throws if the device will not open a session or
     * reports no storage.
     */
    fun mountMtp(d: android.hardware.usb.UsbDevice, key: String): List<MountedDrive> {
        val usb = appContext.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        val connection = usb.openDevice(d) ?: throw java.io.IOException("Android would not open the USB device")
        val mtp = android.mtp.MtpDevice(d)
        if (!mtp.open(connection)) {
            connection.close()
            throw java.io.IOException("the device refused an MTP session: unlock it, choose File transfer, " +
                "and close any other app showing its files")
        }
        val session = app.fayaz.otgmaster.mtp.MtpSession(d, mtp)
        val storages = session.storages()
        if (storages.isEmpty()) throw java.io.IOException("${session.name} reports no storage (is it unlocked?)")
        val drives = storages.map { fs ->
            val id = app.fayaz.otgmaster.extension.ExtensionContract.stableDriveId(
                key, fs.storageId.toLong() and 0xFFFFFFFFL, OtgMasterState.mountedDrives.map { it.id })
            MountedDrive(
                id = id,
                name = "${session.name} · ${fs.volumeLabel}",
                fileSystem = fs,
                blockDevice = null,
                sourceDeviceName = key,
                sourceDeviceDisplayName = session.name,
                isPlain = true,
                partitionLabel = fs.volumeLabel,
                filesystemName = "MTP",
                mountedReadOnly = true,
            ).also { OtgMasterState.addDrive(it) }
        }
        log("Mounted ${session.name} over MTP, read-only: ${drives.joinToString { it.partitionLabel }}")
        appContext.contentResolver.notifyChange(android.provider.DocumentsContract.buildRootsUri(app.fayaz.otgmaster.extension.ExtensionContract.AUTHORITY), null)
        return drives
    }

    /**
     * What the screen shows of the engine's work, installed by the live MainActivity and null when
     * none is up: then a toast is dropped, as it was with no activity, and the rest is in the
     * log and the service's notifications. Called on the main thread.
     */
    interface Screen {
        fun toast(message: String, ok: Boolean)
        /** The mounted drives changed: the cards re-read them. */
        fun drivesChanged()
        /** The form's devices may have changed: the screen probes for new ones. */
        fun devicesChanged()
        /** Whether the screen is in front, so an Eject's end needs no notification. */
        val isResumed: Boolean
    }

    @Volatile var screen: Screen? = null

    /** The drives an Eject of the USB device [key] takes with it. */
    fun ejectTargets(key: String) = OtgMasterState.mountedDrives.filter { it.sourceDeviceName == key }

    /** True while something still needs the drive: an open file, or a backup that uses it. */
    fun deviceBusy(key: String): Boolean = drivesBusy(ejectTargets(key))

    /** The same for [drives] alone, and the backups mounted from them. */
    fun drivesBusy(drives: List<MountedDrive>): Boolean {
        val ids = drives.flatMap { d ->
            listOf(d.id) + app.fayaz.otgmaster.backup.BackupMounts.dependents(d.id).map { it.id }
        }
        if (ids.any { app.fayaz.otgmaster.provider.VeraCryptDocumentProvider.driveInUse(it) }) return true
        val jobs = app.fayaz.otgmaster.backup.JobStore.get(appContext).jobs.value
        return ids.any { id ->
            app.fayaz.otgmaster.ui.jobsForDrive(jobs, id).any { app.fayaz.otgmaster.backup.BackupExecutor.isBusy(it.id) }
        }
    }

    /**
     * Unmounts [drive]: off the list at once, then the filesystem and its decrypted device torn
     * down on another thread, then the USB connection released if nothing else on it needs it,
     * or the partition offered for unlocking again if other partitions are still mounted.
     * [onTornDown] runs on the main thread once the teardown is done, and not at all if it
     * fails: anything that tells the user the drive is safe to unplug belongs there. Called on
     * the main thread.
     */
    fun unmount(drive: MountedDrive, disconnected: Boolean = false, onTornDown: (() -> Unit)? = null) {
        drive.sourceDeviceName?.let { key ->
            pendingUnmounts.merge(key, 1) { a, b -> a + b }
        }
        // A backup mounted from a repository on this drive cannot outlive it.
        app.fayaz.otgmaster.backup.BackupMounts.dependents(drive.id).forEach { unmount(it, disconnected = disconnected) }
        app.fayaz.otgmaster.backup.BackupMounts.forget(drive.id)
        OtgMasterState.unmountsInFlight.incrementAndGet()
        val t0 = System.currentTimeMillis()
        val tRemoved = unlist(drive)
        val tNotified = System.currentTimeMillis()
        screen?.drivesChanged()
        val tUpdated = System.currentTimeMillis()
        removeShortcut(drive.id)
        // All of this is main-thread work. Refreshing the cards reads free space, which is
        // filesystem I/O, and on a slow card through a crypto layer that is the obvious candidate
        // for a stall — but it runs after removeDrive, so in principle it cannot delay a waiter
        // watching the mount list. Measured rather than assumed.
        android.util.Log.i("OTGMaster",
            "unmountDrive ${drive.id}: remove=${tRemoved - t0}ms" +
            " notify=${tNotified - tRemoved}ms update=${tUpdated - tNotified}ms" +
            " shortcut=${System.currentTimeMillis() - tUpdated}ms")
        // In the process scope: a teardown is flushing metadata, and it has to finish even if
        // Android destroys the screen meanwhile (review of #141, PR-3). The service stays in the
        // foreground until it has.
        app.fayaz.otgmaster.MountedDriveService.beginTeardown(appContext, drive.name)
        OtgMasterState.appScope.launch(Dispatchers.IO) {
          try {
            tearDown(drive)
            withContext(Dispatchers.Main) {
                val ejecting = drive.sourceDeviceName?.let { it in OtgMasterState.ejectedDevices } == true
                // A green "Unmounted" would overwrite the disconnect warning with good news,
                // and the message at the end of an Eject says more than one per partition.
                if (!disconnected && !ejecting) screen?.toast("Unmounted: ${drive.name}", true)
                log(appContext.getString(R.string.log_drive_unmounted, drive.name))

                val sourceKey = drive.sourceDeviceName
                val rawDevice = drive.rawBlockDevice
                val volCandidate = drive.sourceVolumeCandidate

                if (sourceKey != null && rawDevice != null) {
                    val stillUnmounting = (pendingUnmounts.merge(sourceKey, -1) { a, b -> a + b } ?: 0) > 0
                    val otherMounted = OtgMasterState.mountedDrives.any { it.sourceDeviceName == sourceKey }
                    val candidatesInForm = deviceCandidates.value.any { it.deviceName == sourceKey }
                    android.util.Log.i("OTGMaster",
                        "unmountDrive ${drive.id} done: plain=${drive.isPlain} stillUnmounting=$stillUnmounting" +
                        " otherMounted=$otherMounted candidatesInForm=$candidatesInForm" +
                        " raw=${objId(rawDevice)} opened=${objId(openedDevices[sourceKey])}")

                    // close() is idempotent, so a connection the detach handler already
                    // closed is safe to close again. What is not safe is closing one a
                    // mounted partition still reads through.
                    val rawInUse = OtgMasterState.mountedDrives.any { it.rawBlockDevice === rawDevice }
                    val current = openedDevices[sourceKey]
                    if (current != null && current !== rawDevice) {
                        // A newer connection to this device already exists. Restoring
                        // this one over it put a dead connection back in openedDevices,
                        // and every unlock through it failed with result == -1.
                        if (!rawInUse) rawDevice.close()
                    } else if (!stillUnmounting && !otherMounted && (ejecting || !candidatesInForm) &&
                            (ejecting || !OtgMasterState.holdConnections)) {
                        // Last partition from this USB — release the connection.
                        //
                        // This used to close only if openedDevices still held it, but a
                        // successful mount of the last encrypted partition removes the
                        // entry, so the connection was never closed: every remount leaked
                        // one, still holding the interface, while the next probe opened
                        // another to the same device.
                        openedDevices.remove(sourceKey)
                        if (!rawInUse) rawDevice.close()
                        if (ejecting) finishEject(sourceKey)
                    } else if (!ejecting && !drive.isPlain && volCandidate != null) {
                        // Other partitions still alive; restore this candidate so the user can remount.
                        openedDevices[sourceKey] = rawDevice
                        val existing = deviceCandidates.value.find { it.deviceName == sourceKey }
                        if (existing != null) {
                            deviceCandidates.value = deviceCandidates.value.map {
                                if (it.deviceName == sourceKey &&
                                        it.candidates.none { c -> c.slotKey == volCandidate.slotKey })
                                    it.copy(candidates = (it.candidates + volCandidate).sortedWith(compareBy({ c -> c.startBlock }, { c -> c.apfsVolume })))
                                else it
                            }
                        } else {
                            deviceCandidates.value = deviceCandidates.value + app.fayaz.otgmaster.UsbDeviceCandidate(
                                deviceName = sourceKey,
                                displayName = drive.sourceDeviceDisplayName ?: sourceKey,
                                blockDevice = rawDevice,
                                candidates = listOf(volCandidate),
                                plainPartitions = emptyList()
                            )
                        }
                    }
                }

                screen?.devicesChanged()
                onTornDown?.invoke()
            }
          } finally {
            OtgMasterState.unmountsInFlight.decrementAndGet()
            app.fayaz.otgmaster.MountedDriveService.endTeardown(appContext, drive.name)
          }
        }
    }

    /**
     * Unmounts every partition of [key] and releases the device, so Android can take it.
     * What was only offered for unlocking goes too: its connection holds the interface as well.
     * Called on the main thread.
     */
    fun ejectNow(key: String, notify: Boolean) {
        val targets = ejectTargets(key)
        val title = OtgMasterState.pendingEjects.value[key]?.title
            ?: targets.firstNotNullOfOrNull { it.sourceDeviceDisplayName } ?: key
        OtgMasterState.pendingEjects.value = OtgMasterState.pendingEjects.value - key
        OtgMasterState.driveWatchers.remove("eject:$key")
        if (targets.isEmpty()) return

        ejectsInFlight[key] = EjectInFlight(
            title,
            app.fayaz.otgmaster.usb.UsbAccess.androidCanRead(targets.map {
                app.fayaz.otgmaster.usb.EjectedPartition(it.isPlain, it.filesystemName)
            }),
            notify,
        )
        OtgMasterState.ejectedDevices.add(key)
        deviceCandidates.value = deviceCandidates.value.filterNot { it.deviceName == key }
        deferredCandidates.remove(key)
        val raws = targets.mapNotNull { it.rawBlockDevice }
        openedDevices.remove(key)?.takeIf { o -> raws.none { it === o } }?.let { o ->
            OtgMasterState.appScope.launch(Dispatchers.IO) { runCatching { o.close() } }
        }
        targets.forEach { unmount(it) }
    }

    /** The last partition is unmounted and the connection closed. */
    private fun finishEject(key: String) {
        val done = ejectsInFlight.remove(key) ?: return
        val message = appContext.getString(if (done.androidCanRead) R.string.eject_done_android else R.string.eject_done_other)
        log(appContext.getString(R.string.log_ejected, done.title))
        val s = screen
        s?.toast(message, true)
        if (done.notify || s?.isResumed != true) {
            app.fayaz.otgmaster.MountedDriveService.driveEjected(appContext, done.title, message)
        }
    }

    private fun objId(o: Any?): String = if (o == null) "null" else Integer.toHexString(System.identityHashCode(o))

    /** A launcher share target for [drive], so files can be shared straight onto it. */
    fun pushShortcut(drive: MountedDrive) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        val shortcutManager = appContext.getSystemService(android.content.pm.ShortcutManager::class.java) ?: return
        val intent = android.content.Intent(appContext, app.fayaz.otgmaster.MainActivity::class.java).apply {
            action = android.content.Intent.ACTION_DEFAULT
            putExtra(app.fayaz.otgmaster.MainActivity.EXTRA_DRIVE_ID, drive.id)
        }
        val shortcut = android.content.pm.ShortcutInfo.Builder(appContext, "drive_share_${drive.id}")
            .setShortLabel(drive.name)
            .setLongLabel(drive.name)
            .setIcon(android.graphics.drawable.Icon.createWithResource(appContext, R.mipmap.ic_launcher))
            .setIntent(intent)
            .setCategories(setOf(app.fayaz.otgmaster.MainActivity.SHARE_TARGET_CATEGORY))
            .setLongLived(true)
            .build()
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                shortcutManager.pushDynamicShortcut(shortcut)
            } else {
                val live = OtgMasterState.mountedDrives.map { "drive_share_${it.id}" }.toSet()
                val stale = shortcutManager.dynamicShortcuts.map { it.id }.filter { it !in live }
                if (stale.isNotEmpty()) shortcutManager.removeDynamicShortcuts(stale)
                if (shortcutManager.dynamicShortcuts.size < shortcutManager.maxShortcutCountPerActivity)
                    shortcutManager.addDynamicShortcuts(listOf(shortcut))
            }
        } catch (e: Exception) {
            android.util.Log.w("OTGMaster", "share shortcut for ${drive.name} not added: ${e.message}")
        }
    }

    fun removeShortcut(driveId: String) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        val shortcutManager = appContext.getSystemService(android.content.pm.ShortcutManager::class.java) ?: return
        shortcutManager.removeDynamicShortcuts(listOf("drive_share_$driveId"))
    }

    /** What a probe of a disk found: every partition, the plain ones, and those the form can act on. */
    data class Probed(
        val all: List<VolumeCandidate>,
        val plain: List<PlainPartition>,
        /** Without UNENCRYPTED (mounted as plain, or an unsupported filesystem) and UNKNOWN (not identified). */
        val unlockable: List<VolumeCandidate>,
    )

    /**
     * Reads [device]'s partition table and what each partition holds. Blocking I/O; call it off
     * the main thread. Throws if the partition table cannot be read.
     */
    fun probe(device: RawBlockDevice): Probed {
        val all = VeraCryptUnlocker().probeCandidates(device)
        // Detect plain filesystems here too, on the same IO thread (readBlocks is blocking).
        val plain = app.fayaz.otgmaster.fs.PlainPartitions.detect(device, all)
        // Only candidates the picker can act on. UNENCRYPTED has nothing to unlock — it is
        // either mounted as a plain partition or its filesystem is unsupported — and UNKNOWN
        // means the probe could not identify it at all.
        val unlockable = all.filter { it.containerType != ContainerType.UNKNOWN && it.containerType != ContainerType.UNENCRYPTED }
        return Probed(all, plain, unlockable)
    }

    /**
     * Re-reads a partly mounted drive [key] through its held connection [raw], under the device
     * mutex, and returns every partition and the locked ones not mounted yet: what Unmount or a
     * held connection leaves to offer again.
     */
    suspend fun probeLocked(key: String, raw: RawBlockDevice): Pair<List<VolumeCandidate>, List<VolumeCandidate>> {
        val probed = OtgMasterState.deviceMutex.withLock { VeraCryptUnlocker().probeCandidates(raw) }
        val mounted = OtgMasterState.mountedDrives.filter { it.sourceDeviceName == key }
        val mountedLabels = mounted.map { it.partitionLabel }.toSet()
        val mountedStarts = mounted.mapNotNull { it.sourceVolumeCandidate?.slotKey }.toSet()
        val locked = probed.filter {
            it.containerType != ContainerType.UNKNOWN && it.containerType != ContainerType.UNENCRYPTED &&
                it.label !in mountedLabels && it.slotKey !in mountedStarts
        }
        return probed to locked
    }

    /** What [openAndProbe] found: the drives it opened, as form entries, and what it says about the rest. */
    data class ProbeRun(
        /** One entry per drive opened, with every partition the probe found. */
        val devices: List<app.fayaz.otgmaster.UsbDeviceCandidate>,
        /** One reason per attached device that was not opened, whether or not another one was. */
        val notOpened: List<String>,
        /** One line per partition neither mounted nor offered, with its drive's name, so a drive with "missing" partitions says why. */
        val skipped: List<String>,
        /** The volumes Android was using just before the claim, for reportTakenFromAndroid. */
        val androidHad: List<String>,
    )

    /**
     * Opens every attached USB drive not in [excludeKeys] and reads its partitions (2b step 2,
     * slice 3). Blocking I/O; call it off the main thread. Throws if opening fails as a whole;
     * a drive whose partition table cannot be read comes back with no partitions, and the
     * failure goes to the report's history. [baseIndex] numbers the drives' display names after
     * those the form already shows; [knownVolumes] are the removable volumes Android had before.
     */
    fun openAndProbe(excludeKeys: Set<String>, knownVolumes: Set<String>, baseIndex: Int): ProbeRun {
        val opener = app.fayaz.otgmaster.usb.LibaumsRawBlockDeviceOpener(appContext)
        // What Android is using just before the claim.
        val androidHad = runCatching { app.fayaz.otgmaster.usb.UsbAccess.androidVolumeNames(appContext, knownVolumes) }
            .getOrDefault(emptyList())
        val openedList = opener.openAllAvailable(excludeKeys)
        val notOpened = opener.notOpened.toList()
        openedList.forEach {
            android.util.Log.i("OTGMaster", "probe opened ${it.deviceKey} as ${Integer.toHexString(System.identityHashCode(it.blockDevice))}")
        }
        val skipped = mutableListOf<String>()
        val devices = openedList.mapIndexed { index, opened ->
            val displayName = app.fayaz.otgmaster.usb.UsbDeviceDescriber.friendlyName(opened.usbDevice, baseIndex + index)
            val (all, plain, unlockable) = try {
                probe(opened.blockDevice)
            } catch (e: Exception) {
                e.printStackTrace()
                OtgMasterState.logFailure("probe", "Reading the partition table of $displayName failed", e)
                Probed(emptyList(), emptyList(), emptyList())
            }
            app.fayaz.otgmaster.fs.SkippedPartitions.lines(
                all, plain.map { it.startBlock }.toSet(), opened.blockDevice.blockSize, ::formatSizeForLog,
            ) { c ->
                runCatching {
                    FilesystemDetector.detectFromBytes(
                        opened.blockDevice.readBlocks(c.startBlock, minOf(4L, opened.blockDevice.blockCount - c.startBlock).toInt()))
                }.getOrDefault(DetectedFilesystem.Unknown)
            }.forEach { skipped += "$displayName · $it" }
            app.fayaz.otgmaster.UsbDeviceCandidate(opened.deviceKey, displayName, opened.blockDevice, unlockable, plain, all.size - unlockable.size)
        }
        return ProbeRun(devices, notOpened, skipped, androidHad)
    }

    private fun formatSizeForLog(bytes: Long): String = app.fayaz.otgmaster.formatSize(bytes)

    /** Newly probed drives split by what happens to them next; see [classify]. */
    data class Classified(
        /** Drives with plain partitions, all of which auto mount. */
        val withPlain: List<app.fayaz.otgmaster.UsbDeviceCandidate>,
        /** Drives with partitions to unlock, for the form or auto mount: the plain ones taken out. */
        val encrypted: List<app.fayaz.otgmaster.UsbDeviceCandidate>,
    )

    /**
     * Splits newly probed drives: every plain partition is mounted at once, and a drive with
     * partitions to unlock goes to the form with its plain ones left out (and counted as hidden),
     * so the form shows only what is still to unlock. Drives with no plain partition come first
     * in [Classified.encrypted], then the mixed ones, as the form always listed them.
     */
    fun classify(devices: List<app.fayaz.otgmaster.UsbDeviceCandidate>): Classified {
        val (hasPlain, pureEncrypted) = devices.partition { it.plainPartitions.isNotEmpty() }
        val (purePlain, mixed) = hasPlain.partition { device ->
            val plainStarts = device.plainPartitions.map { it.startBlock }.toSet()
            device.candidates.all { it.isIn(plainStarts) }
        }
        val mixedEncrypted = mixed.map { device ->
            val plainStarts = device.plainPartitions.map { it.startBlock }.toSet()
            val kept = device.candidates.filter { !it.isIn(plainStarts) }
            device.copy(candidates = kept, hiddenPartitions = device.hiddenPartitions + (device.candidates.size - kept.size))
        }
        return Classified(purePlain + mixed, pureEncrypted + mixedEncrypted)
    }

    /** Adds a freshly mounted drive to the list, says if it is read-only, tells the Files app. */
    private fun listMounted(drive: MountedDrive) {
        OtgMasterState.addDrive(drive)
        drive.readOnlyReason?.let { reason -> log("${drive.name} is read-only: $reason") }
        appContext.contentResolver.notifyChange(
            android.provider.DocumentsContract.buildRootsUri(app.fayaz.otgmaster.provider.VeraCryptDocumentProvider.AUTHORITY), null
        )
    }

    /**
     * Memory the phone has free right now, for refusing an Argon2 keyslot that cannot fit
     * before it starts: running out part way is a kill, not an exception the unlock can catch.
     */
    private fun availableMemoryBytes(): Long? = runCatching {
        val info = android.app.ActivityManager.MemoryInfo()
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(info)
        info.availMem
    }.getOrNull()

    /**
     * Wraps [device] in the block cache unless configuration disables it.
     *
     * Returning the bare device when disabled lets the cache be A/B'd in one
     * session; [device] already implements BlockDeviceDriver in both mount paths.
     */
    fun wrapWithCache(device: RawBlockDevice, filesystemName: String): RawBlockDevice {
        val cfg = OtgMasterState.cacheConfig
        if (cfg?.enabled == false) {
            log("Block cache: DISABLED for this mount")
            android.util.Log.i("OTGMaster", "block cache disabled for this mount")
            return device
        }
        val readAhead = cfg?.readAheadBytes ?: readAheadFor(filesystemName)
        android.util.Log.i("OTGMaster", "block cache enabled, readahead $readAhead bytes")
        return app.fayaz.otgmaster.block.CachedBlockDevice(device, readAheadBytes = readAhead)
    }

    /**
     * Cache line size for a filesystem.
     *
     * exFAT re-reads a small metadata region thousands of times, so a 64 KiB line
     * is nearly free and pays back 90x on a cold directory listing. FAT32 at small
     * cluster sizes walks a large FAT sparsely, where the same readahead was pure
     * amplification and made cold listings 2-4x slower. Measured in
     * docs/IO_PERFORMANCE.md.
     */
    private fun readAheadFor(filesystemName: String): Int = when {
        filesystemName.contains("exFAT", ignoreCase = true) ->
            app.fayaz.otgmaster.block.CachedBlockDevice.DEFAULT_READAHEAD_BYTES
        filesystemName.startsWith("ext", ignoreCase = true) ->
            app.fayaz.otgmaster.block.CachedBlockDevice.DEFAULT_READAHEAD_BYTES
        else ->
            app.fayaz.otgmaster.block.CachedBlockDevice.FAT_READAHEAD_BYTES
    }
}

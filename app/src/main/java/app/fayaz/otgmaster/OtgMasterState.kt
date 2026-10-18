package app.fayaz.otgmaster

import me.jahnen.libaums.core.fs.FileSystem
import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.veracrypt.VolumeCandidate
import androidx.compose.runtime.mutableStateOf
import java.util.concurrent.CopyOnWriteArrayList

data class MountedDrive(
    val id: String,
    val name: String,
    val fileSystem: FileSystem,
    val blockDevice: RawBlockDevice?,
    /** Stable USB device identity (see UsbDeviceDescriber.stableKey) this drive was unlocked
     * from, used to avoid re-probing/re-mounting the same physical device while it's mounted. */
    val sourceDeviceName: String? = null,
    /** Human-readable USB device name (e.g. "Kingston DataTraveler") for display purposes. */
    val sourceDeviceDisplayName: String? = null,
    /** True for auto-mounted plain (unencrypted) drives — no unmount action shown. */
    val isPlain: Boolean = false,
    /** The raw USB block device shared across all partitions on the same physical drive.
     * Used by unmountDrive to close the USB connection only when ALL partitions are gone. */
    val rawBlockDevice: RawBlockDevice? = null,
    /** The specific VolumeCandidate this encrypted partition was unlocked from.
     * Restored to _deviceCandidates when the drive is unmounted so the user can remount. */
    val sourceVolumeCandidate: VolumeCandidate? = null,
    /** Partition label shown in the card header (e.g. "Partition 1", "Whole device"). */
    val partitionLabel: String = "",
    /** Detected filesystem name for the tag chip (e.g. "exFAT", "ext4", "FAT32"). */
    val filesystemName: String = "",
    /** The user chose read-only for this partition; see ReadOnlyBlockDeviceDriver. */
    val mountedReadOnly: Boolean = false,
    /** First block of the partition on the raw device, and its length; null when unknown. */
    val partitionStartBlock: Long? = null,
    val partitionBlockCount: Long? = null,
) {
    /**
     * Why the volume refuses writes, or null if it is writable. ext4 and NTFS can
     * refuse on their own (a journal needing recovery), and their reason takes precedence.
     */
    val readOnlyReason: String?
        get() = (fileSystem as? app.fayaz.otgmaster.ext4.Ext4FileSystem)?.readOnlyReason
            ?: (fileSystem as? app.fayaz.otgmaster.ntfs.NtfsFileSystem)?.readOnlyReason
            ?: (fileSystem as? app.fayaz.otgmaster.apfs.ApfsFileSystem)?.readOnlyReason
            ?: if (mountedReadOnly) app.fayaz.otgmaster.ext4.Ext4FileSystem.MOUNTED_READ_ONLY else null

    val isReadOnly: Boolean get() = readOnlyReason != null

    /**
     * The partition's identity across a re-plug, which a fresh mount does not keep in [id].
     * [sourceDeviceName] carries the serial (it is taken with permission held), so two sticks
     * of the same model do not share it; a stick with no serial falls back to its bus path,
     * which changes on re-plug, and then its history simply does not carry over.
     */
    val partitionKey: String
        get() = "${sourceDeviceName ?: id}#${partitionStartBlock ?: partitionLabel}"
}

/** A mounted partition's USB device went away without being unmounted (issue #50). */
data class DisconnectEvent(
    val atMillis: Long,
    /** Battery level at the moment of the drop, or null if the phone did not report it. */
    val batteryPercent: Int?,
    /**
     * The phone was plugged into power: a charger or a powered hub. Android shows apps
     * neither the hub nor the port's current, so this is the one sign the drive was not
     * running off the phone's battery.
     */
    val externalPower: Boolean,
)

/**
 * The card left behind by a partition that dropped while mounted. It holds no file system
 * or block device: those belong to a device that is gone, and touching them would be I/O
 * against a dead handle.
 */
data class DisconnectedDrive(
    val partitionKey: String,
    val title: String,
    val filesystemName: String,
    val containerType: app.fayaz.otgmaster.veracrypt.ContainerType?,
    val event: DisconnectEvent,
)

object OtgMasterState {
    val mountedDrives = CopyOnWriteArrayList<MountedDrive>()

    /**
     * Partitions that dropped while mounted, shown as cards until the same partition
     * mounts again or the user dismisses them. Process-wide rather than in the activity,
     * so a rotation does not lose them; nothing is persisted, so a restart does.
     */
    val disconnectedDrives = mutableStateOf<List<DisconnectedDrive>>(emptyList())

    /** Every unexpected disconnect this session, by [MountedDrive.partitionKey], newest first. */
    val disconnectHistory = mutableStateOf<Map<String, List<DisconnectEvent>>>(emptyMap())

    private const val HISTORY_PER_PARTITION = 10

    fun recordDisconnect(drive: DisconnectedDrive) {
        disconnectedDrives.value =
            disconnectedDrives.value.filterNot { it.partitionKey == drive.partitionKey } + drive
        val history = disconnectHistory.value
        disconnectHistory.value = history + (drive.partitionKey to
            (listOf(drive.event) + history[drive.partitionKey].orEmpty()).take(HISTORY_PER_PARTITION))
    }

    fun dismissDisconnected(partitionKey: String) {
        disconnectedDrives.value = disconnectedDrives.value.filterNot { it.partitionKey == partitionKey }
    }

    /** A partition mounting again closes its disconnected card; its history stays. */
    fun clearDisconnectedFor(mounted: Collection<MountedDrive>) {
        val keys = mounted.map { it.partitionKey }.toSet()
        if (disconnectedDrives.value.any { it.partitionKey in keys }) {
            disconnectedDrives.value = disconnectedDrives.value.filterNot { it.partitionKey in keys }
        }
    }

    /**
     * The recent log lines, mirrored here so another activity can read them.
     *
     * MainActivity keeps the list the UI renders; this is the same content, process
     * wide, for the report screen. Bounded to the same 50 lines: it exists to be
     * attached to a bug report, not to be a history.
     */
    private val logHistory = CopyOnWriteArrayList<String>()

    fun recordLog(line: String) {
        if (logHistory.size >= 50) logHistory.removeAt(0)
        logHistory.add(line)
    }

    fun recentLogs(): List<String> = logHistory.toList()

    /**
     * True while a benchmark is running, so whichever screen is in front can hold the
     * display on.
     *
     * The screen going off is not a cosmetic problem: the CPU throttles, decryption is
     * CPU-bound, and the block layer measured 7.27 MB/s asleep against 17.26 awake. A
     * dozed run reads as a regression, which is how one was first misread. The bench
     * screen holds the flag itself, but MainActivity can end up in front — the mount
     * pre-flight brings it forward — and it needs to hold it too.
     */
    val benchmarkRunning = mutableStateOf(false)

    fun clearLogHistory() = logHistory.clear()

    /**
     * Optional sink for user-visible log lines, installed by MainActivity while it
     * is alive.
     *
     * Lets components with no activity reference — a manifest-declared receiver, a
     * background worker — surface progress in the app's log pane. The sink is
     * responsible for hopping to the main thread; callers may be on any thread.
     */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    fun log(line: String) {
        logSink?.invoke(line)
    }

    /**
     * Unlocks and mounts every currently-probed candidate with these credentials,
     * bypassing the UI form.
     *
     * Installed by MainActivity while it is alive. Exists so callers without an
     * activity reference can mount — test automation today, and the planned
     * backup service, which has to mount with no user present.
     */
    fun interface MountRequest {
        /**
         * @param readOnly overrides the user's "read-only by default" choice for the
         *   encrypted partitions this mounts; null keeps it. The benchmark passes false
         *   when it means to write: on a phone set to read-only by default, a run's
         *   write sections otherwise all failed against read-only mounts.
         */
        fun mount(password: String, pim: Int?, cipherName: String, hashName: String, readOnly: Boolean?)
    }

    @Volatile
    var mountRequest: MountRequest? = null

    /**
     * Unlocks a [MountRequest] has started and not yet finished, successfully or not.
     * Unlocks run one at a time and a key derivation can take many seconds, so the
     * number of mounted drives going quiet for a while does not mean they are done.
     */
    val unlocksInFlight = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Unmounts started and not yet finished, including the bookkeeping that puts an
     * encrypted partition back on the candidate list. That runs after the device lock,
     * so it can trail the mount list emptying by more than a minute; a mount request
     * made in between saw none of the partitions still waiting (run 5, Drive 2).
     */
    val unmountsInFlight = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * While true, unmounting a drive's last partition keeps its USB connection open
     * instead of releasing it. Set by the debug benchmark around its own unmount and
     * remount: a released stick is taken by Android's own storage stack within about a
     * second, which runs fsck on it and mounts its partitions read-write, and the app
     * then pulls it back out from under those mounts. Seen on the OnePlus 7, where
     * vold mounted four partitions of a device-matrix drive mid-run.
     */
    @Volatile
    var holdConnections = false

    /** Unmounts every drive. Installed by MainActivity while it is alive. */
    @Volatile
    var unmountAllRequest: (() -> Unit)? = null

    /**
     * Handles the USB device with this key going away, as Android's detach broadcast
     * would. For the E2E suite: the emulator's drive is read through /dev/block/sda, not
     * as a UsbDevice, so when QEMU removes it Android sends no broadcast at all.
     * Installed by MainActivity while it is alive.
     */
    @Volatile
    var detachRequest: ((deviceKey: String) -> Unit)? = null

    /**
     * Block-cache configuration applied at mount time; null uses the
     * per-filesystem default.
     *
     * Exposed so the cache can be A/B'd within a single session. Comparing across
     * sessions proved unreliable — the block-layer control measured 22.03 MB/s in
     * one session and 12.94 MB/s in another with identical code, which is larger
     * than the effect being measured.
     */
    data class CacheConfig(val enabled: Boolean = true, val readAheadBytes: Int? = null)

    @Volatile
    var cacheConfig: CacheConfig? = null
    
    fun getDrive(id: String): MountedDrive? {
        return mountedDrives.find { it.id == id }
    }
    
    /**
     * Told the mounted list after every change, in mount order. MainActivity sets it to
     * keep MountedDriveService in step; nothing else changes the list.
     */
    @Volatile
    var onMountedChanged: ((List<MountedDrive>) -> Unit)? = null

    fun addDrive(drive: MountedDrive) {
        mountedDrives.add(drive)
        onMountedChanged?.invoke(mountedDrives.toList())
    }
    
    fun removeDrive(id: String) {
        mountedDrives.removeIf { it.id == id }
        onMountedChanged?.invoke(mountedDrives.toList())
    }
}

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
    val filesystemName: String = ""
) {
    /** Why the volume refuses writes, or null if it is writable. Only ext4 can refuse today. */
    val readOnlyReason: String?
        get() = (fileSystem as? app.fayaz.otgmaster.ext4.Ext4FileSystem)?.readOnlyReason

    val isReadOnly: Boolean get() = readOnlyReason != null
}

object OtgMasterState {
    val mountedDrives = CopyOnWriteArrayList<MountedDrive>()

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
        fun mount(password: String, pim: Int?, cipherName: String, hashName: String)
    }

    @Volatile
    var mountRequest: MountRequest? = null

    /** Unmounts every drive. Installed by MainActivity while it is alive. */
    @Volatile
    var unmountAllRequest: (() -> Unit)? = null

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
    
    fun addDrive(drive: MountedDrive) {
        mountedDrives.add(drive)
    }
    
    fun removeDrive(id: String) {
        mountedDrives.removeIf { it.id == id }
    }
}

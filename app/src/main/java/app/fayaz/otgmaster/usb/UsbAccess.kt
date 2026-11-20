package app.fayaz.otgmaster.usb

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager

/** When the app takes a USB drive that has just appeared; the USB access setting. */
enum class UsbAccessMode(val prefValue: String) {
    /** Only when the user taps Scan USB devices. */
    MANUAL("manual"),

    /** As soon as it appears, whatever Android is doing with it. The default. */
    ALWAYS("always"),

    /** After Android has had time to mount it, and only if Android did not. */
    WAIT_FOR_ANDROID("wait");

    companion object {
        fun fromPref(value: String?): UsbAccessMode = entries.firstOrNull { it.prefValue == value } ?: ALWAYS
    }
}

object UsbAccess {
    /**
     * How long Android gets before Wait for Android opens a drive it has not mounted. A released
     * stick has been seen mounted by Android within about a second, on a OnePlus 7; a large FAT32
     * volume is checked first, and that shows up here as a volume in the checking state.
     */
    const val ANDROID_WAIT_MS = 8_000L

    enum class Decision { OPEN, WAIT, LEAVE_UNTIL_SCAN, LEFT_WITH_ANDROID }

    fun decide(mode: UsbAccessMode, userScan: Boolean, waitedMs: Long, androidHolds: () -> Boolean): Decision = when {
        userScan || mode == UsbAccessMode.ALWAYS -> Decision.OPEN
        mode == UsbAccessMode.MANUAL -> Decision.LEAVE_UNTIL_SCAN
        androidHolds() -> Decision.LEFT_WITH_ANDROID
        waitedMs < ANDROID_WAIT_MS -> Decision.WAIT
        else -> Decision.OPEN
    }

    /**
     * Android offers no public way to tell which UsbDevice a StorageVolume is on, and it does not
     * name a volume as USB rather than SD card. So a volume counts as a USB drive's if it is not
     * one of [known], the removable volumes seen while no USB drive was attached. With several
     * drives attached this cannot say which one Android took, so all count as taken. A wrong
     * answer leaves a drive unopened until Scan, which is the safe direction.
     */
    fun androidHolds(current: Set<String>, known: Set<String>): Boolean = (current - known).isNotEmpty()

    /** The removable volumes Android has mounted or is checking. */
    fun removableVolumes(context: Context): Set<String> =
        context.getSystemService(StorageManager::class.java).storageVolumes
            .filter {
                it.isRemovable && it.state in setOf(
                    Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY, Environment.MEDIA_CHECKING,
                )
            }
            .map { it.uuid ?: it.getDescription(context) }
            .toSet()

    /**
     * How long after the app takes a drive a volume Android loses still counts as taken from it.
     * The loss follows within a second (the kernel drops the disk when the app claims the USB
     * interface); the margin covers a slow vold.
     */
    const val TAKEOVER_WINDOW_MS = 15_000L

    /**
     * Whether a removable volume reaching [state] [msSinceTakeover] after the app took a drive
     * means the app cut Android off mid-use. vold reports "bad_removal" for a volume that
     * vanished while mounted, and "unmountable" for one whose check failed because the disk went
     * away. A volume Android had not started on goes to "removed", which is not reported.
     */
    fun cutOffByTakeover(state: String?, removable: Boolean, msSinceTakeover: Long?): Boolean =
        removable && msSinceTakeover != null && msSinceTakeover in 0..TAKEOVER_WINDOW_MS &&
            state in CUT_OFF_STATES

    // Environment.MEDIA_BAD_REMOVAL and MEDIA_UNMOUNTABLE, spelt out so host tests can use them.
    private val CUT_OFF_STATES = setOf("bad_removal", "unmountable")

    /** The removable volumes Android is using that it was not before a USB drive came: their names. */
    fun androidVolumeNames(context: Context, known: Set<String>): List<String> =
        context.getSystemService(StorageManager::class.java).storageVolumes
            .filter {
                it.isRemovable && it.state in setOf(
                    Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY, Environment.MEDIA_CHECKING,
                ) && (it.uuid ?: it.getDescription(context)) !in known
            }
            .map { it.getDescription(context) }

    /** The message after an Eject: what Android will do next depends on what it can read. */
    fun androidCanRead(partitions: List<EjectedPartition>): Boolean =
        partitions.isNotEmpty() && partitions.all { it.plain && it.filesystem in ANDROID_FILESYSTEMS }

    private val ANDROID_FILESYSTEMS = setOf("FAT32", "FAT16", "FAT12", "exFAT")
}

data class EjectedPartition(val plain: Boolean, val filesystem: String)

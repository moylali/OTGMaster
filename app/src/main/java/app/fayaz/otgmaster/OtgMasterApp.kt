package app.fayaz.otgmaster

import android.app.Application
import app.fayaz.otgmaster.feedback.Feedback
import app.fayaz.otgmaster.feedback.FeedbackSource
import app.fayaz.otgmaster.veracrypt.ContainerType

/**
 * Process-wide wiring that must exist before any activity: Report issue's source (the screen
 * is shared with Container Master and can be restored by Android without MainActivity).
 */
class OtgMasterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Feedback.source = OtgFeedbackSource(this)
        app.fayaz.otgmaster.engine.MountCoordinator.init(this)
    }
}

/** OTG Master's Report issue: its mounted drives, its log and USB events, its theme. */
class OtgFeedbackSource(private val app: Application) : FeedbackSource {
    override val appVersion: String get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    override fun usbMake(): String = OtgMasterState.mountedDrives.firstOrNull()?.sourceDeviceDisplayName ?: ""

    override fun volumes(): List<FeedbackSource.Volume> = OtgMasterState.mountedDrives.toList().map { d ->
        val encType = when (d.sourceVolumeCandidate?.containerType) {
            ContainerType.LUKS1 -> "luks1"
            ContainerType.LUKS2 -> "luks2"
            ContainerType.VERACRYPT -> "veracrypt"
            ContainerType.BITLOCKER -> "bitlocker"
            ContainerType.APFS -> "apfs"
            else -> if (d.isPlain) "none" else "veracrypt"
        }
        FeedbackSource.Volume(
            capacityBytes = runCatching { d.fileSystem.capacity }.getOrNull(),
            fileSystem = d.filesystemName,
            encryptionType = encType,
        )
    }

    override fun logs(): List<String> = OtgMasterState.recentLogs()
    override fun usbEvents(): List<String> = OtgMasterState.usbEvents()
    override fun themeMode(): String =
        app.getSharedPreferences("otgmaster_prefs", android.content.Context.MODE_PRIVATE).getString("theme_mode", "SYSTEM") ?: "SYSTEM"
}

package app.fayaz.containermaster

import android.app.Application
import android.content.Context
import app.fayaz.otgmaster.core.EngineLog
import app.fayaz.otgmaster.feedback.Feedback
import app.fayaz.otgmaster.feedback.FeedbackSource
import app.fayaz.otgmaster.provider.TreeDocumentsProvider

/** Process-wide wiring, before any activity: the engine's log, Report issue, the registry. */
class ContainerMasterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EngineLog.install(object : EngineLog.Sink {
            override fun log(line: String) = CmLog.log(line)
            override fun logFailure(kind: String, what: String, e: Throwable) =
                CmLog.log("$what: ${e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName}")
        })
        TreeDocumentsProvider.mounts = ContainerSessions
        ContainerSessions.appContext = this
        ContainerSessions.idleMinutes = prefs(this).getInt(IDLE_MINUTES, 15)
        TreeDocumentsProvider.activityHook = { id -> ContainerSessions.touch(id) }
        Feedback.source = object : FeedbackSource {
            override val appVersion: String get() = "Container Master ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            override val usesUsb: Boolean get() = false
            override fun usbMake(): String = ""
            override fun volumes(): List<FeedbackSource.Volume> = ContainerSessions.open.value.map {
                FeedbackSource.Volume(it.capacityBytes, it.fileSystemName, if (it.format == "ZIP") "none" else "veracrypt")
            }
            override fun logs(): List<String> = CmLog.lines.value
            override fun themeMode(): String = prefs(this@ContainerMasterApp).getString(THEME, "SYSTEM") ?: "SYSTEM"
        }
    }

    companion object {
        const val THEME = "theme_mode"
        const val IDLE_MINUTES = "idle_minutes"
        const val LOCK_ON_SCREEN_OFF = "lock_on_screen_off"
        fun prefs(c: Context) = c.getSharedPreferences("containermaster_prefs", Context.MODE_PRIVATE)
    }
}

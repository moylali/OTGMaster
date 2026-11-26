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
        // This class runs in both processes (Milestone 2b). The UI process ("…:ui") only needs
        // Report issue's source, which asks the engine; everything else is the engine's.
        if (processName().endsWith(":ui")) {
            Feedback.source = UiFeedbackSource(this)
            return
        }
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
                FeedbackSource.Volume(it.capacityBytes, it.fileSystemName, when { it.format == "ZIP" || it.format == "ISO" -> "none"; it.format.startsWith("LUKS") -> it.format.lowercase(); it.format == "AES Crypt" -> "aescrypt"; else -> "veracrypt" })
            }
            override fun logs(): List<String> = CmLog.lines.value
            override fun themeMode(): String = prefs(this@ContainerMasterApp).getString(THEME, "SYSTEM") ?: "SYSTEM"
        }
    }

    private fun processName(): String =
        if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName()
        else runCatching { java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrDefault(packageName)

    /** Report issue in the UI process: the engine's open containers and log, read through EngineClient. */
    private class UiFeedbackSource(private val app: Application) : FeedbackSource {
        private val engine = EngineClient(app)
        override val appVersion: String get() = "Container Master ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        override val usesUsb: Boolean get() = false
        override fun usbMake(): String = ""
        override fun volumes(): List<FeedbackSource.Volume> = runCatching { engine.state().open }.getOrDefault(emptyList()).map {
            FeedbackSource.Volume(null, it.fileSystem, when { it.format == "ZIP" || it.format == "ISO" -> "none"
                it.format.startsWith("LUKS") -> it.format.lowercase(); it.format == "AES Crypt" -> "aescrypt"; else -> "veracrypt" })
        }
        override fun logs(): List<String> = runCatching { engine.state().logs }.getOrDefault(emptyList())
        override fun themeMode(): String = prefs(app).getString(THEME, "SYSTEM") ?: "SYSTEM"
    }

    companion object {
        const val THEME = "theme_mode"
        const val IDLE_MINUTES = "idle_minutes"
        const val LOCK_ON_SCREEN_OFF = "lock_on_screen_off"
        /** Settings, Remember passwords: save a container's password after it opens. Off by default. */
        const val REMEMBER_PASSWORDS = "remember_passwords"
        /** Settings, Read-only mode: on by default; off opens VeraCrypt and LUKS files for writing. */
        const val READ_ONLY = "read_only_mode"
        fun prefs(c: Context) = c.getSharedPreferences("containermaster_prefs", Context.MODE_PRIVATE)
    }
}

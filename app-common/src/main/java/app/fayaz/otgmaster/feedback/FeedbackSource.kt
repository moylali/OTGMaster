package app.fayaz.otgmaster.feedback

/**
 * What the app hosting Report issue supplies to it: OTG Master and Container Master share
 * [FeedbackActivity] and [FeedbackPayload] (app-common), and each installs its own source in
 * its Application, so the screen works even when Android restores it on its own.
 */
interface FeedbackSource {
    /** As the report shows it, e.g. "0.5.5 (54)" or "Container Master 0.1.0 (1)". */
    val appVersion: String

    /** The name of the USB drive the first volume is on, or "" (Container Master has none). */
    fun usbMake(): String

    /** Whether the app opens USB drives at all, so the screen offers the USB row. */
    val usesUsb: Boolean get() = true

    /** The open volumes, described without labels, serials or names. */
    fun volumes(): List<Volume>

    /** The recent log, oldest first; lines carrying names are marked for [PathMask]. */
    fun logs(): List<String>

    /** USB attach and permission lines kept beyond the recent log; empty without USB. */
    fun usbEvents(): List<String> = emptyList()

    /** "LIGHT", "DARK" or "SYSTEM": the app's theme choice, which the screen follows. */
    fun themeMode(): String = "SYSTEM"

    data class Volume(
        /** Capacity in bytes, or null when unknown. */
        val capacityBytes: Long?,
        val fileSystem: String,
        /** Lowercase: "veracrypt", "luks1", "luks2", "bitlocker", "apfs", or "none". */
        val encryptionType: String,
    )
}

/** The installed source; each app sets it once in Application.onCreate. */
object Feedback {
    @Volatile
    var source: FeedbackSource? = null
}

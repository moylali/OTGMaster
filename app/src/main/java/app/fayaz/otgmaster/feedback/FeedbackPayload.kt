package app.fayaz.otgmaster.feedback

import android.os.Build
import app.fayaz.otgmaster.BuildConfig
import app.fayaz.otgmaster.MountedDrive
import app.fayaz.otgmaster.veracrypt.ContainerType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the payload the feedback form accepts, and the URL that carries it.
 *
 * The form (github.com/moylali/OTGMaster-Feedback) reads a `payload` query
 * parameter, base64-decodes it as UTF-8 and parses it as JSON, pre-filling its
 * fields from these keys. Nothing is sent anywhere by this class — it only produces
 * a string for the user to review and a URL to hand to a browser, so submission
 * remains the user's deliberate act on the form itself.
 */
object FeedbackPayload {

    /** Everything that would leave the device, as the user will see it. */
    data class Contents(
        val deviceName: String,
        val appVersion: String,
        val usbMake: String,
        val usbSize: String,
        val partitions: List<Partition>,
        val logLines: List<String>,
        /** Log lines dropped to keep the URL within [MAX_URL_CHARS]. */
        val logLinesDropped: Int,
    )

    data class Partition(
        val sizeGb: String,
        val fileSystem: String,
        val encrypted: Boolean,
        /** Lowercase encryption type: "veracrypt", "luks1", "luks2", or "none". */
        val encryptionType: String,
    )

    /**
     * Which categories the user has agreed to include.
     *
     * Per-category rather than one consent for everything, because the categories
     * carry different risk: an app version is innocuous, a drive's make and capacity
     * narrows it down, and the log names USB devices and mount events. Someone
     * willing to share the first may reasonably refuse the last, and a single
     * checkbox forces them to refuse everything or accept everything.
     */
    data class Selection(
        val device: Boolean = true,
        val appVersion: Boolean = true,
        val usb: Boolean = true,
        val partitions: Boolean = true,
        val logs: Boolean = true,
    ) {
        val nothing: Boolean get() = !device && !appVersion && !usb && !partitions && !logs

        companion object {
            /** Everything off — what "Don't include anything" selects. */
            val NONE = Selection(false, false, false, false, false)
        }
    }

    /**
     * Collects what the form asks for, and nothing else.
     *
     * Deliberately excluded: the volume label, the USB serial number, and any file or
     * directory name. A label is often chosen by the user and a serial identifies the
     * physical drive, so neither belongs in a report described as anonymous. What
     * remains is make, capacity, filesystem and whether the volume is encrypted —
     * enough to reproduce a storage bug without identifying the drive or its owner.
     */
    fun collect(drives: List<MountedDrive>, logs: List<String>): Contents {
        val first = drives.firstOrNull()
        val partitions = drives.map { d ->
            val encType = when (d.sourceVolumeCandidate?.containerType) {
                ContainerType.LUKS1    -> "luks1"
                ContainerType.LUKS2    -> "luks2"
                ContainerType.VERACRYPT -> "veracrypt"
                else -> if (d.isPlain) "none" else "veracrypt"
            }
            Partition(
                sizeGb = runCatching { "%.1f".format(d.fileSystem.capacity / 1_000_000_000.0) }
                    .getOrDefault("?"),
                fileSystem = d.filesystemName.ifEmpty { "unknown" },
                encrypted = encType != "none",
                encryptionType = encType,
            )
        }
        val (kept, dropped) = trimLogs(logs)
        return Contents(
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            usbMake = first?.sourceDeviceDisplayName ?: "",
            usbSize = partitions.firstOrNull()?.sizeGb?.let { "$it GB" } ?: "",
            partitions = partitions,
            logLines = kept,
            logLinesDropped = dropped,
        )
    }

    private fun MountedDrive.uncachedIsEncrypted(): Boolean {
        val cached = blockDevice as? app.fayaz.otgmaster.block.CachedBlockDevice ?: return false
        return cached.uncached.javaClass.simpleName == "NativeDecryptedBlockDevice"
    }

    /**
     * Keeps the most recent log lines that fit, and reports how many were dropped.
     *
     * The tail is what matters: a report is written just after something went wrong,
     * so the last lines describe it. Dropping from the front also means the count of
     * omitted lines is worth showing — silently truncating would let someone believe
     * they had sent a complete log.
     */
    private fun trimLogs(logs: List<String>): Pair<List<String>, Int> {
        val kept = ArrayDeque<String>()
        var budget = MAX_LOG_CHARS
        for (line in logs.asReversed()) {
            val cost = line.length + 1
            if (budget - cost < 0) break
            budget -= cost
            kept.addFirst(line)
        }
        return kept.toList() to (logs.size - kept.size)
    }

    /**
     * Omits the drive fields entirely when nothing is mounted, rather than sending
     * empty strings.
     *
     * Feedback is often written precisely because a drive would not mount, so no-USB
     * is a normal case and not an edge one. The form skips falsy values, so empty
     * strings would work — but an absent key states "not applicable" where an empty
     * one states "blank", and only the first is true.
     */
    /**
     * Builds Contents from logs alone, for tests that need the real trimming without
     * an Android Build or a mounted drive.
     */
    internal fun collectForTest(logs: List<String>): Contents {
        val (kept, dropped) = trimLogs(logs)
        return Contents(
            deviceName = "TestCo TestPhone (Android 99)",
            appVersion = "0.0.0 (0)",
            usbMake = "Test USB Drive",
            usbSize = "62.0 GB",
            partitions = listOf(Partition("62.0", "exFAT", true, "veracrypt")),
            logLines = kept,
            logLinesDropped = dropped,
        )
    }

    fun toJson(c: Contents, sel: Selection = Selection()): String = JSONObject().apply {
        if (sel.device) put("deviceName", c.deviceName)
        if (sel.appVersion) put("appVersion", c.appVersion)
        if (sel.logs && c.logLines.isNotEmpty()) put("deviceLogs", c.logLines.joinToString("\n"))
        if (sel.usb && c.usbMake.isNotBlank()) put("usbMake", c.usbMake)
        if (sel.usb && c.usbSize.isNotBlank()) put("usbSize", c.usbSize)
        if (sel.partitions && c.partitions.isNotEmpty()) {
            put("partitionCount", c.partitions.size)
            put("partitionsData", JSONArray().apply {
                c.partitions.forEach {
                    put(JSONObject().apply {
                        put("size_gb", it.sizeGb)
                        put("file_system", it.fileSystem)
                        put("encrypted", it.encrypted)
                        put("encryption_type", it.encryptionType)
                    })
                }
            })
        }
    }.toString()

    /**
     * The URL to open, with the payload base64-encoded in the `payload` parameter.
     *
     * Standard base64 rather than the URL-safe alphabet, because the form decodes with
     * `atob`, which rejects `-` and `_`. That makes percent-encoding mandatory: base64
     * contains `+`, and a `+` in a query string decodes to a space — which would
     * corrupt a large fraction of payloads, silently and only sometimes.
     *
     * java.util.Base64 and java.net.URLEncoder rather than the android.* equivalents,
     * which are unimplemented stubs under plain unit tests. Both exist from API 26,
     * which is this project's minSdk.
     */
    fun url(base: String, c: Contents, sel: Selection = Selection()): String {
        // Nothing selected means a bare form: no parameter at all rather than an
        // encoded empty object, so the link carries no data even in a browser history
        // or a proxy log.
        if (sel.nothing) return base
        val b64 = java.util.Base64.getEncoder()
            .encodeToString(toJson(c, sel).toByteArray(Charsets.UTF_8))
        return "$base?payload=${java.net.URLEncoder.encode(b64, "UTF-8")}"
    }

    /** What the user reads before deciding. Mirrors the payload exactly. */
    /** What the user reads before deciding. Shows exactly what [sel] would send. */
    fun summary(c: Contents, sel: Selection = Selection()): String = buildString {
        if (sel.nothing) {
            appendLine("Nothing will be shared.")
            appendLine()
            appendLine("The form opens empty. You can still describe the problem there")
            appendLine("in your own words.")
            return@buildString
        }
        if (sel.device) appendLine("Device: ${c.deviceName}")
        if (sel.appVersion) appendLine("App version: ${c.appVersion}")
        if (sel.usb) {
            if (c.usbMake.isNotBlank()) appendLine("USB drive: ${c.usbMake}")
            if (c.usbSize.isNotBlank()) appendLine("Capacity: ${c.usbSize}")
            if (c.usbMake.isBlank() && c.usbSize.isBlank()) {
                appendLine("USB drive: none connected — nothing to share")
            }
        }
        if (sel.partitions) {
            if (c.partitions.isEmpty()) {
                appendLine("Partitions: none mounted — nothing to share")
            } else {
                appendLine("Partitions: ${c.partitions.size}")
                c.partitions.forEachIndexed { i, p ->
                    val encLabel = when (p.encryptionType) {
                        "luks1"     -> "LUKS1"
                        "luks2"     -> "LUKS2"
                        "veracrypt" -> "VeraCrypt"
                        else        -> "not encrypted"
                    }
                    appendLine("  ${i + 1}. ${p.sizeGb} GB, ${p.fileSystem}, $encLabel")
                }
            }
        }
        if (sel.logs) {
            appendLine()
            if (c.logLines.isEmpty()) {
                appendLine("Log: empty")
            } else {
                append("Log: ${c.logLines.size} line(s)")
                if (c.logLinesDropped > 0) {
                    append(" — ${c.logLinesDropped} older line(s) left out to fit the link")
                }
                appendLine()
                c.logLines.forEach { appendLine("  $it") }
            }
        }
    }

    /**
     * Budget for the whole URL. Cloudflare and most intermediaries accept well beyond
     * 8 KB, but a request line that long is rejected by enough proxies to be a poor
     * bet, and base64 inflates by a third.
     */
    private const val MAX_URL_CHARS = 8_000

    /**
     * Log budget in characters, before base64 and percent-encoding.
     *
     * 8000 URL chars, less roughly 1,000 for the base and the other fields once
     * encoded, leaves about 7,000 of base64 — which is about 5,200 bytes of JSON.
     * 3,500 for the log leaves comfortable room for the device, USB and partition
     * fields and for percent-encoding expanding `+` and `/` threefold.
     */
    private const val MAX_LOG_CHARS = 3_500
}

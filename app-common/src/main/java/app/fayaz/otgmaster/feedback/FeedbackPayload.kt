package app.fayaz.otgmaster.feedback

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the payload the feedback form accepts, and the URL that carries it.
 *
 * The form (github.com/moylali/OTGMaster-Feedback) reads a `payload` fragment
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
        /**
         * The log (up to [MAX_LOG_CHARS]), sent in the URL fragment as `logs`, on its
         * own. It is not in `payload`: the form has read `logs` since moylali/
         * OTGMaster-Feedback ee2f6b8 (11-07).
         */
        val logLines: List<String>,
        /** Older log lines left out to stay within [MAX_LOG_CHARS]. */
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
     * directory name (the log's are masked: [PathMask]). A label is often chosen by the user and a serial identifies the
     * physical drive, so neither belongs in a report described as anonymous. What
     * remains is make, capacity, filesystem and whether the volume is encrypted —
     * enough to reproduce a storage bug without identifying the drive or its owner.
     */
    fun collect(source: FeedbackSource): Contents {
        val partitions = source.volumes().map { v ->
            Partition(
                sizeGb = v.capacityBytes?.let { "%.1f".format(it / 1_000_000_000.0) } ?: "?",
                fileSystem = v.fileSystem.ifEmpty { "unknown" },
                encrypted = v.encryptionType != "none",
                encryptionType = v.encryptionType,
            )
        }
        val (kept, dropped) = trimLogs(source.logs().map(PathMask::maskMarked), source.usbEvents().map(PathMask::maskMarked))
        return Contents(
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
            appVersion = source.appVersion,
            usbMake = source.usbMake(),
            usbSize = partitions.firstOrNull()?.sizeGb?.let { "$it GB" } ?: "",
            partitions = partitions,
            logLines = kept,
            logLinesDropped = dropped,
        )
    }

    /**
     * Keeps the most recent log lines that fit, and reports how many were dropped.
     *
     * The tail is what matters: a report is written just after something went wrong,
     * so the last lines describe it. Dropping from the front also means the count of
     * omitted lines is worth showing — silently truncating would let someone believe
     * they had sent a complete log.
     */
    private fun trimLogs(
        logs: List<String>,
        usbEvents: List<String> = emptyList(),
        maxChars: Int = MAX_LOG_CHARS,
        maxUsbChars: Int = MAX_USB_CHARS,
    ): Pair<List<String>, Int> {
        // USB lines that have already scrolled out of the recent log go first, with a
        // budget of their own (OtgMasterState.usbEvents says why), newest kept.
        val earlier = ArrayDeque<String>()
        var usbBudget = maxUsbChars
        for (line in usbEvents.filter { it !in logs }.asReversed()) {
            val cost = line.length + 1
            if (usbBudget - cost < 0) break
            usbBudget -= cost
            earlier.addFirst(line)
        }
        val header = if (earlier.isEmpty()) emptyList()
            else listOf(EARLIER_USB_HEADER) + earlier + RECENT_LOG_HEADER
        val kept = ArrayDeque<String>()
        var budget = maxChars - header.sumOf { it.length + 1 }
        for (line in logs.asReversed()) {
            val cost = line.length + 1
            if (budget - cost < 0) break
            budget -= cost
            kept.addFirst(line)
        }
        return header + kept.toList() to (logs.size - kept.size)
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
    internal fun collectForTest(logs: List<String>, usbEvents: List<String> = emptyList()): Contents {
        val (kept, dropped) = trimLogs(logs.map(PathMask::maskMarked), usbEvents.map(PathMask::maskMarked))
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
     * The URL to open, with the payload base64-encoded in the `payload` parameter of
     * the fragment, and the whole log in `logs` beside it.
     *
     * The fragment, not the query: a browser never sends the fragment to a server or
     * a proxy, so nothing reaches the form's worker until the user submits, which is
     * what the screen before it promises. In the query the payload was in the request
     * that loads the page (#165). Forms from before 0.5.4 read only the query; the form
     * reads the fragment first and the query after.
     *
     * Standard base64 rather than the URL-safe alphabet, because the form decodes with
     * `atob`, which rejects `-` and `_`. That makes percent-encoding mandatory: base64
     * contains `+`, and a `+` in a URLSearchParams value decodes to a space — which
     * would corrupt a large fraction of payloads, silently and only sometimes.
     *
     * java.util.Base64 and java.net.URLEncoder rather than the android.* equivalents,
     * which are unimplemented stubs under plain unit tests. Both exist from API 26,
     * which is this project's minSdk.
     */
    fun url(base: String, c: Contents, sel: Selection = Selection()): String {
        // Nothing selected means a bare form: no parameter at all rather than an
        // encoded empty object, so the link carries no data even in a browser history.
        if (sel.nothing) return base
        val enc = java.util.Base64.getEncoder()
        val b64 = enc.encodeToString(toJson(c, sel).toByteArray(Charsets.UTF_8))
        val url = "$base#payload=${java.net.URLEncoder.encode(b64, "UTF-8")}"
        if (!sel.logs || c.logLines.isEmpty()) return url
        val logs = enc.encodeToString(c.logLines.joinToString("\n").toByteArray(Charsets.UTF_8))
        return "$url&logs=${java.net.URLEncoder.encode(logs, "UTF-8")}"
    }

    /** What the user reads before deciding. Mirrors the payload exactly. */
    /** What the user reads before deciding. Shows exactly what [sel] would send. */
    /**
     * [c] with its log replaced by what the user left in the editor, so the lines sent
     * are exactly those. A log the user emptied sends no `logs` parameter at all.
     */
    fun withEditedLog(c: Contents, text: String): Contents =
        c.copy(logLines = text.split('\n').map { it.trimEnd('\r') }.dropLastWhile { it.isBlank() })

    /**
     * What the user reads before deciding. With [logText] false the log is counted, not
     * listed: the screen shows it in its own editor, and listing it twice would hide
     * that the editor is what gets sent.
     */
    fun summary(c: Contents, sel: Selection = Selection(), logText: Boolean = true): String = buildString {
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
                appendLine("USB drive: none connected, so nothing to share")
            }
        }
        if (sel.partitions) {
            if (c.partitions.isEmpty()) {
                appendLine("Partitions: none mounted, so nothing to share")
            } else {
                appendLine("Partitions: ${c.partitions.size}")
                c.partitions.forEachIndexed { i, p ->
                    val encLabel = when (p.encryptionType) {
                        "luks1"     -> "LUKS1"
                        "luks2"     -> "LUKS2"
                        "veracrypt" -> "VeraCrypt"
                        "bitlocker" -> "BitLocker"
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
                appendLine("Log: ${c.logLines.size} line(s)" + if (logText) "" else ", as shown below")
                if (logText) c.logLines.forEach { appendLine("  $it") }
            }
        }
    }


    /**
     * The log, in the fragment: 200 lines at a generous 200 characters each, with room
     * for the USB lines that scrolled out; well under GitHub's 65,536-character issue
     * body, which is where the form puts it. A fragment is never sent to a server, so
     * no proxy limit applies.
     */
    private const val MAX_LOG_CHARS = 40_000
    /** Of [MAX_LOG_CHARS], at most this much for USB lines older than the recent log. */
    private const val MAX_USB_CHARS = 8_000
    internal const val EARLIER_USB_HEADER = "--- earlier USB events ---"
    internal const val RECENT_LOG_HEADER = "--- recent log ---"
}

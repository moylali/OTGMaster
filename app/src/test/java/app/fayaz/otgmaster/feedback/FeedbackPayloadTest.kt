package app.fayaz.otgmaster.feedback

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class FeedbackPayloadTest {

    private fun contents(
        logLines: List<String> = listOf("line one", "line two"),
        dropped: Int = 0,
        partitions: List<FeedbackPayload.Partition> = listOf(
            FeedbackPayload.Partition("62.0", "exFAT", true)
        ),
        usbMake: String = "PNY USB 3.2.1 FD",
        usbSize: String = "62.0 GB",
    ) = FeedbackPayload.Contents(
        deviceName = "Google Pixel 10 Pro XL (Android 17)",
        appVersion = "0.3.11 (44)",
        usbMake = usbMake,
        usbSize = usbSize,
        partitions = partitions,
        logLines = logLines,
        logLinesDropped = dropped,
    )

    /** The parameter must survive the trip the form will take it on. */
    private fun decodePayload(url: String): JSONObject {
        val raw = url.substringAfter("payload=")
        val b64 = URLDecoder.decode(raw, "UTF-8")
        return JSONObject(String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8))
    }

    @Test
    fun urlRoundTripsThroughBase64AndPercentEncoding() {
        val url = FeedbackPayload.url("https://example.test/", contents())
        val json = decodePayload(url)
        assertEquals("Google Pixel 10 Pro XL (Android 17)", json.getString("deviceName"))
        assertEquals("0.3.11 (44)", json.getString("appVersion"))
        assertEquals("line one\nline two", json.getString("deviceLogs"))
        assertEquals(1, json.getInt("partitionCount"))
    }

    /**
     * A raw `+` in a query string decodes to a space, which would corrupt the base64
     * and break the payload — intermittently, since it depends on the bytes.
     */
    @Test
    fun plusIsPercentEncodedNotLeftRaw() {
        // A long random-ish log makes a '+' in the base64 overwhelmingly likely.
        val noisy = (1..40).map { "event $it: ÿþ mount ✓ $it" }
        val url = FeedbackPayload.url("https://example.test/", contents(logLines = noisy))
        val param = url.substringAfter("payload=")
        assertFalse("raw '+' would decode to a space", param.contains('+'))
        // And it still decodes, which is the point of encoding it.
        assertTrue(decodePayload(url).getString("deviceLogs").contains("event 40"))
    }

    @Test
    fun nothingSelectedYieldsABareUrlWithNoParameter() {
        val url = FeedbackPayload.url(
            "https://example.test/", contents(), FeedbackPayload.Selection.NONE
        )
        assertEquals("https://example.test/", url)
        assertFalse("no data may appear in the link at all", url.contains("payload"))
    }

    @Test
    fun deselectedCategoriesAreAbsentFromTheJson() {
        val sel = FeedbackPayload.Selection(
            device = true, appVersion = false, usb = false, partitions = false, logs = false
        )
        val json = JSONObject(FeedbackPayload.toJson(contents(), sel))
        assertTrue(json.has("deviceName"))
        assertFalse(json.has("appVersion"))
        assertFalse(json.has("usbMake"))
        assertFalse(json.has("usbSize"))
        assertFalse(json.has("partitionCount"))
        assertFalse(json.has("deviceLogs"))
    }

    /** No drive connected is a normal case: the keys should be absent, not blank. */
    @Test
    fun noUsbConnectedOmitsDriveKeys() {
        val json = JSONObject(
            FeedbackPayload.toJson(
                contents(partitions = emptyList(), usbMake = "", usbSize = "")
            )
        )
        assertTrue(json.has("deviceName"))
        assertFalse(json.has("usbMake"))
        assertFalse(json.has("usbSize"))
        assertFalse(json.has("partitionCount"))
    }

    @Test
    fun summaryShowsOnlyWhatWillBeSent() {
        val sel = FeedbackPayload.Selection(logs = false)
        val text = FeedbackPayload.summary(contents(), sel)
        assertTrue(text.contains("Device:"))
        assertTrue(text.contains("62.0 GB"))
        assertFalse("a deselected log must not be shown as if it were included",
            text.contains("line one"))
    }

    @Test
    fun summarySaysPlainlyWhenNothingWillBeSent() {
        val text = FeedbackPayload.summary(contents(), FeedbackPayload.Selection.NONE)
        assertTrue(text.contains("Nothing will be shared"))
        assertFalse(text.contains("Pixel"))
    }

    /**
     * The URL has to stay within what proxies accept. A 50-line log of long lines is
     * well past the budget, so this is the case that matters.
     */
    @Test
    fun aLargeLogIsTrimmedAndTheUrlStaysWithinBudget() {
        val big = (1..50).map { "line $it " + "x".repeat(300) }
        val c = FeedbackPayload.collectForTest(big)
        val url = FeedbackPayload.url("https://otgmaster-feedback.moylali.workers.dev/", c)
        assertTrue("url was ${url.length} chars", url.length <= 8_000)
        assertTrue("older lines should be reported as dropped", c.logLinesDropped > 0)
        // The tail is what a report needs: it describes what just went wrong.
        assertTrue("the newest line must be kept", c.logLines.last().startsWith("line 50"))
    }
}

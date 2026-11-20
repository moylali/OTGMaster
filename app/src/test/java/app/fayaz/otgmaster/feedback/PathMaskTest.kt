package app.fayaz.otgmaster.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathMaskTest {
    @Test fun `each name keeps its first character and last three`() {
        assertEquals("D***/C***/I***jpg", PathMask.mask("DCIM/Camera/IMG_2024.jpg"))
        assertEquals("/P***025/t***pdf", PathMask.mask("/Paperwork-2025/tax-return-final.pdf"))
    }

    @Test fun `a short name keeps only its first character`() {
        assertEquals("D***/a***/m***", PathMask.mask("DCIM/a/medium"))
        assertEquals("/", PathMask.mask("/"))
    }

    @Test fun `the report masks a marked path and nothing else on the line`() {
        val line = "Backup \"Phone\": not read: ${PathMask.mark("DCIM/Camera/IMG_2024.jpg")}: Permission denied"
        assertEquals("Backup \"Phone\": not read: D***/C***/I***jpg: Permission denied", PathMask.maskMarked(line))
    }

    @Test fun `lines without marks are left alone, slashes included`() {
        val line = "Opening /dev/bus/usb/002/005 interface 0; 5.45 MB/s"
        assertEquals(line, PathMask.maskMarked(line))
    }

    @Test fun `an unclosed mark masks to the end of the line`() {
        assertEquals("x: S***ret", PathMask.maskMarked("x: \u2068SecretSecret"))
    }

    @Test fun `the feedback log carries masked paths, not names`() {
        val logs = listOf(
            "Verify \"Phone\": ${PathMask.mark("Holidays/Barcelona_family.jpg")}: differs",
            "Opening /dev/bus/usb/002/005 interface 0",
        )
        val sent = FeedbackPayload.collectForTest(logs).logLines
        assertEquals("Verify \"Phone\": H***ays/B***jpg: differs", sent[0])
        assertEquals("Opening /dev/bus/usb/002/005 interface 0", sent[1])
        assertFalse(sent.any { "Barcelona" in it || '⁨' in it || '⁩' in it })
        val url = FeedbackPayload.url("https://example.invalid/", FeedbackPayload.collectForTest(logs))
        assertTrue(url.contains("&logs="))
    }
}

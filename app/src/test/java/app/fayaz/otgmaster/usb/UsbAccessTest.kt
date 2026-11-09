package app.fayaz.otgmaster.usb

import app.fayaz.otgmaster.usb.UsbAccess.Decision
import app.fayaz.otgmaster.usb.UsbAccess.decide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAccessTest {
    private val waited = UsbAccess.ANDROID_WAIT_MS
    private val never: () -> Boolean = { throw AssertionError("asked Android when it did not matter") }

    @Test fun `always opens without asking`() {
        assertEquals(Decision.OPEN, decide(UsbAccessMode.ALWAYS, false, 0, never))
    }

    @Test fun `manual leaves a drive until Scan`() {
        assertEquals(Decision.LEAVE_UNTIL_SCAN, decide(UsbAccessMode.MANUAL, false, waited, never))
        assertEquals(Decision.OPEN, decide(UsbAccessMode.MANUAL, true, 0, never))
    }

    @Test fun `wait gives Android its time, then opens what it left`() {
        assertEquals(Decision.WAIT, decide(UsbAccessMode.WAIT_FOR_ANDROID, false, waited - 1) { false })
        assertEquals(Decision.OPEN, decide(UsbAccessMode.WAIT_FOR_ANDROID, false, waited) { false })
    }

    @Test fun `wait leaves a drive Android took, at once`() {
        assertEquals(Decision.LEFT_WITH_ANDROID, decide(UsbAccessMode.WAIT_FOR_ANDROID, false, 0) { true })
        assertEquals(Decision.LEFT_WITH_ANDROID, decide(UsbAccessMode.WAIT_FOR_ANDROID, false, waited * 10) { true })
    }

    @Test fun `Scan overrides waiting`() {
        assertEquals(Decision.OPEN, decide(UsbAccessMode.WAIT_FOR_ANDROID, true, 0, never))
    }

    @Test fun `an unknown saved value is the default`() {
        assertEquals(UsbAccessMode.ALWAYS, UsbAccessMode.fromPref("gone"))
        assertEquals(UsbAccessMode.ALWAYS, UsbAccessMode.fromPref(null))
        UsbAccessMode.entries.forEach { assertEquals(it, UsbAccessMode.fromPref(it.prefValue)) }
    }

    @Test fun `an SD card seen before the drive is not the drive`() {
        assertFalse(UsbAccess.androidHolds(setOf("SDCARD"), setOf("SDCARD")))
        assertTrue(UsbAccess.androidHolds(setOf("SDCARD", "1234-ABCD"), setOf("SDCARD")))
        assertFalse(UsbAccess.androidHolds(emptySet(), setOf("SDCARD")))
    }

    @Test fun `Android can read plain FAT and exFAT only`() {
        assertTrue(UsbAccess.androidCanRead(listOf(EjectedPartition(true, "FAT32"), EjectedPartition(true, "exFAT"))))
        assertFalse(UsbAccess.androidCanRead(listOf(EjectedPartition(true, "FAT32"), EjectedPartition(false, "FAT32"))))
        assertFalse(UsbAccess.androidCanRead(listOf(EjectedPartition(true, "ext4"))))
        assertFalse(UsbAccess.androidCanRead(emptyList()))
    }
}

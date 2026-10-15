package app.fayaz.otgmaster.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pulling one stick must not count as pulling every other stick of the same model. */
class DetachMatchTest {
    private val pny = "5404:4199"
    private val pulledSerial = "$pny:071C48F5FB0EF449"
    private val keptSerial = "$pny:071C48F5FB0EF450"

    // The serial is often unreadable once the device is gone, so the detached device's
    // keys can be only its bus-path form.
    private val detachedKeys = setOf("$pny:/dev/bus/usb/001/004")

    @Test
    fun theStickThatWentMatchesThoughItsSerialIsUnreadable() {
        assertTrue(UsbDeviceDescriber.matchesDetached(pulledSerial, detachedKeys, pny,
            stillAttachedKeys = setOf(keptSerial, "$pny:/dev/bus/usb/001/005")))
    }

    @Test
    fun anIdenticalStickStillPluggedInDoesNotMatch() {
        assertFalse(UsbDeviceDescriber.matchesDetached(keptSerial, detachedKeys, pny,
            stillAttachedKeys = setOf(keptSerial, "$pny:/dev/bus/usb/001/005")))
    }

    @Test
    fun anExactKeyMatchesEvenIfTheModelDiffers() {
        assertTrue(UsbDeviceDescriber.matchesDetached("$pny:/dev/bus/usb/001/004", detachedKeys, pny, emptySet()))
    }

    @Test
    fun anotherModelNeverMatches() {
        assertFalse(UsbDeviceDescriber.matchesDetached("1234:5678:ABC", detachedKeys, pny, emptySet()))
    }
}

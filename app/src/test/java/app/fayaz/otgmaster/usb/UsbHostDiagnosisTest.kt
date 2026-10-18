package app.fayaz.otgmaster.usb

import app.fayaz.otgmaster.usb.UsbHostDiagnosis.Hint
import app.fayaz.otgmaster.usb.UsbHostDiagnosis.PortState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the app says when it lists no USB device. Each case is a report or a run
 * where "Found 0 USB devices" was all the log said.
 */
class UsbHostDiagnosisTest {

    private fun state(
        host: Boolean = true, computer: Boolean = false, charging: Boolean = false, brand: String = "Google",
        otgOn: Boolean? = null,
    ) = PortState(host, computer, charging, brand, otgOn)

    @Test
    fun `the Huawei on a powered hub is told the port is charging, not hosting`() {
        // Huawei P20 Lite, 2026-10-05: AC powered, port in device mode, no devices.
        assertEquals(listOf(Hint.CHARGING_FROM_PORT), UsbHostDiagnosis.hints(state(charging = true, brand = "HUAWEI")))
    }

    @Test
    fun `vivo phones are told about the OTG switch`() {
        // #23 vivo Y93, #26 vivo V2534: nothing plugged into a charger, nothing listed.
        assertEquals(listOf(Hint.OTG_SWITCH), UsbHostDiagnosis.hints(state(brand = "vivo")))
        for (b in listOf("iQOO", "OPPO", "realme", "OnePlus")) {
            assertEquals(b, listOf(Hint.OTG_SWITCH), UsbHostDiagnosis.hints(state(brand = b)))
        }
    }

    @Test
    fun `a phone attached to a computer is told so, and not also that it is charging`() {
        assertEquals(listOf(Hint.CONNECTED_TO_COMPUTER),
            UsbHostDiagnosis.hints(state(computer = true, charging = true)))
    }

    @Test
    fun `no host support says only that`() {
        assertEquals(listOf(Hint.NO_HOST_SUPPORT),
            UsbHostDiagnosis.hints(state(host = false, charging = true, brand = "vivo")))
    }

    @Test
    fun `causes combine`() {
        assertEquals(listOf(Hint.CHARGING_FROM_PORT, Hint.OTG_SWITCH),
            UsbHostDiagnosis.hints(state(charging = true, brand = "realme")))
    }

    @Test
    fun `a ColorOS switch that reads off is reported as off`() {
        // realme 8 (RMX3085), 2026-10-18: persist.sys.oplus.otg_support read 0 with OTG off.
        assertEquals(listOf(Hint.OTG_SWITCH_OFF), UsbHostDiagnosis.hints(state(brand = "realme", otgOn = false)))
    }

    @Test
    fun `a ColorOS switch that reads on is not mentioned`() {
        assertEquals(emptyList<Hint>(), UsbHostDiagnosis.hints(state(brand = "OnePlus", otgOn = true)))
        assertEquals(listOf(Hint.CHARGING_FROM_PORT),
            UsbHostDiagnosis.hints(state(charging = true, brand = "realme", otgOn = true)))
    }

    @Test
    fun `nothing to say about a host-capable phone on battery`() {
        assertEquals(emptyList<Hint>(), UsbHostDiagnosis.hints(state()))
    }
}

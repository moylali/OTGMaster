package app.fayaz.otgmaster.usb

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build

/**
 * Why a phone that lists no USB devices at all might not be seeing the drive.
 *
 * "Found 0 USB devices" is the whole log in several reports (#8, #23 vivo Y93,
 * #26 vivo V2534), and on the Huawei P20 Lite the cause was a hub with a power
 * supply: the phone took the charging role, its port went to device mode, and it
 * never became a host. None of that reaches the app as an error; the device list
 * is simply empty. What the app can read without special permissions is enough
 * to say which of the usual causes applies.
 */
object UsbHostDiagnosis {

    enum class Hint {
        /** The phone does not declare USB host support at all. */
        NO_HOST_SUPPORT,
        /** The port is connected to a computer, with the phone as the USB device. */
        CONNECTED_TO_COMPUTER,
        /**
         * The phone is charging through its port. Many phones then stay in device
         * mode; some (a OnePlus 7 on a powered hub) host and charge at once, so
         * this is a likely cause, not a certain one.
         */
        CHARGING_FROM_PORT,
        /**
         * vivo, iQOO, OPPO, realme and OnePlus ship an OTG switch that is off by
         * default and turns itself off after a few minutes unused; while it is off
         * the device list is empty.
         */
        OTG_SWITCH,
    }

    data class PortState(
        val hostSupported: Boolean,
        val connectedToComputer: Boolean,
        val chargingFromPort: Boolean,
        val manufacturer: String,
    )

    private val OTG_SWITCH_BRANDS = setOf("vivo", "iqoo", "oppo", "realme", "oneplus")

    fun hasOtgSwitch(manufacturer: String) = manufacturer.trim().lowercase() in OTG_SWITCH_BRANDS

    /** The hints that apply when no USB device is listed, most decisive first. */
    fun hints(s: PortState): List<Hint> {
        if (!s.hostSupported) return listOf(Hint.NO_HOST_SUPPORT)
        val out = mutableListOf<Hint>()
        if (s.connectedToComputer) out += Hint.CONNECTED_TO_COMPUTER
        else if (s.chargingFromPort) out += Hint.CHARGING_FROM_PORT
        if (hasOtgSwitch(s.manufacturer)) out += Hint.OTG_SWITCH
        return out
    }

    /** Reads the port state from sticky broadcasts; nothing here needs a permission. */
    fun read(context: Context): PortState {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        // UsbManager.ACTION_USB_STATE and its "connected" extra are hidden constants,
        // but the broadcast is sticky and readable, and both strings have been stable
        // since Android 4.
        val usbState = runCatching {
            context.registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE"))
        }.getOrNull()
        val brand = Build.BRAND.orEmpty().takeIf { hasOtgSwitch(it) } ?: Build.MANUFACTURER.orEmpty()
        return PortState(
            hostSupported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
            connectedToComputer = usbState?.getBooleanExtra("connected", false) ?: false,
            chargingFromPort = plugged and (BatteryManager.BATTERY_PLUGGED_AC or BatteryManager.BATTERY_PLUGGED_USB) != 0,
            manufacturer = brand,
        )
    }
}

package app.fayaz.otgmaster.usb

import android.content.Context
import android.provider.Settings

/**
 * ColorOS (OPPO, realme, OnePlus) keeps its "OTG connection" switch in Settings.Global
 * persist.sys.oplus.otg_support: "1" on, "0" off. An app may read it - checked on a realme 8
 * (RMX3085, Android 13) from inside the app, following the switch both ways - and it lets
 * the no-drive help say "OTG is off" only when it is, instead of to every phone of the brand.
 */
object OemOtgSwitch {
    const val KEY = "persist.sys.oplus.otg_support"

    /** True on, false off; null where the setting does not exist or cannot be read. */
    fun isOn(context: Context): Boolean? = try {
        when (Settings.Global.getString(context.contentResolver, KEY)) {
            "1" -> true
            "0" -> false
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

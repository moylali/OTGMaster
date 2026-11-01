package app.fayaz.otgmaster.usb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import app.fayaz.otgmaster.MainActivity
import app.fayaz.otgmaster.OtgMasterState
import app.fayaz.otgmaster.R
import app.fayaz.otgmaster.mtp.MtpSession

/**
 * Tells the user about a drive plugged in that OTG Master was not opened for.
 *
 * Android opens OTG Master for a USB drive only when it is the default app for that device.
 * Otherwise it shows an "Open with" chooser, and behind the lock screen that chooser closes
 * itself and opens nothing (realme 8, Android 13: UsbResolverActivity created and finished in
 * 0.2 s). OTG Master, closed, never heard of the drive. An app cannot bring its own screen up
 * from the background, so a notification is the one thing it may do: tap it after unlock.
 *
 * Android has no API that says whether this app is the default for a device, so the outcome
 * decides: if MainActivity was started for this plug-in or is on screen within [WAIT_MS], no
 * notification; if not, one. A manifest receiver gets these broadcasts even with the app
 * closed (they are exempt from Android 8's implicit-broadcast limits).
 */
class UsbPlugInReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_DETACHED -> cancel(app)
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                if (device == null) return
                if (!UsbDeviceDescriber.isMassStorageDevice(device) && !MtpSession.isMtpDevice(device)) return
                val pluggedAt = SystemClock.elapsedRealtime()
                val pending = goAsync()
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        if (!MainActivity.handledSince(pluggedAt)) show(app, UsbDeviceDescriber.friendlyName(device, 0))
                    } finally {
                        pending.finish()
                    }
                }, WAIT_MS)
            }
        }
    }

    companion object {
        /** Android starts the default app within about a second; the chooser closes in 0.2 s. */
        private const val WAIT_MS = 3_000L
        private const val CHANNEL = "usb_plugged_in"
        private const val NOTIFICATION_ID = 0x07D8

        private fun show(context: Context, name: String) {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                OtgMasterState.log(context.getString(R.string.log_plugged_in_no_notice, name))
                return
            }
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL,
                    context.getString(R.string.notification_channel_plugged_in), NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_drive)
                .setContentTitle(context.getString(R.string.notification_plugged_in_title, name))
                .setContentText(context.getString(R.string.notification_plugged_in_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build())
            OtgMasterState.log(context.getString(R.string.log_plugged_in_notice, name))
        }

        /** The drive mounted, the app came to the front, or the drive left: the notice has done its job. */
        fun cancel(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }
}

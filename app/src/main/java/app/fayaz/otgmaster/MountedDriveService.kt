package app.fayaz.otgmaster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * A foreground service that runs while any drive is mounted, so the files stay readable
 * after the user switches to another app.
 *
 * Files are served through StorageManager.openProxyFileDescriptor, whose reads are answered
 * by a thread in this process. Once the user left for the Files app, nothing kept this
 * process in a foreground state, and Android's cached-app freezer froze it: on a Pixel 7
 * Pro (Android 17) a 256 MB video from a mounted T7 stopped loading, and the log showed
 * "freezing … app.fayaz.otgmaster" 34 s after the viewer opened it. Images, read in one go
 * before the freeze, were fine. A running foreground service exempts the process.
 *
 * One notification, naming the first drive and counting the rest. Started from the
 * activity when the first drive mounts; stopped when the last one goes. A phone that
 * refuses the service gets what it had before, never a crash.
 */
class MountedDriveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = build(this, intent?.getStringExtra(EXTRA_FIRST).orEmpty(), intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
            running = true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException, or a SecurityException where the
            // connectedDevice prerequisites are not met. The drive still works while the app
            // is in front; the service only keeps it working behind another app.
            Log.w(TAG, "foreground service refused: ${e.javaClass.simpleName}: ${e.message}")
            running = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OTGMaster"
        private const val CHANNEL = "mounted_drives"
        private const val NOTIFICATION_ID = 0x07D6
        private const val EXTRA_FIRST = "first"
        private const val EXTRA_COUNT = "count"

        @Volatile private var running = false

        /**
         * Brings the service in line with what is mounted: [titles] in mount order. Called on
         * every change; the first start must come while the activity is in front, which is
         * where every mount happens.
         */
        fun update(context: Context, titles: List<String>) {
            val app = context.applicationContext
            if (titles.isEmpty()) {
                if (running) app.stopService(Intent(app, MountedDriveService::class.java))
                running = false
                return
            }
            if (running) {
                // Already in the foreground: only the text changes. Posting it directly avoids
                // a service start, which Android may refuse while the app is behind another.
                app.getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, build(app, titles.first(), titles.size))
                return
            }
            try {
                app.startForegroundService(Intent(app, MountedDriveService::class.java)
                    .putExtra(EXTRA_FIRST, titles.first())
                    .putExtra(EXTRA_COUNT, titles.size))
            } catch (e: Exception) {
                Log.w(TAG, "could not start the mounted-drive service: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        private fun build(context: Context, first: String, count: Int): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL,
                    context.getString(R.string.notification_channel_mounted), NotificationManager.IMPORTANCE_LOW))
            }
            val text = if (count <= 1) context.getString(R.string.notification_drive_mounted, first)
                else context.resources.getQuantityString(R.plurals.notification_drives_mounted, count - 1, first, count - 1)
            val open = PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_drive)
                .setContentTitle(text)
                .setContentText(context.getString(R.string.notification_mounted_detail))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}

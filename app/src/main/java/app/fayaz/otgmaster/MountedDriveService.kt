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
        val notification = current(this)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
            running = true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException, or a SecurityException where the
            // connectedDevice prerequisites are not met. The drive still works while the app
            // is in front; the service only keeps it working behind another app.
            Log.w(TAG, "foreground service refused: ${e.javaClass.simpleName}: ${e.message}")
            OtgMasterState.logFailure("fgs", "Android refused the background service that keeps drives mounted behind other apps", e)
            running = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    /** A Format or Encrypt in progress, shown in place of the mounted-drive text. */
    data class Operation(val title: String, val phase: String, val fraction: Float?)

    companion object {
        private const val TAG = "OTGMaster"
        private const val CHANNEL = "mounted_drives"
        private const val RESULT_CHANNEL = "drive_operations"
        private const val NOTIFICATION_ID = 0x07D6
        private const val RESULT_ID = 0x07D7
        /** Android drops updates posted faster than this; progress need not be smoother. */
        private const val PROGRESS_INTERVAL_MS = 1_000L

        @Volatile private var running = false
        @Volatile private var titles: List<String> = emptyList()
        @Volatile private var operation: Operation? = null
        @Volatile private var lastProgressPost = 0L

        fun update(context: Context, titles: List<String>) {
            this.titles = titles
            refresh(context.applicationContext)
        }

        /**
         * A Format or Encrypt is running: keeps the service in the foreground for its whole
         * length, with its progress in the notification, even when nothing else is mounted.
         *
         * Formatting or encrypting the only partition on a drive unmounts it first, so the
         * mounted list goes empty and the service used to stop - leaving a run that can take
         * an hour (a full Encrypt of a 64 GB stick) in a process Android may freeze or kill
         * as soon as the user switches app. A volume abandoned half-written is the worst
         * outcome there is. Being in the foreground is what prevents it.
         */
        fun operationProgress(context: Context, op: Operation) {
            val app = context.applicationContext
            val starting = operation == null
            operation = op
            val now = android.os.SystemClock.elapsedRealtime()
            if (!starting && running && now - lastProgressPost < PROGRESS_INTERVAL_MS) return
            lastProgressPost = now
            refresh(app)
        }

        /** The operation ended: a separate notice says how, and the ongoing one goes back to drives. */
        fun operationFinished(context: Context, title: String, message: String, failed: Boolean) {
            val app = context.applicationContext
            operation = null
            val nm = app.getSystemService(NotificationManager::class.java)
            if (nm != null) {
                if (nm.getNotificationChannel(RESULT_CHANNEL) == null) {
                    nm.createNotificationChannel(NotificationChannel(RESULT_CHANNEL,
                        app.getString(R.string.notification_channel_operations), NotificationManager.IMPORTANCE_DEFAULT))
                }
                nm.notify(RESULT_ID, NotificationCompat.Builder(app, RESULT_CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_drive)
                    .setContentTitle(app.getString(
                        if (failed) R.string.notification_operation_failed else R.string.notification_operation_done, title))
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setContentIntent(openApp(app))
                    .setAutoCancel(true)
                    .build())
            }
            refresh(app)
        }

        private fun refresh(app: Context) {
            if (titles.isEmpty() && operation == null) {
                if (running) app.stopService(Intent(app, MountedDriveService::class.java))
                running = false
                return
            }
            if (running) {
                // Already in the foreground: only the text changes. Posting it directly avoids
                // a service start, which Android may refuse while the app is behind another.
                app.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, current(app))
                return
            }
            try {
                app.startForegroundService(Intent(app, MountedDriveService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "could not start the mounted-drive service: ${e.javaClass.simpleName}: ${e.message}")
                OtgMasterState.logFailure("fgs", "Could not start the background service that keeps drives mounted", e)
            }
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun current(context: Context): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL,
                    context.getString(R.string.notification_channel_mounted), NotificationManager.IMPORTANCE_LOW))
            }
            val b = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_drive)
                .setContentIntent(openApp(context))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            val op = operation
            if (op != null) {
                return b.setContentTitle(op.title)
                    .setContentText(op.phase.ifEmpty { context.getString(R.string.notification_operation_keep_attached) })
                    .setSubText(context.getString(R.string.notification_operation_keep_attached))
                    .setProgress(1000, ((op.fraction ?: 0f) * 1000).toInt(), op.fraction == null)
                    .build()
            }
            val first = titles.firstOrNull().orEmpty()
            val count = titles.size.coerceAtLeast(1)
            val text = if (count <= 1) context.getString(R.string.notification_drive_mounted, first)
                else context.resources.getQuantityString(R.plurals.notification_drives_mounted, count - 1, first, count - 1)
            return b.setContentTitle(text)
                .setContentText(context.getString(R.string.notification_mounted_detail))
                .build()
        }
    }
}

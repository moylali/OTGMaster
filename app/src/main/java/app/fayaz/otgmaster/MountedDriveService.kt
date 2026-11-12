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
            synchronized(lock) { startPending = false; running = true }
            if (refused) {
                refused = false
                OtgMasterState.log(getString(R.string.log_fgs_started_after_refusal))
            }
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException, or a SecurityException where the
            // connectedDevice prerequisites are not met. The drive still works while the app
            // is in front; the service only keeps it working behind another app.
            Log.w(TAG, "foreground service refused: ${e.javaClass.simpleName}: ${e.message}")
            OtgMasterState.logFailure("fgs", "Android refused the background service that keeps drives mounted behind other apps", e)
            refused = true
            synchronized(lock) { startPending = false; running = false }
            stopSelf()
            return START_NOT_STICKY
        }
        // Everything ended while Android was starting us. Only now may it stop: stopping a
        // service before it has called startForeground crashes the process.
        synchronized(lock) {
            if (titles.isEmpty() && operation == null && teardowns.isEmpty()) {
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    /** A Format or Encrypt in progress, shown in place of the mounted-drive text. */
    data class Operation(val title: String, val phase: String, val fraction: Float?, val backupJobId: String? = null)

    companion object {
        private const val TAG = "OTGMaster"
        private const val CHANNEL = "mounted_drives"
        private const val RESULT_CHANNEL = "drive_operations"
        private const val NOTIFICATION_ID = 0x07D6
        private const val RESULT_ID = 0x07D7
        /** Request code of the intent that opens the app on the mounted-drives slider. */
        private const val OPEN_MOUNTED_REQUEST = 1
        private const val HIDDEN_PROTECTION_ID = 0x07D8
        private const val EJECTED_ID = 0x07D9
        /** Android drops updates posted faster than this; progress need not be smoother. */
        private const val PROGRESS_INTERVAL_MS = 1_000L

        @Volatile private var running = false
        /** startForegroundService was called and onStartCommand has not run yet. */
        private var startPending = false
        /** Showing [current] with no service behind it: see [mayRunInForeground]. */
        private var plainPosted = false

        /**
         * Android 14 and later refuse a connected-device service unless the app holds permission
         * for a USB device. Asking anyway is not harmless: startForeground throws, and stopping
         * the service then crashes the process with ForegroundServiceDidNotStartInTimeException
         * (seen in E2E). A backup on the phone alone with nothing plugged in gets here.
         */
        private fun mayRunInForeground(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 34) return true
            val usb = context.getSystemService(android.hardware.usb.UsbManager::class.java) ?: return false
            return usb.deviceList.values.any { usb.hasPermission(it) }
        }

        /** Drives change on the main thread and backups finish on their own. */
        private val lock = Any()
        /** A start was refused, so the next one that succeeds is worth a log line. */
        @Volatile private var refused = false
        val isRunning: Boolean get() = running
        @Volatile private var titles: List<String> = emptyList()
        @Volatile private var operation: Operation? = null
        /** Partitions whose unmount teardown (flush and close) is running, by name. */
        private val teardowns = java.util.concurrent.CopyOnWriteArrayList<String>()

        /**
         * An unmount's teardown has started: the service stays in the foreground until
         * [endTeardown], so Android cannot freeze or kill the process mid-flush once the last
         * partition has left the mounted list (review of #141, PR-3).
         */
        fun beginTeardown(context: Context, name: String) { teardowns += name; refresh(context.applicationContext) }

        fun endTeardown(context: Context, name: String) { teardowns.remove(name); refresh(context.applicationContext) }
        @Volatile private var lastProgressPost = 0L

        fun update(context: Context, titles: List<String>) {
            val added = titles.any { it !in this.titles }
            val removed = this.titles.any { it !in titles }
            this.titles = titles
            refresh(context.applicationContext)
            // A drive just mounted: a backup that was waiting for it can ask now. One went:
            // what was asked about it, or waiting to start on it, has to change.
            if (added) app.fayaz.otgmaster.backup.BackupScheduler.evaluateSoon(context, "attach")
            else if (removed) app.fayaz.otgmaster.backup.BackupScheduler.evaluateSoon(context, "detach")
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
        fun operationFinished(context: Context, title: String, message: String, failed: Boolean, jobId: String? = null) {
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
                    .setContentIntent(openApp(app, jobId))
                    .setAutoCancel(true)
                    .build())
            }
            refresh(app)
        }

        /**
         * VeraCrypt's Background Task warning. A write usually comes from another app, with this
         * one in the background, so a dialog alone would go unseen. Secret on the lock screen:
         * the text says that a hidden volume exists.
         */
        fun hiddenProtectionTriggered(context: Context, volumeName: String) {
            val app = context.applicationContext
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(RESULT_CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(RESULT_CHANNEL,
                    app.getString(R.string.notification_channel_operations), NotificationManager.IMPORTANCE_DEFAULT))
            }
            val text = app.getString(R.string.hidden_protection_triggered_notification, volumeName)
            nm.notify(HIDDEN_PROTECTION_ID, NotificationCompat.Builder(app, RESULT_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_drive)
                .setContentTitle(app.getString(R.string.hidden_protection_triggered_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setContentIntent(openApp(app))
                .setAutoCancel(true)
                .build())
        }

        /** An Eject, or with [titleRes] an Unmount, the user did not stay to watch has finished. */
        fun driveEjected(context: Context, name: String, message: String,
                         titleRes: Int = R.string.notification_ejected_title) {
            val app = context.applicationContext
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(RESULT_CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(RESULT_CHANNEL,
                    app.getString(R.string.notification_channel_operations), NotificationManager.IMPORTANCE_DEFAULT))
            }
            nm.notify(EJECTED_ID, NotificationCompat.Builder(app, RESULT_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_drive)
                .setContentTitle(app.getString(titleRes, name))
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(openApp(app))
                .setAutoCancel(true)
                .build())
        }

        private fun refresh(app: Context) { synchronized(lock) {
            if (titles.isEmpty() && operation == null && teardowns.isEmpty()) {
                if (plainPosted) {
                    plainPosted = false
                    app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
                }
                // A start in flight stops itself once it has called startForeground.
                if (startPending) return
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
            if (startPending) return
            if (!mayRunInForeground(app)) {
                // The progress still shows, as an ordinary notification.
                plainPosted = true
                app.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, current(app))
                return
            }
            try {
                startPending = true
                app.startForegroundService(Intent(app, MountedDriveService::class.java))
            } catch (e: Exception) {
                startPending = false
                Log.w(TAG, "could not start the mounted-drive service: ${e.javaClass.simpleName}: ${e.message}")
                OtgMasterState.logFailure("fgs", "Could not start the background service that keeps drives mounted", e)
                refused = true
            }
        } }

        /**
         * Opens the main screen, and over it the progress dialog of [backupJobId] if there is
         * one, or with [mountedSlider] the slider of mounted drives. Each kind has its own
         * request code: with FLAG_UPDATE_CURRENT a shared one would rewrite the others' extras.
         */
        private fun openApp(context: Context, backupJobId: String? = null, mountedSlider: Boolean = false): PendingIntent =
            PendingIntent.getActivity(context, backupJobId?.hashCode() ?: if (mountedSlider) OPEN_MOUNTED_REQUEST else 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).apply {
                backupJobId?.let { putExtra(app.fayaz.otgmaster.backup.BackupNotifications.EXTRA_PROGRESS_JOB, it) }
                if (mountedSlider) putExtra(MainActivity.EXTRA_OPEN_MOUNTED, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun current(context: Context): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL,
                    context.getString(R.string.notification_channel_mounted), NotificationManager.IMPORTANCE_LOW))
            }
            val b = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_drive)
                .setContentIntent(openApp(context, mountedSlider = true))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            val op = operation
            if (op != null) {
                op.backupJobId?.let { b.setContentIntent(openApp(context, it)) }
                return b.setContentTitle(op.title)
                    .setContentText(op.phase.ifEmpty { context.getString(R.string.notification_operation_keep_attached) })
                    .setSubText(context.getString(R.string.notification_operation_keep_attached))
                    .setProgress(1000, ((op.fraction ?: 0f) * 1000).toInt(), op.fraction == null)
                    .build()
            }
            if (titles.isEmpty()) teardowns.firstOrNull()?.let { name ->
                return b.setContentTitle(context.getString(R.string.notification_unmounting, name))
                    .setContentText(context.getString(R.string.notification_operation_keep_attached))
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

package app.fayaz.containermaster

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Keeps the process, and with it the open containers, alive while the user reads them in
 * other apps (architecture proposal, F4). Started from the visible activity when an unlock
 * begins, promoted at once, and stopped when nothing is open. Its notification names no
 * file and carries Lock all, an explicit immutable intent to this non-exported service.
 */
class SessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var ticker: Job? = null

    /** Locks everything when the screen turns off, if the user chose that in Settings. */
    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (ContainerMasterApp.prefs(c).getBoolean(ContainerMasterApp.LOCK_ON_SCREEN_OFF, false) &&
                ContainerSessions.open.value.isNotEmpty()) {
                CmLog.log("The screen turned off: locking every container")
                Thread { ContainerSessions.lockAll(applicationContext) }.start()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW))
        registerReceiver(screenOff, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF))
        // Each find() also checks expiry, so a container a client uses after the phone slept
        // is refused even before this runs; the ticker locks the ones nobody touches.
        ticker = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000L)
                kotlinx.coroutines.withContext(Dispatchers.IO) { ContainerSessions.lockExpired(applicationContext) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote(ContainerSessions.open.value.size)
        if (intent?.action == ACTION_LOCK_ALL) {
            Thread { ContainerSessions.lockAll(applicationContext) }.start()
        }
        if (watcher == null) watcher = scope.launch {
            ContainerSessions.open.collect { open ->
                if (open.isEmpty() && !opening) stopSelf() else promote(open.size)
            }
        }
        return START_NOT_STICKY
    }

    private fun promote(count: Int) {
        val lockAll = PendingIntent.getService(this, 0,
            Intent(this, SessionService::class.java).setAction(ACTION_LOCK_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val openApp = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(resources.getQuantityString(R.plurals.notification_text, count, count))
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.lock_all), lockAll).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOff) }
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "open_containers"
        private const val NOTIFICATION_ID = 1
        const val ACTION_LOCK_ALL = "app.fayaz.containermaster.LOCK_ALL"

        /** True while an unlock started from the activity is running, so the service stays. */
        @Volatile var opening = false

        /** Starts the service; call from the visible activity, before the unlock begins. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, SessionService::class.java))
        }
    }
}

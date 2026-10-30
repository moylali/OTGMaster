package app.fayaz.otgmaster.backup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.fayaz.otgmaster.MainActivity
import app.fayaz.otgmaster.MountedDriveService
import app.fayaz.otgmaster.OtgMasterState
import app.fayaz.otgmaster.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs backups, restores and checks one at a time, on their own thread, with progress
 * in the mounted-drive notification.
 *
 * Not WorkManager workers: a worker is stopped after ten minutes unless it runs as a
 * foreground service of its own, which would need a second foreground-service type and
 * Play declaration. A backup always has a mounted drive at one end, and while a drive is
 * mounted MountedDriveService is already in the foreground for it, so the run lives in
 * this process under that service.
 */
object BackupExecutor {
    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "otg-backup") }
    private val queued = ConcurrentHashMap.newKeySet<String>()
    private val cancel = ConcurrentHashMap<String, AtomicBoolean>()

    sealed class Op {
        data class Backup(val reason: String, val overrideLowSpace: Boolean = false) : Op()
        data class Restore(val snapshotId: String, val subPath: String, val target: Uri?, val targetPath: String, val safety: Boolean) : Op()
        data class Check(val readData: Boolean) : Op()
    }

    fun isBusy(jobId: String) = jobId in queued

    fun cancel(jobId: String) { cancel[jobId]?.set(true) }

    /** Queues [op] on [jobId]; false if that job already has something queued or running. */
    fun start(context: Context, jobId: String, op: Op): Boolean {
        if (!queued.add(jobId)) return false
        val app = context.applicationContext
        val stop = AtomicBoolean(false).also { cancel[jobId] = it }
        thread.execute {
            val store = JobStore.get(app)
            val name = store.job(jobId)?.name ?: "backup"
            val title = when (op) {
                is Op.Backup -> "Backing up $name"
                is Op.Restore -> "Restoring from $name"
                is Op.Check -> "Checking $name"
            }
            try {
                var last = 0L
                val control = BackupRunner.Control(isCancelled = { stop.get() }, listener = { p ->
                    val now = System.currentTimeMillis()
                    if (now - last >= 1000) {
                        last = now
                        val fraction = if (p.totalBytes > 0) (p.bytes.toFloat() / p.totalBytes).coerceIn(0f, 1f) else null
                        val phase = when {
                            p.phase == "scan" -> "Measuring: ${p.files} files"
                            p.totalFiles > 0 -> "${p.files} of ${p.totalFiles} files · ${Format.bytes(p.bytes)}"
                            else -> "${p.phase}: ${p.files} files"
                        }
                        MountedDriveService.operationProgress(app, MountedDriveService.Operation(title, phase, fraction))
                    }
                })
                MountedDriveService.operationProgress(app, MountedDriveService.Operation(title, "Starting…", null))
                val runner = BackupRunner(app)
                val outcome = when (op) {
                    is Op.Backup -> runner.backup(jobId, op.reason, op.overrideLowSpace, control)
                    is Op.Restore -> runner.restore(jobId, op.snapshotId, op.subPath, op.target, op.targetPath, op.safety, control)
                    is Op.Check -> runner.check(jobId, op.readData, control)
                }
                val job = store.job(jobId)
                when (outcome) {
                    is RunOutcome.Success -> {
                        BackupNotifications.cancel(app, BackupNotifications.reminderId(jobId))
                        MountedDriveService.operationFinished(app, title, outcome.record.message, failed = false)
                    }
                    is RunOutcome.NeedsConfirmation -> {
                        MountedDriveService.operationFinished(app, title, "Waiting for your OK: the drive would be nearly full", failed = false)
                        if (job != null) BackupNotifications.lowSpace(app, job, outcome.pending)
                    }
                    is RunOutcome.Failed -> {
                        // A scheduled run finding its drive unplugged is normal: the reminder covers it.
                        val quiet = outcome.status == RunStatus.UNAVAILABLE && op is Op.Backup && op.reason == "scheduled"
                        if (!quiet) MountedDriveService.operationFinished(app, title, outcome.message, failed = outcome.status != RunStatus.CANCELLED)
                    }
                }
            } catch (t: Throwable) {
                OtgMasterState.logFailure("backup", "$title failed", t)
                MountedDriveService.operationFinished(app, title, "${t.javaClass.simpleName}: ${t.message}", failed = true)
            } finally {
                queued.remove(jobId)
                cancel.remove(jobId)
            }
        }
        return true
    }
}

/**
 * Decides, for every job, whether it is due and can run now, and whether the user
 * needs reminding to plug the drive in. Runs hourly (WorkManager), when a drive is
 * mounted, and when the app opens. It never starts a backup whose drive is not
 * mounted; that is what the reminder is for, and it names the drive.
 */
object BackupScheduler {
    private const val PERIODIC = "backup-scheduler"
    /** At most one reminder per job per this long. */
    const val REMINDER_SPACING_MS = 20 * 3_600_000L

    fun ensurePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<BackupSchedulerWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Off the main thread: availability checks query document providers. */
    fun evaluateSoon(context: Context, trigger: String) {
        val app = context.applicationContext
        Thread({ runCatching { evaluate(app, trigger) }.onFailure {
            OtgMasterState.logFailure("backup-scheduler", "Checking for due backups failed", it)
        } }, "otg-backup-scheduler").start()
    }

    /** Returns the ids of the jobs it started, for tests. */
    fun evaluate(context: Context, trigger: String, now: Long = System.currentTimeMillis()): List<String> {
        val store = JobStore.get(context)
        val started = ArrayList<String>()
        for (job in store.jobs.value) {
            if (job.isDue(now) && job.schedule.autoRun && job.pendingConfirmation == null) {
                val reachable = VolumeInfo.isAvailable(context, Uri.parse(job.source.uri)) &&
                    VolumeInfo.isAvailable(context, Uri.parse(job.destination.uri))
                if (reachable && BackupExecutor.start(context, job.id, BackupExecutor.Op.Backup(if (trigger == "attach") "attach" else "scheduled"))) {
                    started += job.id
                    continue
                }
            }
            if (job.isOverdue(now) && (job.lastReminderAt == null || now - job.lastReminderAt >= REMINDER_SPACING_MS)) {
                BackupNotifications.reminder(context, job, now)
                store.update(job.id) { it.copy(lastReminderAt = now) }
            }
        }
        return started
    }
}

class BackupSchedulerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        BackupScheduler.evaluate(applicationContext, "periodic")
        Result.success()
    }
}

/** Reminders to plug a drive in, and the low-space question. Progress and results use MountedDriveService's. */
object BackupNotifications {
    private const val CHANNEL_REMINDERS = "backup_reminders"
    private const val CHANNEL_QUESTIONS = "backup_questions"
    const val EXTRA_JOB_ID = "app.fayaz.otgmaster.extra.BACKUP_JOB_ID"

    fun reminderId(jobId: String) = 200_000 + (jobId.hashCode() and 0xffff)
    private fun questionId(jobId: String) = 300_000 + (jobId.hashCode() and 0xffff)

    private fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_REMINDERS, context.getString(R.string.backup_channel_reminders), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_QUESTIONS, context.getString(R.string.backup_channel_questions), NotificationManager.IMPORTANCE_DEFAULT),
        ))
    }

    private fun openApp(context: Context, jobId: String): PendingIntent = PendingIntent.getActivity(context, jobId.hashCode(),
        Intent(context, MainActivity::class.java).putExtra(EXTRA_JOB_ID, jobId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun reminder(context: Context, job: BackupJob, now: Long) {
        val since = job.lastSuccessAt
        val title = if (since == null) context.getString(R.string.backup_reminder_never, job.name)
            else context.getString(R.string.backup_reminder_last, job.name, Format.ago(since, now))
        val drives = listOf(job.source, job.destination).filter { it.kind == VolumeInfo.KIND_OTG }
        val text = if (drives.isNotEmpty()) context.getString(R.string.backup_reminder_plug, drives.joinToString(" and ") { VolumeInfo.summary(it.info) })
            else context.getString(R.string.backup_reminder_open)
        post(context, reminderId(job.id), NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_drive)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context, job.id)).setAutoCancel(true).build())
    }

    fun lowSpace(context: Context, job: BackupJob, pending: PendingConfirmation) {
        fun action(a: String, req: Int) = PendingIntent.getBroadcast(context, job.id.hashCode() + req,
            Intent(context, BackupActionReceiver::class.java).setAction(a).putExtra(EXTRA_JOB_ID, job.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = BackupRunner.lowSpaceMessage(pending) + " " + context.getString(R.string.backup_low_space_question)
        post(context, questionId(job.id), NotificationCompat.Builder(context, CHANNEL_QUESTIONS)
            .setSmallIcon(R.drawable.ic_stat_drive)
            .setContentTitle(context.getString(R.string.backup_low_space_title, job.name)).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context, job.id))
            .addAction(0, context.getString(R.string.backup_low_space_proceed), action(BackupActionReceiver.ACTION_PROCEED, 0))
            .addAction(0, context.getString(R.string.backup_low_space_skip), action(BackupActionReceiver.ACTION_SKIP, 1))
            .setAutoCancel(true).build())
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)
    fun cancelQuestion(context: Context, jobId: String) = cancel(context, questionId(jobId))

    @android.annotation.SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, n: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            OtgMasterState.log("Backup notice not shown (notifications are off for OTG Master): ${n.extras.getCharSequence("android.title")}")
            return
        }
        channels(context)
        NotificationManagerCompat.from(context).notify(id, n)
    }
}

/** "Proceed" and "Not now" on the low-space question. */
class BackupActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(BackupNotifications.EXTRA_JOB_ID) ?: return
        BackupNotifications.cancelQuestion(context, jobId)
        when (intent.action) {
            ACTION_PROCEED -> BackupExecutor.start(context, jobId, BackupExecutor.Op.Backup("confirmed", overrideLowSpace = true))
            ACTION_SKIP -> JobStore.get(context).update(jobId) {
                it.copy(pendingConfirmation = null, snoozedUntil = System.currentTimeMillis() + SNOOZE_MS)
            }
        }
    }

    companion object {
        const val ACTION_PROCEED = "app.fayaz.otgmaster.action.BACKUP_PROCEED"
        const val ACTION_SKIP = "app.fayaz.otgmaster.action.BACKUP_SKIP"
        const val SNOOZE_MS = 24 * 3_600_000L
    }
}

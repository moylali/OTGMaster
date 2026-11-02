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
import app.fayaz.otgmaster.extension.ExtensionContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs backups, restores and checks one at a time, on their own thread, with progress
 * in the mounted-drive notification and, for the dialog, in [runs].
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
        /** Sets the damaged backup aside and makes it again from the source. */
        object Recreate : Op()
    }

    /** What the progress dialog shows of one job's run; it stays after the run until read. */
    data class RunState(
        val jobId: String,
        val title: String,
        val phase: String = "",
        val fraction: Float? = null,
        /** Queued behind another job's run; the one thread takes them in turn. */
        val waiting: Boolean = true,
        val finished: Boolean = false,
        val failed: Boolean = false,
        val message: String? = null,
    )

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    /** Jobs with something queued or running, for screens to follow. */
    val busy: StateFlow<Set<String>> = _busy
    private val _runs = MutableStateFlow<Map<String, RunState>>(emptyMap())
    val runs: StateFlow<Map<String, RunState>> = _runs

    private fun setRun(jobId: String, change: (RunState?) -> RunState?) {
        _runs.update { m ->
            val next = change(m[jobId])
            if (next == null) m - jobId else m + (jobId to next)
        }
    }

    /** The user has read the result: forget it. */
    fun clearResult(jobId: String) = setRun(jobId) { if (it?.finished == true) null else it }

    fun isBusy(jobId: String) = jobId in queued

    fun cancel(jobId: String) { cancel[jobId]?.set(true) }

    /** Queues [op] on [jobId]; false if that job already has something queued or running. */
    fun start(context: Context, jobId: String, op: Op): Boolean {
        if (!queued.add(jobId)) return false
        _busy.value = queued.toSet()
        val app = context.applicationContext
        val stop = AtomicBoolean(false).also { cancel[jobId] = it }
        val store = JobStore.get(app)
        val name = store.job(jobId)?.name ?: "backup"
        val title = when (op) {
            is Op.Backup -> "Backing up $name"
            is Op.Restore -> "Restoring from $name"
            is Op.Check -> "Verifying $name"
            is Op.Recreate -> "Recreating $name"
        }
        setRun(jobId) { RunState(jobId, title) }
        // A Verify or a restore answers nothing about a due backup, and leaves a chosen delay alone.
        if (op is Op.Backup || op is Op.Recreate) {
            BackupDelays.clearFor(app, jobId)
            BackupNotifications.cancelDue(app, jobId)
        }
        thread.execute {
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
                        setRun(jobId) { it?.copy(phase = phase, fraction = fraction, waiting = false) }
                        MountedDriveService.operationProgress(app, MountedDriveService.Operation(title, phase, fraction, jobId))
                    }
                })
                setRun(jobId) { it?.copy(phase = "Starting…", waiting = false) }
                MountedDriveService.operationProgress(app, MountedDriveService.Operation(title, "Starting…", null, jobId))
                val runner = BackupRunner(app)
                val outcome = when (op) {
                    is Op.Backup -> runner.backup(jobId, op.reason, op.overrideLowSpace, control)
                    is Op.Restore -> runner.restore(jobId, op.snapshotId, op.subPath, op.target, op.targetPath, op.safety, control)
                    is Op.Check -> runner.check(jobId, op.readData, control)
                    is Op.Recreate -> runner.recreate(jobId, control)
                }
                val job = store.job(jobId)
                fun finish(message: String, failed: Boolean) = setRun(jobId) { it?.copy(finished = true, waiting = false, failed = failed, message = message, fraction = if (failed) it.fraction else 1f) }
                when (outcome) {
                    is RunOutcome.Success -> {
                        BackupNotifications.cancel(app, BackupNotifications.reminderId(jobId))
                        finish(outcome.record.message, false)
                        MountedDriveService.operationFinished(app, title, outcome.record.message, failed = false, jobId = jobId)
                    }
                    is RunOutcome.NeedsConfirmation -> {
                        val text = "Waiting for your OK: the drive would be nearly full"
                        finish(text, false)
                        MountedDriveService.operationFinished(app, title, text, failed = false, jobId = jobId)
                        if (job != null) BackupNotifications.lowSpace(app, job, outcome.pending)
                    }
                    is RunOutcome.Failed -> {
                        finish(outcome.message, outcome.status != RunStatus.CANCELLED)
                        // A scheduled run finding its drive unplugged is normal: the reminder covers it.
                        val quiet = outcome.status == RunStatus.UNAVAILABLE && op is Op.Backup && op.reason == "scheduled"
                        if (!quiet) MountedDriveService.operationFinished(app, title, outcome.message, failed = outcome.status != RunStatus.CANCELLED, jobId = jobId)
                        if (outcome.status == RunStatus.CORRUPTED) store.job(jobId)?.let { BackupPrompts.corruptionFound(app, it) }
                    }
                }
            } catch (t: Throwable) {
                OtgMasterState.logFailure("backup", "$title failed", t)
                val text = "${t.javaClass.simpleName}: ${t.message}"
                setRun(jobId) { it?.copy(finished = true, waiting = false, failed = true, message = text) }
                MountedDriveService.operationFinished(app, title, text, failed = true, jobId = jobId)
            } finally {
                queued.remove(jobId)
                _busy.value = queued.toSet()
                cancel.remove(jobId)
            }
        }
        return true
    }
}

/**
 * "Later" on a due backup: an alarm that starts it after the chosen minutes. Both ends are
 * checked again when it fires, and a drive pulled while waiting drops it, because the user
 * chose that delay for the drive as it was then.
 */
object BackupDelays {
    val CHOICES_MINUTES = listOf(5, 10, 15, 30)

    private fun pending(context: Context, jobId: String): PendingIntent = PendingIntent.getBroadcast(context, jobId.hashCode(),
        Intent(context, BackupActionReceiver::class.java).setAction(BackupActionReceiver.ACTION_DELAYED_START)
            .putExtra(BackupNotifications.EXTRA_JOB_ID, jobId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(context: Context, jobId: String, minutes: Int, now: Long = System.currentTimeMillis()) {
        val at = now + minutes * 60_000L
        val job = JobStore.get(context).update(jobId) { it.copy(delayedStartAt = at, snoozedUntil = null) } ?: return
        BackupNotifications.cancelDue(context, jobId)
        context.getSystemService(android.app.AlarmManager::class.java)
            ?.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pending(context, jobId))
        OtgMasterState.log("Backup \"${job.name}\" will start in $minutes minutes, if its drive stays connected")
    }

    /** The field and the alarm, with no log line: the run is starting, or the job is going. */
    fun clearFor(context: Context, jobId: String) {
        context.getSystemService(android.app.AlarmManager::class.java)?.cancel(pending(context, jobId))
        val store = JobStore.get(context)
        if (store.job(jobId)?.delayedStartAt != null) store.update(jobId) { it.copy(delayedStartAt = null) }
    }

    /** Drops a waiting start because an end went away, and says so. */
    fun drop(context: Context, job: BackupJob, why: String) {
        clearFor(context, job.id)
        OtgMasterState.log("Backup \"${job.name}\" did not start: $why")
    }

    /** The time came. Returns true if the backup started. */
    fun fire(context: Context, jobId: String): Boolean {
        val job = JobStore.get(context).job(jobId) ?: return false
        if (job.delayedStartAt == null) return false
        if (!BackupScheduler.reachable(context, job)) {
            drop(context, job, BackupScheduler.missingEnds(context, job))
            return false
        }
        clearFor(context, jobId)
        return BackupExecutor.start(context, jobId, BackupExecutor.Op.Backup("delayed"))
    }
}

/** A mount prompt waiting for the main screen, and what a notification asked it to open. */
object BackupPrompts {
    data class Due(val driveName: String, val jobIds: List<String>, val laterFirst: Boolean = false)

    private val _due = MutableStateFlow<Due?>(null)
    val due: StateFlow<Due?> = _due
    private val _corrupted = MutableStateFlow<String?>(null)
    /** The job whose "recreate it?" dialog is up. */
    val corrupted: StateFlow<String?> = _corrupted
    private val _progress = MutableStateFlow<String?>(null)
    /** The job whose progress dialog the main screen should open. */
    val progress: StateFlow<String?> = _progress

    /** Set by MainActivity: while it is on screen a prompt is a dialog, otherwise a notification. */
    @Volatile var mainVisible = false

    fun ask(due: Due) { _due.value = due }
    fun answered() { _due.value = null }
    fun showProgress(jobId: String?) { _progress.value = jobId }
    fun askRecreate(jobId: String?) { _corrupted.value = jobId }

    /**
     * A run found the backup damaged. The dialog is what offers the way out. If it went
     * unseen, the row still says Corrupted and a notification brings the user back to it.
     */
    fun corruptionFound(context: Context, job: BackupJob) {
        if (_progress.value != job.id) askRecreate(job.id)
        if (!mainVisible && _progress.value != job.id) BackupNotifications.corrupted(context, job)
    }

    /** What a tap on one of the backup notifications asked for. */
    fun handleIntent(context: Context, intent: Intent?) {
        val i = intent ?: return
        i.getStringExtra(BackupNotifications.EXTRA_PROGRESS_JOB)?.let { showProgress(it); i.removeExtra(BackupNotifications.EXTRA_PROGRESS_JOB) }
        i.getStringExtra(BackupNotifications.EXTRA_CORRUPT_JOB)?.let { askRecreate(it); i.removeExtra(BackupNotifications.EXTRA_CORRUPT_JOB) }
        val dueJob = i.getStringExtra(BackupNotifications.EXTRA_DUE_JOB) ?: return
        val later = i.getBooleanExtra(BackupNotifications.EXTRA_DUE_LATER, false)
        i.removeExtra(BackupNotifications.EXTRA_DUE_JOB)
        val job = JobStore.get(context).job(dueJob) ?: return
        ask(Due(BackupScheduler.driveNames(job), listOf(job.id), laterFirst = later))
    }
}

/**
 * Decides, for every job, what its being due means right now. Runs hourly (WorkManager), and
 * when a drive mounts or goes.
 *
 * A backup on the phone alone starts by itself. A backup using a USB drive never does, since
 * it would slow whatever the user plugged the drive in for: mounting asks in a dialog, coming
 * due with the drive already there asks in a notification, and with the drive away a
 * reminder names it.
 */
object BackupScheduler {
    private const val PERIODIC = "backup-scheduler"
    /** At most one reminder per job per this long. */
    const val REMINDER_SPACING_MS = 20 * 3_600_000L

    /** Jobs asked about since their drive was last away: a second partition does not ask again. */
    private val asked = ConcurrentHashMap.newKeySet<String>()
    /** Jobs whose due-while-mounted notification is up, to swap it when the drive goes. */
    private val dueShown = ConcurrentHashMap.newKeySet<String>()

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

    fun reachable(context: Context, job: BackupJob): Boolean =
        VolumeInfo.isAvailable(context, Uri.parse(job.source.uri)) && VolumeInfo.isAvailable(context, Uri.parse(job.destination.uri))

    /** What is not there, for a log line or a dialog. */
    fun missingEnds(context: Context, job: BackupJob): String =
        listOf(job.source, job.destination).filterNot { VolumeInfo.isAvailable(context, Uri.parse(it.uri)) }
            .joinToString(" and ") { VolumeInfo.summary(it.info) }.ifEmpty { "it can no longer be reached" }
            .let { if (it == "it can no longer be reached") it else "$it is not connected" }

    fun driveNames(job: BackupJob): String = job.driveEnds().joinToString(" and ") { VolumeInfo.summary(it.info) }

    /** Whether any partition of the device behind [end] is mounted now. */
    private fun deviceMounted(end: Endpoint): Boolean = end.kind == VolumeInfo.KIND_OTG && OtgMasterState.mountedDrives.any { d ->
        onSameDevice(VolumeInfo.otgDriveId(Uri.parse(end.uri)), end.info, d.id, ExtensionContract.parseDeviceKey(d.sourceDeviceName))
    }

    /** Same partition, or a recorded serial that says the same USB device. No serial, no match. */
    fun onSameDevice(endDriveId: String?, info: Map<String, String>, driveId: String, key: ExtensionContract.DeviceKey): Boolean {
        if (endDriveId == driveId) return true
        val serial = info[VolumeInfo.OTG_SERIAL]
        return !serial.isNullOrBlank() && serial == key.serial &&
            info[VolumeInfo.OTG_VENDOR] == key.vendorId?.toString() && info[VolumeInfo.OTG_PRODUCT] == key.productId?.toString()
    }

    /** Returns the ids of the jobs it started, for tests. */
    fun evaluate(context: Context, trigger: String, now: Long = System.currentTimeMillis()): List<String> {
        val store = JobStore.get(context)
        val started = ArrayList<String>()
        val prompt = ArrayList<BackupJob>()
        for (job in store.jobs.value) {
            val mounted = job.driveEnds().any(::deviceMounted)
            if (!mounted) asked.remove(job.id)
            val at = job.delayedStartAt
            if (at != null) {
                when {
                    !reachable(context, job) -> BackupDelays.drop(context, job, missingEnds(context, job))
                    now >= at -> if (BackupDelays.fire(context, job.id)) started += job.id
                }
                continue
            }
            if (!job.isDue(now) || job.pendingConfirmation != null || BackupExecutor.isBusy(job.id)) continue
            val there = reachable(context, job)
            if (!job.usesUsb) {
                if (there && job.schedule.autoRun && BackupExecutor.start(context, job.id, BackupExecutor.Op.Backup("scheduled"))) started += job.id
                else remind(context, job, now)
                continue
            }
            if (mounted && trigger == "attach" && asked.add(job.id)) {
                // One end here is enough to ask: the dialog names what else it needs.
                prompt += job
                continue
            }
            if (there) {
                BackupNotifications.cancel(context, BackupNotifications.reminderId(job.id))
                if (trigger != "attach" && spaced(job, now)) {
                    BackupNotifications.dueMounted(context, job)
                    dueShown += job.id
                    store.update(job.id) { it.copy(lastReminderAt = now) }
                }
            } else {
                // The drive went while the due notification was up: this one takes its place at once.
                val swap = dueShown.remove(job.id)
                if (swap) BackupNotifications.cancelDue(context, job.id)
                if (swap || spaced(job, now)) remind(context, job, now)
            }
        }
        if (prompt.isNotEmpty()) {
            val drive = prompt.map(::driveNames).distinct().joinToString(" and ")
            if (BackupPrompts.mainVisible) BackupPrompts.ask(BackupPrompts.Due(drive, prompt.map { it.id }))
            else prompt.forEach { job ->
                if (reachable(context, job)) { BackupNotifications.dueMounted(context, job); dueShown += job.id }
                else remind(context, job, now)
                store.update(job.id) { it.copy(lastReminderAt = now) }
            }
        }
        return started
    }

    private fun spaced(job: BackupJob, now: Long) = job.lastReminderAt == null || now - job.lastReminderAt >= REMINDER_SPACING_MS

    private fun remind(context: Context, job: BackupJob, now: Long) {
        BackupNotifications.reminder(context, job)
        JobStore.get(context).update(job.id) { it.copy(lastReminderAt = now) }
    }

    /** "Not now": no asking for a day, on any path. */
    fun snooze(context: Context, jobId: String, now: Long = System.currentTimeMillis()) {
        BackupDelays.clearFor(context, jobId)
        BackupNotifications.cancelDue(context, jobId)
        dueShown.remove(jobId)
        JobStore.get(context).update(jobId) { it.copy(snoozedUntil = now + BackupActionReceiver.SNOOZE_MS, pendingConfirmation = null) }
    }

    /** The job is going: nothing about it may remain. */
    fun forget(context: Context, jobId: String) {
        asked.remove(jobId); dueShown.remove(jobId)
        BackupDelays.clearFor(context, jobId)
        BackupNotifications.cancelAll(context, jobId)
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
    /** On an intent to MainActivity: open this job's progress dialog. */
    const val EXTRA_PROGRESS_JOB = "app.fayaz.otgmaster.extra.BACKUP_PROGRESS_JOB"
    /** On an intent to MainActivity: open the due dialog for this job. */
    const val EXTRA_DUE_JOB = "app.fayaz.otgmaster.extra.BACKUP_DUE_JOB"
    const val EXTRA_DUE_LATER = "app.fayaz.otgmaster.extra.BACKUP_DUE_LATER"
    /** On an intent to MainActivity: ask whether to recreate this damaged backup. */
    const val EXTRA_CORRUPT_JOB = "app.fayaz.otgmaster.extra.BACKUP_CORRUPT_JOB"

    fun reminderId(jobId: String) = 200_000 + (jobId.hashCode() and 0xffff)
    private fun questionId(jobId: String) = 300_000 + (jobId.hashCode() and 0xffff)
    fun dueId(jobId: String) = 400_000 + (jobId.hashCode() and 0xffff)

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

    fun reminder(context: Context, job: BackupJob) {
        val title = context.getString(if (job.lastSuccessAt == null) R.string.backup_reminder_never else R.string.backup_reminder_title, job.name)
        val text = if (job.usesUsb) context.getString(R.string.backup_reminder_connect, BackupScheduler.driveNames(job))
            else context.getString(R.string.backup_reminder_open)
        post(context, reminderId(job.id), NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_drive)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context, job.id)).setAutoCancel(true).build())
    }

    /** Due with its drive mounted: asks, because a backup would slow what the drive is doing. */
    fun dueMounted(context: Context, job: BackupJob) {
        cancel(context, reminderId(job.id))
        fun action(a: String, req: Int) = PendingIntent.getBroadcast(context, job.id.hashCode() + req,
            Intent(context, BackupActionReceiver::class.java).setAction(a).putExtra(EXTRA_JOB_ID, job.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun openDue(req: Int, later: Boolean) = PendingIntent.getActivity(context, job.id.hashCode() + req,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_DUE_JOB, job.id).putExtra(EXTRA_DUE_LATER, later)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = context.getString(R.string.backup_due_text, job.name)
        post(context, dueId(job.id), NotificationCompat.Builder(context, CHANNEL_QUESTIONS)
            .setSmallIcon(R.drawable.ic_stat_drive)
            .setContentTitle(context.getString(R.string.backup_due_title, BackupScheduler.driveNames(job)))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openDue(10, later = false))
            .setOnlyAlertOnce(true)
            .addAction(0, context.getString(R.string.backup_due_start), action(BackupActionReceiver.ACTION_START_NOW, 11))
            .addAction(0, context.getString(R.string.backup_due_later), openDue(12, later = true))
            .addAction(0, context.getString(R.string.backup_due_not_now), action(BackupActionReceiver.ACTION_NOT_NOW, 13))
            .setAutoCancel(true).build())
    }

    /** Found damaged while nobody was looking. */
    fun corrupted(context: Context, job: BackupJob) {
        val text = context.getString(R.string.backup_corrupted_notification_text)
        post(context, questionId(job.id), NotificationCompat.Builder(context, CHANNEL_QUESTIONS)
            .setSmallIcon(R.drawable.ic_stat_drive)
            .setContentTitle(context.getString(R.string.backup_corrupted_title, job.name)).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(PendingIntent.getActivity(context, job.id.hashCode() + 20,
                Intent(context, MainActivity::class.java).putExtra(EXTRA_CORRUPT_JOB, job.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true).build())
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
    fun cancelDue(context: Context, jobId: String) = cancel(context, dueId(jobId))
    fun cancelAll(context: Context, jobId: String) {
        cancel(context, reminderId(jobId)); cancelQuestion(context, jobId); cancelDue(context, jobId)
    }

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

/** "Proceed" and "Not now" on the low-space question; Start now and Not now on a due backup; a chosen delay running out. */
class BackupActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(BackupNotifications.EXTRA_JOB_ID) ?: return
        BackupNotifications.cancelQuestion(context, jobId)
        when (intent.action) {
            ACTION_PROCEED -> BackupExecutor.start(context, jobId, BackupExecutor.Op.Backup("confirmed", overrideLowSpace = true))
            ACTION_SKIP -> JobStore.get(context).update(jobId) {
                it.copy(pendingConfirmation = null, snoozedUntil = System.currentTimeMillis() + SNOOZE_MS)
            }
            ACTION_START_NOW -> {
                BackupNotifications.cancelDue(context, jobId)
                BackupExecutor.start(context, jobId, BackupExecutor.Op.Backup("manual"))
            }
            ACTION_NOT_NOW -> BackupScheduler.snooze(context, jobId)
            ACTION_DELAYED_START -> BackupDelays.fire(context, jobId)
        }
    }

    companion object {
        const val ACTION_PROCEED = "app.fayaz.otgmaster.action.BACKUP_PROCEED"
        const val ACTION_SKIP = "app.fayaz.otgmaster.action.BACKUP_SKIP"
        const val ACTION_START_NOW = "app.fayaz.otgmaster.action.BACKUP_START_NOW"
        const val ACTION_NOT_NOW = "app.fayaz.otgmaster.action.BACKUP_NOT_NOW"
        const val ACTION_DELAYED_START = "app.fayaz.otgmaster.action.BACKUP_DELAYED_START"
        const val SNOOZE_MS = 24 * 3_600_000L
    }
}

package app.fayaz.otgmaster.backup

import android.content.Context
import app.fayaz.otgmaster.backup.restic.KeepPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Backup jobs, their run history and the snapshot cache: BackupMaster's job layer,
 * moved into OTG Master and started from a drive's More actions.
 *
 * An incremental job writes a real restic repository (backup/restic), so a drive
 * backed up here opens with `restic` on a desktop, and the other way round.
 */
enum class JobType {
    /** Deduplicated restic snapshots in a repository folder; browsable timeline. */
    INCREMENTAL,

    /** A plain copy into a dated folder, readable without any app. */
    ONE_TIME_COPY,
}

/** One end of a job: a folder, as a document tree URI, and what was known about it. */
data class Endpoint(
    val uri: String,
    val label: String,
    /** VolumeInfo.KIND_* */
    val kind: String,
    /** Captured when the job was configured (see VolumeInfo); names the drive to plug in. */
    val info: Map<String, String> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject().put("uri", uri).put("label", label).put("kind", kind).put("info", JSONObject(info))

    companion object {
        fun fromJson(o: JSONObject) = Endpoint(o.getString("uri"), o.optString("label"), o.optString("kind"), o.optJSONObject("info").toStringMap())
    }
}

data class Schedule(
    /** 0 = manual only. */
    val intervalHours: Int = 24,
    /** Start the backup by itself when it is due and both ends are reachable. */
    val autoRun: Boolean = true,
    /** Remind when the newest successful backup is older than this; 0 = never. */
    val remindAfterHours: Int = 72,
) {
    val isManual: Boolean get() = intervalHours <= 0

    fun toJson(): JSONObject = JSONObject().put("intervalHours", intervalHours).put("autoRun", autoRun).put("remindAfterHours", remindAfterHours)

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) Schedule() else
            Schedule(o.optInt("intervalHours", 24), o.optBoolean("autoRun", true), o.optInt("remindAfterHours", 72))
    }
}

data class Retention(val keepLast: Int = 0, val keepDaily: Int = 0, val keepWeekly: Int = 0, val keepMonthly: Int = 0) {
    fun toPolicy() = KeepPolicy(keepLast, keepDaily, keepWeekly, keepMonthly)

    fun toJson(): JSONObject = JSONObject().put("keepLast", keepLast).put("keepDaily", keepDaily)
        .put("keepWeekly", keepWeekly).put("keepMonthly", keepMonthly)

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) Retention() else
            Retention(o.optInt("keepLast"), o.optInt("keepDaily"), o.optInt("keepWeekly"), o.optInt("keepMonthly"))
    }
}

/** A run stopped because the destination would be left nearly full; waits for the user. */
data class PendingConfirmation(
    val neededBytes: Long,
    val freeBytes: Long,
    val capacityBytes: Long?,
    val totalBytes: Long,
    val reason: String,
    val at: Long,
) {
    fun toJson(): JSONObject = JSONObject().put("neededBytes", neededBytes).put("freeBytes", freeBytes)
        .put("capacityBytes", capacityBytes ?: JSONObject.NULL).put("totalBytes", totalBytes).put("reason", reason).put("at", at)

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) null else PendingConfirmation(
            o.getLong("neededBytes"), o.getLong("freeBytes"), o.optLongOrNull("capacityBytes"),
            o.optLong("totalBytes"), o.optString("reason"), o.optLong("at"))
    }
}

data class BackupJob(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: JobType = JobType.INCREMENTAL,
    val source: Endpoint,
    val destination: Endpoint,
    /** Folder inside the destination that holds the restic repository (or the dated copies). */
    val destinationFolder: String,
    val schedule: Schedule = Schedule(),
    val retention: Retention = Retention(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastSnapshotId: String? = null,
    val lastStatus: String? = null,
    val lastMessage: String? = null,
    val lastReminderAt: Long? = null,
    val pendingConfirmation: PendingConfirmation? = null,
    /** "Not now" on a low-space prompt: no automatic run before this time. */
    val snoozedUntil: Long? = null,
    /** The restic repository's config id, to recognise the same repository later. */
    val repositoryId: String? = null,
) {
    val intervalMs: Long get() = schedule.intervalHours * HOUR
    val remindAfterMs: Long get() = schedule.remindAfterHours * HOUR

    /** Due for a scheduled run. */
    fun isDue(now: Long): Boolean = !schedule.isManual && (snoozedUntil == null || now >= snoozedUntil) &&
        (lastSuccessAt == null || now - lastSuccessAt >= intervalMs)

    /** Old enough that the user should be reminded to plug the drive in. */
    fun isOverdue(now: Long): Boolean =
        schedule.remindAfterHours > 0 && now - (lastSuccessAt ?: createdAt) >= remindAfterMs

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("type", type.name)
        put("source", source.toJson()); put("destination", destination.toJson())
        put("destinationFolder", destinationFolder)
        put("schedule", schedule.toJson()); put("retention", retention.toJson())
        put("createdAt", createdAt)
        putOpt("lastRunAt", lastRunAt); putOpt("lastSuccessAt", lastSuccessAt)
        putOpt("lastSnapshotId", lastSnapshotId); putOpt("lastStatus", lastStatus)
        putOpt("lastMessage", lastMessage); putOpt("lastReminderAt", lastReminderAt)
        putOpt("pendingConfirmation", pendingConfirmation?.toJson())
        putOpt("snoozedUntil", snoozedUntil); putOpt("repositoryId", repositoryId)
    }

    companion object {
        const val HOUR = 3_600_000L

        fun fromJson(o: JSONObject) = BackupJob(
            id = o.getString("id"),
            name = o.getString("name"),
            type = runCatching { JobType.valueOf(o.getString("type")) }.getOrDefault(JobType.INCREMENTAL),
            source = Endpoint.fromJson(o.getJSONObject("source")),
            destination = Endpoint.fromJson(o.getJSONObject("destination")),
            destinationFolder = o.optString("destinationFolder"),
            schedule = Schedule.fromJson(o.optJSONObject("schedule")),
            retention = Retention.fromJson(o.optJSONObject("retention")),
            createdAt = o.optLong("createdAt"),
            lastRunAt = o.optLongOrNull("lastRunAt"),
            lastSuccessAt = o.optLongOrNull("lastSuccessAt"),
            lastSnapshotId = o.optStringOrNull("lastSnapshotId"),
            lastStatus = o.optStringOrNull("lastStatus"),
            lastMessage = o.optStringOrNull("lastMessage"),
            lastReminderAt = o.optLongOrNull("lastReminderAt"),
            pendingConfirmation = PendingConfirmation.fromJson(o.optJSONObject("pendingConfirmation")),
            snoozedUntil = o.optLongOrNull("snoozedUntil"),
            repositoryId = o.optStringOrNull("repositoryId"),
        )
    }
}

enum class RunStatus { SUCCESS, WARNING, FAILED, CANCELLED, NEEDS_CONFIRMATION, UNAVAILABLE }

/** Counts for one run, shown in history and the timeline. */
data class RunStats(
    val files: Long = 0,
    val dirs: Long = 0,
    val totalBytes: Long = 0,
    val filesNew: Long = 0,
    val filesUnchanged: Long = 0,
    val bytesRead: Long = 0,
    val bytesAdded: Long = 0,
    val errors: Long = 0,
    val durationMs: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("files", files).put("dirs", dirs).put("totalBytes", totalBytes)
        .put("filesNew", filesNew).put("filesUnchanged", filesUnchanged).put("bytesRead", bytesRead)
        .put("bytesAdded", bytesAdded).put("errors", errors).put("durationMs", durationMs)

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) null else RunStats(
            o.optLong("files"), o.optLong("dirs"), o.optLong("totalBytes"), o.optLong("filesNew"),
            o.optLong("filesUnchanged"), o.optLong("bytesRead"), o.optLong("bytesAdded"),
            o.optLong("errors"), o.optLong("durationMs"))
    }
}

data class RunRecord(
    val id: String = UUID.randomUUID().toString(),
    val jobId: String,
    /** "backup", "copy", "restore", "check", "prune" */
    val kind: String,
    val reason: String,
    val startedAt: Long,
    val finishedAt: Long,
    val status: RunStatus,
    val message: String = "",
    val snapshotId: String? = null,
    val copyFolder: String? = null,
    val stats: RunStats? = null,
    val sourceInfo: Map<String, String> = emptyMap(),
    val destinationInfo: Map<String, String> = emptyMap(),
    val errors: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("jobId", jobId); put("kind", kind); put("reason", reason)
        put("startedAt", startedAt); put("finishedAt", finishedAt); put("status", status.name)
        put("message", message); putOpt("snapshotId", snapshotId); putOpt("copyFolder", copyFolder)
        putOpt("stats", stats?.toJson())
        put("sourceInfo", JSONObject(sourceInfo)); put("destinationInfo", JSONObject(destinationInfo))
        put("errors", JSONArray(errors))
    }

    companion object {
        fun fromJson(o: JSONObject) = RunRecord(
            id = o.getString("id"), jobId = o.getString("jobId"), kind = o.optString("kind"),
            reason = o.optString("reason"), startedAt = o.optLong("startedAt"), finishedAt = o.optLong("finishedAt"),
            status = runCatching { RunStatus.valueOf(o.getString("status")) }.getOrDefault(RunStatus.FAILED),
            message = o.optString("message"), snapshotId = o.optStringOrNull("snapshotId"),
            copyFolder = o.optStringOrNull("copyFolder"), stats = RunStats.fromJson(o.optJSONObject("stats")),
            sourceInfo = o.optJSONObject("sourceInfo").toStringMap(),
            destinationInfo = o.optJSONObject("destinationInfo").toStringMap(),
            errors = o.optJSONArray("errors")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
        )
    }
}

/** What the timeline shows for one snapshot, cached so it renders with the drive unplugged. */
data class SnapshotSummary(
    val id: String,
    val time: Long,
    val parent: String?,
    val hostname: String,
    val tags: List<String>,
    val stats: RunStats?,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("time", time).putOpt("parent", parent)
        .put("hostname", hostname).put("tags", JSONArray(tags)).putOpt("stats", stats?.toJson())

    companion object {
        fun fromJson(o: JSONObject) = SnapshotSummary(
            o.getString("id"), o.getLong("time"), o.optStringOrNull("parent"), o.optString("hostname"),
            o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            RunStats.fromJson(o.optJSONObject("stats")))
    }
}

/**
 * Jobs, run history and the snapshot cache, as JSON files in app storage.
 *
 * Small and rewritten whole, atomically (temp file, then rename), under one lock.
 * Observable through [jobs] for the UI.
 */
class JobStore internal constructor(private val dir: File) {
    private val jobsFile = File(dir, "jobs.json")
    private val historyDir = File(dir, "history").apply { mkdirs() }
    private val snapDir = File(dir, "snapshots").apply { mkdirs() }
    private val lock = Any()

    private val _jobs = MutableStateFlow(loadJobs())
    val jobs: StateFlow<List<BackupJob>> = _jobs

    /** Bumped whenever history or a snapshot cache changes, so screens can reload. */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    private fun loadJobs(): List<BackupJob> = runCatching {
        val a = JSONArray(jobsFile.readText())
        (0 until a.length()).map { BackupJob.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun atomicWrite(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "could not write $file" } }
    }

    fun job(id: String): BackupJob? = _jobs.value.find { it.id == id }

    fun upsert(job: BackupJob) = synchronized(lock) {
        val list = _jobs.value.filter { it.id != job.id } + job
        atomicWrite(jobsFile, JSONArray(list.map { it.toJson() }).toString())
        _jobs.value = list
    }

    fun update(id: String, change: (BackupJob) -> BackupJob): BackupJob? = synchronized(lock) {
        val current = job(id) ?: return null
        change(current).also { upsert(it) }
    }

    fun delete(id: String) = synchronized(lock) {
        val list = _jobs.value.filter { it.id != id }
        atomicWrite(jobsFile, JSONArray(list.map { it.toJson() }).toString())
        _jobs.value = list
        File(historyDir, "$id.json").delete()
        File(snapDir, "$id.json").delete()
        _revision.value++
    }

    fun history(jobId: String): List<RunRecord> = synchronized(lock) {
        runCatching {
            val a = JSONArray(File(historyDir, "$jobId.json").readText())
            (0 until a.length()).map { RunRecord.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun addRun(record: RunRecord) = synchronized(lock) {
        val list = (history(record.jobId) + record).takeLast(MAX_HISTORY)
        atomicWrite(File(historyDir, "${record.jobId}.json"), JSONArray(list.map { it.toJson() }).toString())
        _revision.value++
    }

    fun snapshots(jobId: String): List<SnapshotSummary> = synchronized(lock) {
        runCatching {
            val a = JSONArray(File(snapDir, "$jobId.json").readText())
            (0 until a.length()).map { SnapshotSummary.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun setSnapshots(jobId: String, list: List<SnapshotSummary>) = synchronized(lock) {
        atomicWrite(File(snapDir, "$jobId.json"), JSONArray(list.sortedBy { it.time }.map { it.toJson() }).toString())
        _revision.value++
    }

    companion object {
        const val MAX_HISTORY = 500

        @Volatile private var instance: JobStore? = null

        fun get(context: Context): JobStore = instance ?: synchronized(this) {
            instance ?: JobStore(File(context.applicationContext.filesDir, "backup").apply { mkdirs() }).also { instance = it }
        }
    }
}

internal fun JSONObject?.toStringMap(): Map<String, String> =
    if (this == null) emptyMap() else keys().asSequence().associateWith { getString(it) }

internal fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null

internal fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

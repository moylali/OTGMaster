package app.fayaz.otgmaster.backup

import android.content.ContentProviderClient
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import app.fayaz.otgmaster.BuildConfig
import app.fayaz.otgmaster.backup.restic.CancelledException
import app.fayaz.otgmaster.backup.restic.FileTreeBackend
import app.fayaz.otgmaster.backup.restic.FileTreeSource
import app.fayaz.otgmaster.backup.restic.ResticMaintenance
import app.fayaz.otgmaster.backup.restic.ResticRepository
import app.fayaz.otgmaster.backup.restic.ResticRestore
import app.fayaz.otgmaster.backup.restic.ResticWriter
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale

/** Live progress for the notification and the job screen. */
data class RunProgress(
    val phase: String,
    val files: Long,
    val bytes: Long,
    val current: String,
    val totalFiles: Long = 0,
    val totalBytes: Long = 0,
)

sealed class RunOutcome {
    data class Success(val record: RunRecord) : RunOutcome()
    data class NeedsConfirmation(val pending: PendingConfirmation) : RunOutcome()
    data class Failed(val message: String, val status: RunStatus = RunStatus.FAILED) : RunOutcome()
}

/**
 * Runs one job end to end. The UI, the scheduler and the tests all go through here.
 *
 * A backup run, in order:
 * 1. both ends reachable (a drive must be mounted and unlocked),
 * 2. fresh device and volume details captured for the record,
 * 3. preflight: the source measured against the newest snapshot, the destination's
 *    free space checked; refused if it cannot fit, held for confirmation if it would
 *    leave under 10% free. Nothing is created on the destination before this passes,
 * 4. the restic backup (or the plain copy), then the job's keep rules,
 * 5. history, job state and the timeline cache updated.
 */
class BackupRunner(private val context: Context) {
    private val store = JobStore.get(context)
    private val secrets = BackupSecrets(context)
    private val hostname = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    fun interface Listener { fun onProgress(p: RunProgress) }

    /** Cancellation and progress hooks from the caller (worker, test). */
    class Control(val isCancelled: () -> Boolean = { false }, val listener: Listener = Listener { })

    /** Opens, or with [create] initialises, the restic repository of [job]. */
    fun repository(job: BackupJob, create: Boolean = false): ResticRepository {
        val dst = Uri.parse(job.destination.uri)
        if (!VolumeInfo.isAvailable(context, dst)) throw IllegalStateException(unavailableMessage("Destination", job.destination.info))
        val password = secrets.password(job.id)
            ?: throw IllegalStateException("The password of this job's backups is not on this phone. Enter it in the job's settings.")
        val root = SafFileTree(context.contentResolver, dst)
        val backend = FileTreeBackend(SubTree(root, job.destinationFolder))
        if (backend.exists("config")) {
            return ResticRepository.open(backend, password.toByteArray())
                ?: throw IllegalStateException("The saved password does not open the restic repository in ${job.destinationFolder}.")
        }
        if (!create) throw IllegalStateException("There is no restic repository in ${job.destinationFolder} yet.")
        root.mkdirs(job.destinationFolder)
        return ResticWriter.init(backend, password.toByteArray(), hostname, USERNAME)
    }

    fun backup(jobId: String, reason: String, overrideLowSpace: Boolean = false, control: Control = Control()): RunOutcome {
        val job = store.job(jobId) ?: return RunOutcome.Failed("job no longer exists")
        val started = System.currentTimeMillis()
        val srcUri = Uri.parse(job.source.uri)
        val dstUri = Uri.parse(job.destination.uri)

        fun fail(message: String, status: RunStatus = RunStatus.FAILED, src: Map<String, String> = emptyMap(),
                 dst: Map<String, String> = emptyMap()): RunOutcome {
            store.addRun(RunRecord(jobId = job.id, kind = kindOf(job), reason = reason, startedAt = started,
                finishedAt = System.currentTimeMillis(), status = status, message = message, sourceInfo = src, destinationInfo = dst))
            store.update(job.id) { it.copy(lastRunAt = started, lastStatus = status.name, lastMessage = message) }
            app.fayaz.otgmaster.OtgMasterState.log("Backup \"${job.name}\": ${status.name.lowercase()}: $message")
            return RunOutcome.Failed(message, status)
        }

        if (!VolumeInfo.isAvailable(context, srcUri)) return fail(unavailableMessage("Source", job.source.info), RunStatus.UNAVAILABLE)
        if (!VolumeInfo.isAvailable(context, dstUri)) return fail(unavailableMessage("Destination", job.destination.info), RunStatus.UNAVAILABLE)

        val clients = holdProviders(srcUri, dstUri)
        try {
            val srcInfo = VolumeInfo.capture(context, srcUri)
            val dstInfo = VolumeInfo.capture(context, dstUri)
            if (dstInfo[VolumeInfo.OTG_READ_ONLY] == "1") return fail("The destination drive is mounted read-only.", src = srcInfo, dst = dstInfo)

            val source = SafFileTree(context.contentResolver, srcUri)
            val destRoot = SafFileTree(context.contentResolver, dstUri)
            val excludes = repositoryInsideSource(srcUri, dstUri, job.destinationFolder)?.let { setOf(it) } ?: emptySet()

            control.listener.onProgress(RunProgress("scan", 0, 0, ""))
            // Measure against an existing repository's newest snapshot of this job, but
            // create nothing until the space check passes: a refused run leaves the
            // destination untouched.
            val incremental = job.type == JobType.INCREMENTAL
            val existing = if (incremental && destRoot.stat(Paths.join(job.destinationFolder, "config")) != null) repository(job) else null
            val parent = existing?.let { latestOf(job, it) }
            val parentTree = parent?.let { p -> subtreeAt(existing, p.tree, snapshotPath(job)) }
            val scan = Preflight.scan(source, existing, parentTree, excludes, control.isCancelled) { f, b ->
                control.listener.onProgress(RunProgress("scan", f, b, ""))
            }

            val space = Preflight.checkSpace(scan.neededBytes, dstInfo[VolumeInfo.FREE]?.toLongOrNull(), dstInfo[VolumeInfo.CAPACITY]?.toLongOrNull())
            when (space.verdict) {
                Preflight.Verdict.INSUFFICIENT -> return fail(
                    "Not enough space on the destination: needs ${Format.bytes(scan.neededBytes)}, ${Format.bytes(space.freeBytes ?: 0)} free.",
                    src = srcInfo, dst = dstInfo)
                Preflight.Verdict.LOW_SPACE_AFTER -> if (!overrideLowSpace) {
                    val pending = PendingConfirmation(scan.neededBytes, space.freeBytes!!, space.capacityBytes, scan.totalBytes, reason, System.currentTimeMillis())
                    store.update(job.id) { it.copy(pendingConfirmation = pending, lastStatus = RunStatus.NEEDS_CONFIRMATION.name, lastMessage = lowSpaceMessage(pending)) }
                    store.addRun(RunRecord(jobId = job.id, kind = kindOf(job), reason = reason, startedAt = started,
                        finishedAt = System.currentTimeMillis(), status = RunStatus.NEEDS_CONFIRMATION,
                        message = lowSpaceMessage(pending), sourceInfo = srcInfo, destinationInfo = dstInfo))
                    return RunOutcome.NeedsConfirmation(pending)
                }
                else -> Unit
            }
            store.update(job.id) { it.copy(pendingConfirmation = null) }

            val record = if (incremental) {
                val repo = existing ?: repository(job, create = true)
                val w = ResticWriter(repo, hostname, USERNAME, "OTG Master ${BuildConfig.VERSION_NAME}")
                w.isCancelled = control.isCancelled
                var last = 0L
                w.onProgress = { f, b, name ->
                    val now = System.currentTimeMillis()
                    if (now - last >= 250) { last = now; control.listener.onProgress(RunProgress("backup", f, b, name, scan.files, scan.neededBytes)) }
                }
                val t0 = System.currentTimeMillis()
                val id = w.backup(FileTreeSource.root(source).excluding(excludes), snapshotPath(job), tagsOf(job, reason), parent)
                val stats = RunStats(files = w.filesDone, dirs = w.dirsDone, totalBytes = scan.totalBytes, filesNew = w.filesNew,
                    filesUnchanged = w.filesUnchanged, bytesRead = w.bytesRead, bytesAdded = w.bytesAdded,
                    errors = w.errors.size.toLong(), durationMs = System.currentTimeMillis() - t0)
                var message = "${stats.files} files, ${Format.bytes(stats.bytesAdded)} added"
                val policy = job.retention.toPolicy()
                if (!policy.keepsEverything) {
                    val m = ResticMaintenance(repo, hostname, USERNAME)
                    val drop = m.toForget(snapshotsOf(job, repo), policy)
                    if (drop.isNotEmpty()) {
                        control.listener.onProgress(RunProgress("prune", 0, 0, ""))
                        m.forget(drop.map { it.id })
                        val pr = m.prune()
                        message += "; removed ${drop.size} old snapshot(s), freed ${Format.bytes(pr.bytesFreed)}"
                    }
                }
                if (w.errors.isNotEmpty()) message += "; ${w.errors.size} item(s) could not be read"
                refreshSnapshotCache(job, repo)
                store.update(job.id) { it.copy(repositoryId = repo.config.optString("id")) }
                RunRecord(jobId = job.id, kind = "backup", reason = reason, startedAt = started,
                    finishedAt = System.currentTimeMillis(), status = if (w.errors.isEmpty()) RunStatus.SUCCESS else RunStatus.WARNING,
                    message = message, snapshotId = id, stats = stats, sourceInfo = srcInfo, destinationInfo = dstInfo,
                    errors = w.errors.take(CopyEngine.MAX_ERRORS))
            } else {
                val folder = Paths.join(job.destinationFolder, copyFolderName(started))
                val manifest = CopyEngine.copy(source, destRoot, folder, CopyEngine.Options(
                    jobId = job.id, jobName = job.name, hostname = hostname, sourceInfo = srcInfo,
                    destinationInfo = dstInfo, appVersion = BuildConfig.VERSION_NAME, excludes = excludes,
                ), progressBridge(control, scan.files, scan.totalBytes))
                val stats = RunStats(files = manifest.files.size.toLong(), dirs = manifest.dirs, totalBytes = manifest.totalBytes,
                    filesNew = manifest.files.size.toLong(), bytesRead = manifest.totalBytes, bytesAdded = manifest.totalBytes,
                    errors = manifest.errors.size.toLong(), durationMs = manifest.durationMs)
                RunRecord(jobId = job.id, kind = "copy", reason = reason, startedAt = started,
                    finishedAt = System.currentTimeMillis(), status = if (manifest.errors.isEmpty()) RunStatus.SUCCESS else RunStatus.WARNING,
                    message = "Copied ${manifest.files.size} files (${Format.bytes(manifest.totalBytes)}) to $folder",
                    copyFolder = folder, stats = stats, sourceInfo = srcInfo, destinationInfo = dstInfo, errors = manifest.errors)
            }
            store.addRun(record)
            store.update(job.id) {
                it.copy(lastRunAt = started, lastSuccessAt = started, lastSnapshotId = record.snapshotId ?: it.lastSnapshotId,
                    lastStatus = record.status.name, lastMessage = record.message, pendingConfirmation = null, snoozedUntil = null)
            }
            app.fayaz.otgmaster.OtgMasterState.log("Backup \"${job.name}\": ${record.message}")
            record.errors.take(5).forEach { app.fayaz.otgmaster.OtgMasterState.log("Backup \"${job.name}\": not read: $it") }
            return RunOutcome.Success(record)
        } catch (e: CancelledException) {
            return fail("Cancelled", RunStatus.CANCELLED)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "backup ${job.name} failed", e)
            val reachable = VolumeInfo.isAvailable(context, srcUri) && VolumeInfo.isAvailable(context, dstUri)
            val why = "${e.javaClass.simpleName}: ${e.message}" + (e.cause?.let { " (cause: ${it.javaClass.simpleName}: ${it.message})" } ?: "")
            return fail(if (reachable) why else "A drive went away during the run: $why")
        } finally {
            clients.forEach { runCatching { it.close() } }
        }
    }

    /** This job's snapshots in [repo], oldest first. Others (a desktop's, another job's) are left alone. */
    fun snapshotsOf(job: BackupJob, repo: ResticRepository): List<ResticRepository.Snapshot> =
        repo.snapshots().filter { s -> s.json.optJSONArray("tags")?.let { a -> (0 until a.length()).any { a.getString(it) == jobTag(job) } } == true }

    private fun latestOf(job: BackupJob, repo: ResticRepository) =
        snapshotsOf(job, repo).maxByOrNull { OffsetDateTime.parse(it.time).toInstant() }

    /** Re-reads this job's snapshots into the timeline cache. */
    fun refreshSnapshotCache(job: BackupJob, repo: ResticRepository = repository(job)) {
        store.setSnapshots(job.id, snapshotsOf(job, repo).map { s ->
            val sum = s.json.optJSONObject("summary")
            SnapshotSummary(
                id = s.id,
                time = OffsetDateTime.parse(s.time).toInstant().toEpochMilli(),
                parent = s.json.optStringOrNull("parent"),
                hostname = s.hostname,
                tags = s.json.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                stats = sum?.let {
                    RunStats(files = it.optLong("total_files_processed"), filesNew = it.optLong("files_new"),
                        filesUnchanged = it.optLong("files_unmodified"), bytesRead = it.optLong("total_bytes_processed"),
                        bytesAdded = it.optLong("data_added"))
                },
            )
        })
    }

    /**
     * Restores [subPath] of a snapshot to [targetPath] in [targetUri]. With [targetUri]
     * null it goes back into the job's source folder, and [safetySnapshot] backs the
     * current state up first so that can be undone too.
     */
    fun restore(jobId: String, snapshotId: String, subPath: String, targetUri: Uri?, targetPath: String,
                safetySnapshot: Boolean, control: Control = Control()): RunOutcome {
        val job = store.job(jobId) ?: return RunOutcome.Failed("job no longer exists")
        val started = System.currentTimeMillis()
        val intoSource = targetUri == null
        val target = targetUri ?: Uri.parse(job.source.uri)
        fun record(status: RunStatus, message: String, errors: List<String> = emptyList()) =
            RunRecord(jobId = job.id, kind = "restore", reason = if (intoSource) "switch" else "restore", startedAt = started,
                finishedAt = System.currentTimeMillis(), status = status, message = message, snapshotId = snapshotId, errors = errors)
                .also { store.addRun(it); app.fayaz.otgmaster.OtgMasterState.log("Restore \"${job.name}\": $message") }
        return try {
            if (!VolumeInfo.isAvailable(context, target)) return RunOutcome.Failed("The restore target is not reachable.").also { record(RunStatus.UNAVAILABLE, it.message) }
            if (intoSource && safetySnapshot && job.type == JobType.INCREMENTAL) {
                control.listener.onProgress(RunProgress("safety snapshot", 0, 0, ""))
                val out = backup(jobId, "pre-restore", overrideLowSpace = true, control = control)
                if (out is RunOutcome.Failed) return RunOutcome.Failed("Safety snapshot failed, nothing was restored: ${out.message}").also { record(RunStatus.FAILED, it.message) }
            }
            val clients = holdProviders(target, Uri.parse(job.destination.uri))
            try {
                val repo = repository(job)
                val snap = repo.snapshots().firstOrNull { it.id == snapshotId } ?: throw IllegalStateException("snapshot ${snapshotId.take(8)} is gone")
                val r = ResticRestore(repo)
                val tree = SafFileTree(context.contentResolver, target)
                val inSnapshot = Paths.join(snapshotPath(job).trim('/'), subPath.trim('/'))
                val node = r.find(snap, inSnapshot) ?: throw IllegalStateException("$subPath is not in that snapshot")
                if (control.isCancelled()) throw CancelledException()
                r.restore(node, tree, targetPath.ifEmpty { node.name })
                RunOutcome.Success(record(RunStatus.SUCCESS, "Restored ${r.filesRestored} files (${Format.bytes(r.bytesRestored)})"))
            } finally {
                clients.forEach { runCatching { it.close() } }
            }
        } catch (e: CancelledException) {
            RunOutcome.Failed("Cancelled", RunStatus.CANCELLED).also { record(RunStatus.CANCELLED, "Cancelled") }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "restore failed", e)
            RunOutcome.Failed("${e.javaClass.simpleName}: ${e.message}").also { record(RunStatus.FAILED, it.message) }
        }
    }

    /**
     * Checks a job's backups: every snapshot's trees must load and every blob they name
     * must be in the index; with [readData], every blob of every pack is decrypted and
     * hashed against its id (`restic check --read-data`). A one-time copy is re-hashed
     * against its manifest.
     */
    fun check(jobId: String, readData: Boolean, control: Control = Control()): RunOutcome {
        val job = store.job(jobId) ?: return RunOutcome.Failed("job no longer exists")
        val started = System.currentTimeMillis()
        return try {
            val problems = ArrayList<String>()
            val message: String
            if (job.type == JobType.INCREMENTAL) {
                val repo = repository(job)
                val seen = HashSet<String>()
                var blobs = 0L
                fun walk(treeId: String) {
                    if (control.isCancelled()) throw CancelledException()
                    if (!seen.add(treeId)) return
                    val nodes = runCatching { repo.tree(treeId) }.getOrElse { problems += "tree ${treeId.take(8)}: ${it.message}"; return }
                    for (n in nodes) {
                        n.subtree?.let(::walk)
                        for (id in n.content) {
                            if (!seen.add(id)) continue
                            blobs++
                            if (!repo.hasBlob(id)) problems += "${n.name}: blob ${id.take(8)} missing from the index"
                            else if (readData) runCatching { repo.blob(id) }.onFailure { problems += "${n.name}: ${it.message}" }
                            if (blobs % 100 == 0L) control.listener.onProgress(RunProgress("check", blobs, 0, n.name))
                        }
                    }
                }
                val snaps = repo.snapshots()
                snaps.forEach { walk(it.tree) }
                message = if (problems.isEmpty()) "Repository OK: ${snaps.size} snapshots, $blobs data blobs${if (readData) " read and verified" else " indexed"}"
                    else "${problems.size} problem(s) found"
            } else {
                val last = store.history(jobId).lastOrNull { it.copyFolder != null } ?: return RunOutcome.Failed("No copy to verify yet")
                val root = SafFileTree(context.contentResolver, Uri.parse(job.destination.uri))
                problems += CopyEngine.verify(root, last.copyFolder!!, progressBridge(control))
                message = if (problems.isEmpty()) "Copy ${last.copyFolder} verified" else "${problems.size} file(s) differ"
            }
            val status = if (problems.isEmpty()) RunStatus.SUCCESS else RunStatus.FAILED
            val rec = RunRecord(jobId = jobId, kind = "check", reason = "manual", startedAt = started,
                finishedAt = System.currentTimeMillis(), status = status, message = message, errors = problems.take(CopyEngine.MAX_ERRORS))
            store.addRun(rec)
            app.fayaz.otgmaster.OtgMasterState.log("Check \"${job.name}\": $message")
            problems.take(5).forEach { app.fayaz.otgmaster.OtgMasterState.log("Check \"${job.name}\": $it") }
            if (status == RunStatus.SUCCESS) RunOutcome.Success(rec) else RunOutcome.Failed(message)
        } catch (e: Exception) {
            RunOutcome.Failed("${e.javaClass.simpleName}: ${e.message}").also {
                store.addRun(RunRecord(jobId = jobId, kind = "check", reason = "manual", startedAt = started,
                    finishedAt = System.currentTimeMillis(), status = RunStatus.FAILED, message = it.message))
                app.fayaz.otgmaster.OtgMasterState.log("Check \"${job.name}\": failed: ${it.message}")
            }
        }
    }

    private fun subtreeAt(repo: ResticRepository, root: String, path: String): String? {
        var tree: String? = root
        for (part in path.trim('/').split('/').filter { it.isNotEmpty() }) {
            tree = tree?.let { id -> runCatching { repo.tree(id) }.getOrNull()?.firstOrNull { it.name == part }?.subtree }
        }
        return tree
    }

    private fun holdProviders(vararg uris: Uri): List<ContentProviderClient> =
        uris.mapNotNull { it.authority }.distinct().mapNotNull { context.contentResolver.acquireContentProviderClient(it) }

    private fun progressBridge(control: Control, totalFiles: Long = 0, totalBytes: Long = 0) = object : Progress {
        private var last = 0L
        override fun update(phase: String, files: Long, bytes: Long, current: String) {
            val now = System.currentTimeMillis()
            if (now - last < 250) return
            last = now
            control.listener.onProgress(RunProgress(phase, files, bytes, current, totalFiles, totalBytes))
        }
        override fun isCancelled() = control.isCancelled()
    }

    companion object {
        const val TAG = "OTGBackup"
        const val USERNAME = "otgmaster"

        fun kindOf(job: BackupJob) = if (job.type == JobType.INCREMENTAL) "backup" else "copy"

        /** The tag that marks a job's snapshots, so its keep rules never touch anyone else's. */
        fun jobTag(job: BackupJob) = "otg-job:${job.id}"

        fun tagsOf(job: BackupJob, reason: String) = listOf(jobTag(job), "otgmaster", "reason:$reason")

        /**
         * The path a job's snapshots record: "/" and the source folder's label, so
         * `restic snapshots` on a desktop shows what was backed up, and so restic there
         * picks the same parent when it backs up the same folder.
         */
        fun snapshotPath(job: BackupJob): String =
            "/" + job.source.label.replace('/', '_').trim().ifEmpty { "phone" }

        fun copyFolderName(time: Long): String = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date(time))

        fun lowSpaceMessage(p: PendingConfirmation): String {
            val after = p.freeBytes - p.neededBytes
            val pct = p.capacityBytes?.takeIf { it > 0 }?.let { " (${"%.1f".format(Locale.US, after * 100.0 / it)}%)" } ?: ""
            return "This backup may write up to ${Format.bytes(p.neededBytes)} and leave only ${Format.bytes(after)}$pct free on the destination."
        }

        fun unavailableMessage(which: String, info: Map<String, String>): String =
            if (info[VolumeInfo.KIND] == VolumeInfo.KIND_OTG)
                "$which drive is not mounted. Plug in ${VolumeInfo.summary(info)} and unlock it."
            else "$which folder is not reachable (${info[VolumeInfo.PATH] ?: "permission revoked?"})."

        /**
         * Where the repository sits inside the source tree, relative to it, when the
         * destination is a folder within the source. Backing that up would copy the
         * repository into itself.
         */
        fun repositoryInsideSource(source: Uri, destination: Uri, folder: String): String? {
            if (source.authority != destination.authority) return null
            val s = runCatching { DocumentsContract.getTreeDocumentId(source) }.getOrNull() ?: return null
            val d = runCatching { DocumentsContract.getTreeDocumentId(destination) }.getOrNull() ?: return null
            val rel = when {
                d == s -> ""
                s.endsWith(":") || s.endsWith("/") -> if (d.startsWith(s)) d.removePrefix(s) else return null
                d.startsWith("$s/") -> d.removePrefix("$s/")
                else -> return null
            }.trim('/')
            return Paths.join(rel, folder.trim('/')).trim('/').ifEmpty { null }
        }
    }
}

object Format {
    fun bytes(b: Long): String {
        if (b < 1024) return "$b B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = b / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    fun ago(ms: Long?, now: Long = System.currentTimeMillis()): String {
        if (ms == null) return "never"
        val d = now - ms
        return when {
            d < 60_000 -> "just now"
            d < 3_600_000 -> "${d / 60_000} min ago"
            d < 86_400_000 -> "${d / 3_600_000} h ago"
            else -> "${d / 86_400_000} days ago"
        }
    }

    fun dateTime(ms: Long): String = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))
}

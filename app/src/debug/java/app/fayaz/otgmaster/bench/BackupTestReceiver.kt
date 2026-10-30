package app.fayaz.otgmaster.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.util.Log
import app.fayaz.otgmaster.OtgMasterState
import app.fayaz.otgmaster.backup.BackupJob
import app.fayaz.otgmaster.backup.BackupRunner
import app.fayaz.otgmaster.backup.BackupSecrets
import app.fayaz.otgmaster.backup.Endpoint
import app.fayaz.otgmaster.backup.JobStore
import app.fayaz.otgmaster.backup.JobType
import app.fayaz.otgmaster.backup.Retention
import app.fayaz.otgmaster.backup.RunOutcome
import app.fayaz.otgmaster.backup.Schedule
import app.fayaz.otgmaster.backup.VolumeInfo
import app.fayaz.otgmaster.extension.ExtensionContract

/**
 * Debug-only: runs a backup between two mounted partitions with no UI, for device tests
 * whose result is then checked by restic itself on the host.
 *
 *   adb shell am broadcast -n app.fayaz.otgmaster/.bench.BackupTestReceiver \
 *     -a app.fayaz.otgmaster.RUN_BACKUP --es src LABEL --es srcPath BENCH/edge \
 *     --es dst LABEL --es folder restic-test --es password PW [--es op backup|check|restore] [--ez copy true]
 *
 * Partitions are found by volume label. The job is kept (named "test <folder>"), so a
 * second broadcast is an incremental run with the first as parent. Results are logged
 * under the tag OTGBackupTest and in the app's log.
 */
class BackupTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try { run(app, intent) } catch (t: Throwable) { Log.e(TAG, "failed", t); Log.i(TAG, "RESULT FAILED ${t.javaClass.simpleName}: ${t.message}") }
            finally { pending.finish() }
        }.start()
    }

    private fun run(context: Context, intent: Intent) {
        fun drive(label: String) = OtgMasterState.mountedDrives.firstOrNull {
            runCatching { it.fileSystem.volumeLabel }.getOrNull()?.trim() == label
        } ?: throw IllegalStateException("no mounted partition labelled $label")
        val src = drive(intent.getStringExtra("src")!!)
        val dst = drive(intent.getStringExtra("dst")!!)
        val srcPath = intent.getStringExtra("srcPath").orEmpty().trim('/')
        val folder = intent.getStringExtra("folder") ?: "restic-test"
        val srcUri = DocumentsContract.buildTreeDocumentUri(ExtensionContract.AUTHORITY, "${src.id}:/$srcPath")
        val dstUri = DocumentsContract.buildTreeDocumentUri(ExtensionContract.AUTHORITY, "${dst.id}:/")
        val store = JobStore.get(context)
        val name = "test $folder"
        val job = store.jobs.value.firstOrNull { it.name == name } ?: BackupJob(
            name = name,
            type = if (intent.getBooleanExtra("copy", false)) JobType.ONE_TIME_COPY else JobType.INCREMENTAL,
            source = Endpoint(srcUri.toString(), srcPath.substringAfterLast('/').ifEmpty { "root" }, VolumeInfo.KIND_OTG, VolumeInfo.capture(context, srcUri, false)),
            destination = Endpoint(dstUri.toString(), dst.name, VolumeInfo.KIND_OTG, VolumeInfo.capture(context, dstUri, false)),
            destinationFolder = folder,
            schedule = Schedule(intervalHours = 0, autoRun = false, remindAfterHours = 0),
            retention = Retention(),
        ).also { store.upsert(it) }
        intent.getStringExtra("password")?.let { BackupSecrets(context).setPassword(job.id, it) }
        val runner = BackupRunner(context)
        if (intent.getStringExtra("op") == "export") {
            // Copies the repository folder (and a restore beside it, if any) off the drive into
            // app storage, so adb can pull a few MB instead of the whole partition.
            val out = java.io.File(context.getExternalFilesDir(null), "export").apply { deleteRecursively(); mkdirs() }
            val from = app.fayaz.otgmaster.backup.SafFileTree(context.contentResolver, dstUri)
            val to = app.fayaz.otgmaster.backup.LocalFileTree(out)
            var files = 0
            fun copy(path: String) {
                for (e in from.list(path)) {
                    val p = app.fayaz.otgmaster.backup.Paths.join(path, e.name)
                    if (e.isDirectory) { to.mkdirs(p); copy(p) }
                    else { from.openRead(p).use { i -> to.openWrite(p).use { o -> i.copyTo(o) } }; files++ }
                }
            }
            for (dir in listOf(folder, "$folder-restored")) if (from.stat(dir)?.isDirectory == true) { to.mkdirs(dir); copy(dir) }
            Log.i(TAG, "RESULT EXPORTED $files files to $out")
            return
        }
        val outcome = when (intent.getStringExtra("op") ?: "backup") {
            "check" -> runner.check(job.id, readData = true)
            "restore" -> {
                val snap = store.snapshots(job.id).maxByOrNull { it.time } ?: throw IllegalStateException("no snapshot")
                val target = DocumentsContract.buildTreeDocumentUri(ExtensionContract.AUTHORITY, "${dst.id}:/")
                runner.restore(job.id, snap.id, "", target, intent.getStringExtra("targetPath") ?: "$folder-restored", safetySnapshot = false)
            }
            else -> runner.backup(job.id, "test", overrideLowSpace = true)
        }
        when (outcome) {
            is RunOutcome.Success -> Log.i(TAG, "RESULT ${outcome.record.status} ${outcome.record.message} snapshot=${outcome.record.snapshotId} errors=${outcome.record.errors}")
            is RunOutcome.Failed -> Log.i(TAG, "RESULT FAILED ${outcome.status}: ${outcome.message}")
            is RunOutcome.NeedsConfirmation -> Log.i(TAG, "RESULT NEEDS_CONFIRMATION")
        }
    }

    companion object { const val TAG = "OTGBackupTest" }
}

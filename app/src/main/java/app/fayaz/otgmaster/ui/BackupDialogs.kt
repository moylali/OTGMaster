package app.fayaz.otgmaster.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.fayaz.otgmaster.R
import app.fayaz.otgmaster.backup.BackupExecutor
import app.fayaz.otgmaster.backup.BackupJob
import app.fayaz.otgmaster.backup.BackupSecrets
import app.fayaz.otgmaster.backup.Endpoint
import app.fayaz.otgmaster.backup.Format
import app.fayaz.otgmaster.backup.JobStore
import app.fayaz.otgmaster.backup.JobType
import app.fayaz.otgmaster.backup.Retention
import app.fayaz.otgmaster.backup.Schedule
import app.fayaz.otgmaster.backup.VolumeInfo
import app.fayaz.otgmaster.extension.ExtensionContract

/** The tree URI of a mounted partition's root, as a backup endpoint names it. */
fun driveTreeUri(driveId: String): Uri = DocumentsContract.buildTreeDocumentUri(ExtensionContract.AUTHORITY, "$driveId:/")

/** Jobs that use the partition [driveId] at either end. */
fun jobsForDrive(jobs: List<BackupJob>, driveId: String): List<BackupJob> =
    jobs.filter { VolumeInfo.otgDriveId(Uri.parse(it.destination.uri)) == driveId || VolumeInfo.otgDriveId(Uri.parse(it.source.uri)) == driveId }

/**
 * "Back up…" on a mounted partition: a phone folder backed up to a folder on this drive,
 * as restic snapshots (opens with `restic` on a computer) or as plain dated copies.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupSetupDialog(driveId: String, driveTitle: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceLabel by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    var incremental by remember { mutableStateOf(true) }
    var intervalHours by remember { mutableStateOf(24) }
    var password by remember { mutableStateOf(BackupSecrets.generate()) }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val pickSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { app.fayaz.otgmaster.OtgMasterState.logFailure("backup-grant", "Could not keep access to the chosen folder", it) }
        sourceUri = uri
        sourceLabel = VolumeInfo.displayPath(context, uri).substringAfterLast('/')
        if (name.isBlank()) name = sourceLabel
        if (folder.isBlank()) folder = "Backups/${sourceLabel.ifEmpty { "Phone" }}"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_setup_title, driveTitle)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickSource.launch(null) }, modifier = Modifier.semantics { contentDescription = "backup_pick_source" }) {
                    Text(if (sourceUri == null) stringResource(R.string.backup_pick_source) else VolumeInfo.displayPath(context, sourceUri!!))
                }
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.backup_name)) }, singleLine = true)
                OutlinedTextField(folder, { folder = it }, label = { Text(stringResource(R.string.backup_folder)) }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(incremental, { incremental = true }, { Text(stringResource(R.string.backup_type_restic)) })
                    FilterChip(!incremental, { incremental = false }, { Text(stringResource(R.string.backup_type_copy)) })
                }
                Text(stringResource(if (incremental) R.string.backup_type_restic_help else R.string.backup_type_copy_help),
                    style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to R.string.backup_manual, 24 to R.string.backup_daily, 168 to R.string.backup_weekly).forEach { (h, label) ->
                        FilterChip(intervalHours == h, { intervalHours = h }, { Text(stringResource(label)) })
                    }
                }
                if (incremental) {
                    HorizontalDivider()
                    Text(stringResource(R.string.backup_password_help), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(password, { password = it; saved = false }, label = { Text(stringResource(R.string.backup_password)) },
                        singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { copy(context, password) }) { Text(stringResource(R.string.backup_copy_password)) }
                        Checkbox(saved, { saved = it }, modifier = Modifier.semantics { contentDescription = "backup_password_saved" })
                        Text(stringResource(R.string.backup_password_saved), style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = sourceUri != null && name.isNotBlank() && folder.isNotBlank() && (!incremental || (password.length >= 8 && saved)),
                onClick = {
                    error = runCatching {
                        val src = sourceUri!!
                        val dst = driveTreeUri(driveId)
                        val job = BackupJob(
                            name = name.trim(),
                            type = if (incremental) JobType.INCREMENTAL else JobType.ONE_TIME_COPY,
                            source = Endpoint(src.toString(), sourceLabel, VolumeInfo.kindOf(src), VolumeInfo.capture(context, src, withFreeSpace = false)),
                            destination = Endpoint(dst.toString(), driveTitle, VolumeInfo.KIND_OTG, VolumeInfo.capture(context, dst, withFreeSpace = false)),
                            destinationFolder = folder.trim().trim('/'),
                            schedule = Schedule(intervalHours = intervalHours, autoRun = intervalHours > 0, remindAfterHours = if (intervalHours > 0) intervalHours * 3 else 0),
                            retention = if (incremental) Retention(keepLast = 3, keepDaily = 7, keepWeekly = 4, keepMonthly = 12) else Retention(),
                        )
                        if (incremental) BackupSecrets(context).setPassword(job.id, password)
                        JobStore.get(context).upsert(job)
                        BackupExecutor.start(context, job.id, BackupExecutor.Op.Backup("first"))
                        app.fayaz.otgmaster.OtgMasterState.log("Backup \"${job.name}\" created: ${VolumeInfo.displayPath(context, src)} → $driveTitle/${job.destinationFolder}")
                    }.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
                    if (error == null) onDismiss()
                },
                modifier = Modifier.semantics { contentDescription = "backup_create" },
            ) { Text(stringResource(R.string.backup_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** "Backups" on a mounted partition: its jobs, with run, check, restore, password and delete. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupJobsDialog(driveId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { JobStore.get(context) }
    val all by store.jobs.collectAsState()
    store.revision.collectAsState().value // recompose on history changes
    val jobs = jobsForDrive(all, driveId)
    var showPassword by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<BackupJob?>(null) }
    var restoreJob by remember { mutableStateOf<BackupJob?>(null) }

    val pickRestoreTarget = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val job = restoreJob ?: return@rememberLauncherForActivityResult
        restoreJob = null
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        val snap = store.snapshots(job.id).maxByOrNull { it.time }
        if (snap == null) app.fayaz.otgmaster.OtgMasterState.log("Restore \"${job.name}\": no snapshot yet")
        else BackupExecutor.start(context, job.id, BackupExecutor.Op.Restore(snap.id, "", uri, "${job.name} ${Format.dateTime(snap.time).replace(':', '.')}", safety = false))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_jobs_title)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (jobs.isEmpty()) Text(stringResource(R.string.backup_jobs_none))
                jobs.forEach { job ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(job.name, style = MaterialTheme.typography.titleMedium)
                        Text("${VolumeInfo.summary(job.source.info)} → ${job.destinationFolder}", style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.backup_last_success, Format.ago(job.lastSuccessAt)), style = MaterialTheme.typography.bodySmall)
                        job.lastMessage?.let { Text("${job.lastStatus?.lowercase() ?: ""}: $it", style = MaterialTheme.typography.bodySmall) }
                        val snaps = store.snapshots(job.id)
                        if (snaps.isNotEmpty()) Text(stringResource(R.string.backup_snapshots, snaps.size, Format.dateTime(snaps.last().time)),
                            style = MaterialTheme.typography.bodySmall)
                        val busy = BackupExecutor.isBusy(job.id)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(enabled = !busy, onClick = { BackupExecutor.start(context, job.id, BackupExecutor.Op.Backup("manual")) }) {
                                Text(stringResource(R.string.backup_run_now)) }
                            if (busy) TextButton(onClick = { BackupExecutor.cancel(job.id) }) { Text(stringResource(R.string.cancel)) }
                            TextButton(enabled = !busy, onClick = { BackupExecutor.start(context, job.id, BackupExecutor.Op.Check(readData = true)) }) {
                                Text(stringResource(R.string.backup_check)) }
                            if (job.type == JobType.INCREMENTAL) {
                                TextButton(enabled = !busy && snaps.isNotEmpty(), onClick = { restoreJob = job; pickRestoreTarget.launch(null) }) {
                                    Text(stringResource(R.string.backup_restore_latest)) }
                                TextButton(onClick = { showPassword = BackupSecrets(context).password(job.id) ?: "" }) {
                                    Text(stringResource(R.string.backup_show_password)) }
                            }
                            TextButton(enabled = !busy, onClick = { confirmDelete = job }) { Text(stringResource(R.string.backup_delete_job)) }
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )

    showPassword?.let { pw ->
        AlertDialog(
            onDismissRequest = { showPassword = null },
            title = { Text(stringResource(R.string.backup_password)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.backup_password_desktop), style = MaterialTheme.typography.bodySmall)
                    SelectionContainer { Text(pw.ifEmpty { stringResource(R.string.backup_password_missing) }, fontFamily = FontFamily.Monospace) }
                }
            },
            confirmButton = { TextButton(onClick = { copy(context, pw); showPassword = null }) { Text(stringResource(R.string.backup_copy_password)) } },
            dismissButton = { TextButton(onClick = { showPassword = null }) { Text(stringResource(R.string.close)) } },
        )
    }
    confirmDelete?.let { job ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.backup_delete_job_title, job.name)) },
            text = { Text(stringResource(R.string.backup_delete_job_text)) },
            confirmButton = { TextButton(onClick = {
                store.delete(job.id); BackupSecrets(context).forget(job.id); confirmDelete = null
                app.fayaz.otgmaster.OtgMasterState.log("Backup \"${job.name}\" deleted (the backups on the drive were kept)")
            }) { Text(stringResource(R.string.backup_delete_job)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("OTG Master backup password", text).apply {
        // Android 13+: keep it out of the clipboard preview and history, as password managers do.
        description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    })
}

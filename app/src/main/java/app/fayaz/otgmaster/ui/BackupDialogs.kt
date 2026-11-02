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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.fayaz.otgmaster.R
import app.fayaz.otgmaster.backup.BackupDelays
import app.fayaz.otgmaster.backup.BackupExecutor
import app.fayaz.otgmaster.backup.BackupJob
import app.fayaz.otgmaster.backup.BackupPrompts
import app.fayaz.otgmaster.backup.BackupScheduler
import app.fayaz.otgmaster.backup.BackupSecrets
import app.fayaz.otgmaster.backup.BackupTargets
import app.fayaz.otgmaster.backup.Endpoint
import app.fayaz.otgmaster.backup.Format
import app.fayaz.otgmaster.backup.JobStore
import app.fayaz.otgmaster.backup.JobType
import app.fayaz.otgmaster.backup.Retention
import app.fayaz.otgmaster.backup.Schedule
import app.fayaz.otgmaster.backup.VolumeInfo
import app.fayaz.otgmaster.extension.ExtensionContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The tree URI of a mounted partition's root, as a backup endpoint names it. */
fun driveTreeUri(driveId: String): Uri = DocumentsContract.buildTreeDocumentUri(ExtensionContract.AUTHORITY, "$driveId:/")

/** Jobs that use the partition [driveId] at either end. */
fun jobsForDrive(jobs: List<BackupJob>, driveId: String): List<BackupJob> =
    jobs.filter { VolumeInfo.otgDriveId(Uri.parse(it.destination.uri)) == driveId || VolumeInfo.otgDriveId(Uri.parse(it.source.uri)) == driveId }

/** Where a backup reads from and writes to, each in a box of its own color with its device and folder. */
@Composable
fun BackupEnds(job: BackupJob) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FromBox(VolumeInfo.summary(job.source.info), VolumeInfo.folderInside(Uri.parse(job.source.uri)))
        ToBox(VolumeInfo.summary(job.destination.info), VolumeInfo.folderInside(Uri.parse(job.destination.uri), job.destinationFolder))
    }
}

/** A restore runs the other way. Only a restore into the job's own source has a known target: other targets are not recorded. */
@Composable
fun RestoreEnds(job: BackupJob, intoSource: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FromBox(VolumeInfo.summary(job.destination.info), VolumeInfo.folderInside(Uri.parse(job.destination.uri), job.destinationFolder))
        if (intoSource) ToBox(VolumeInfo.summary(job.source.info), VolumeInfo.folderInside(Uri.parse(job.source.uri)))
    }
}

@Composable
private fun FromBox(device: String, folder: String?) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    EndBox(R.string.backup_from, if (dark) Color(0xFF12375E) else Color(0xFFDCEBFB), if (dark) Color(0xFFBBDEFB) else Color(0xFF0D47A1), device, folder)
}

@Composable
private fun ToBox(device: String, folder: String?) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    EndBox(R.string.backup_to, if (dark) Color(0xFF17401F) else Color(0xFFDFF2E1), if (dark) Color(0xFFC8E6C9) else Color(0xFF1B5E20), device, folder)
}

@Composable
private fun EndBox(label: Int, background: Color, content: Color, device: String, folder: String?) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = background, contentColor = content) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(stringResource(label), Modifier.width(40.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            Column {
                Text(device, style = MaterialTheme.typography.bodySmall)
                folder?.let { Text(stringResource(R.string.backup_folder_at, it), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** Where one end of a backup can be: the phone's own storage, or a mounted partition. */
data class BackupDevice(val driveId: String?, val title: String, val readOnly: Boolean = false) {
    val isPhone: Boolean get() = driveId == null

    /** Where the folder picker opens: the device's top level. */
    fun rootUri(): Uri = if (driveId == null) PHONE_ROOT else DocumentsContract.buildDocumentUri(ExtensionContract.AUTHORITY, "$driveId:/")

    companion object {
        val PHONE_ROOT: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:")

        /** The phone's storage, then every mounted partition except other mounted backups. */
        fun available(context: Context): List<BackupDevice> =
            listOf(BackupDevice(null, context.getString(R.string.backup_device_phone))) +
                app.fayaz.otgmaster.OtgMasterState.mountedDrives
                    .filter { it.fileSystem !is app.fayaz.otgmaster.backup.restic.ResticFileSystem }
                    .map { BackupDevice(it.id, "${it.sourceDeviceDisplayName ?: it.name} · ${it.partitionLabel} (${it.filesystemName})", it.isReadOnly) }
    }
}

/** One end of a backup as the form holds it: a device, and the folder chosen on it. */
private data class End(val device: BackupDevice, val folder: Uri? = null)

/**
 * "Back up" on a mounted partition: two ends, **source** and **target**, each a device (the
 * phone's storage or any mounted partition) and a folder on it chosen in the Files app;
 * a swap exchanges them, so the same form backs a drive up to the phone. The partition the
 * action was started from is the target by default.
 *
 * The backup goes into a folder named after it inside the target folder. Plain makes a
 * dated full copy each time; Incremental keeps snapshots, with Learn more for what that
 * means (it is a restic repository; the option itself does not say so).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupSetupDialog(driveId: String, driveTitle: String, title: Int = R.string.backup_setup_title_new, allowManual: Boolean = true, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val devices = remember { BackupDevice.available(context) }
    val here = devices.firstOrNull { it.driveId == driveId } ?: BackupDevice(driveId, driveTitle)
    var source by remember { mutableStateOf(End(devices.first())) }
    var target by remember { mutableStateOf(End(here)) }
    var name by remember { mutableStateOf("") }
    var incremental by remember { mutableStateOf(true) }
    var learnMore by remember { mutableStateOf(false) }
    var intervalHours by remember { mutableStateOf(24) }
    // A generated password fills both fields: there is nothing to retype, only to save.
    var password by remember { mutableStateOf(BackupSecrets.generate()) }
    var confirm by remember { mutableStateOf(password) }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf<String?>(null) }

    val pickFolder = rememberLauncherForActivityResult(object : ActivityResultContracts.OpenDocumentTree() {
        override fun createIntent(context: Context, input: Uri?): Intent =
            super.createIntent(context, input).addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }) { uri ->
        val which = picking
        picking = null
        if (uri == null || which == null) return@rememberLauncherForActivityResult
        // Read and write, whichever end it is now: a swap turns a source into a target.
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.recoverCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { app.fayaz.otgmaster.OtgMasterState.logFailure("backup-grant", "Could not keep access to the chosen folder", it) }
        // The folder decides the device: the picker lets the user wander to another one.
        val id = VolumeInfo.otgDriveId(uri)
        val device = devices.firstOrNull { it.driveId == id } ?: if (id == null) devices.first() else BackupDevice(id, VolumeInfo.displayPath(context, uri))
        if (which == "source") {
            source = End(device, uri)
            if (name.isBlank()) name = VolumeInfo.displayPath(context, uri).substringAfterLast('/').ifEmpty { "Backup" }
        } else target = End(device, uri)
    }

    @Composable
    fun EndPicker(title: String, tag: String, end: End, onDevice: (BackupDevice) -> Unit) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Picker(stringResource(R.string.backup_device), end.device, devices, { it.title }, "backup_${tag}_device", onDevice)
        OutlinedButton(
            onClick = { picking = tag; pickFolder.launch(end.device.rootUri()) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "backup_${tag}_location" },
        ) {
            Text(end.folder?.let { VolumeInfo.displayPath(context, it) } ?: stringResource(R.string.backup_choose_location),
                maxLines = 2)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EndPicker(stringResource(R.string.backup_source), "source", source) { if (it != source.device) source = End(it) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { val s = source; source = target; target = s },
                        modifier = Modifier.semantics { contentDescription = "backup_swap" }) {
                        Text(stringResource(R.string.backup_swap))
                    }
                }
                EndPicker(stringResource(R.string.backup_target), "target", target) { if (it != target.device) target = End(it) }
                if (target.device.readOnly) Text(stringResource(R.string.backup_target_read_only), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.backup_name)) },
                    supportingText = { Text(stringResource(R.string.backup_name_help)) }, singleLine = true,
                    modifier = Modifier.semantics { contentDescription = "backup_name" })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!incremental, { incremental = false }, { Text(stringResource(R.string.backup_type_plain)) },
                        modifier = Modifier.semantics { contentDescription = "backup_type_plain" })
                    FilterChip(incremental, { incremental = true }, { Text(stringResource(R.string.backup_type_incremental)) },
                        modifier = Modifier.semantics { contentDescription = "backup_type_incremental" })
                    androidx.compose.material3.IconButton(onClick = { learnMore = true },
                        modifier = Modifier.semantics { contentDescription = "backup_learn_more" }) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Info,
                            contentDescription = stringResource(R.string.backup_learn_more))
                    }
                }
                Text(stringResource(if (incremental) R.string.backup_type_incremental_short else R.string.backup_type_plain_short),
                    style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (if (allowManual) listOf(0 to R.string.backup_manual) else emptyList<Pair<Int, Int>>())
                        .plus(listOf(24 to R.string.backup_daily, 168 to R.string.backup_weekly)).forEach { (h, label) ->
                            FilterChip(intervalHours == h, { intervalHours = h }, { Text(stringResource(label)) },
                                modifier = Modifier.semantics { contentDescription = "backup_interval_$h" })
                        }
                }
                if (incremental) {
                    HorizontalDivider()
                    Text(stringResource(R.string.backup_password_help), style = MaterialTheme.typography.bodySmall)
                    PasswordField(password, stringResource(R.string.backup_password), "backup_password") { password = it; saved = false }
                    PasswordField(confirm, stringResource(R.string.encrypt_password_confirm), "backup_password_confirm") { confirm = it; saved = false }
                    if (confirm != password) Text(stringResource(R.string.encrypt_passwords_differ), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { copy(context, password) }, modifier = Modifier.semantics { contentDescription = "backup_copy_password" }) {
                            Text(stringResource(R.string.backup_copy_password)) }
                        Checkbox(saved, { saved = it }, modifier = Modifier.semantics { contentDescription = "backup_password_saved" })
                        Text(stringResource(R.string.backup_password_saved), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (checking) CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = "backup_create_checking" })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { contentDescription = "backup_create_error" }) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !checking && source.folder != null && target.folder != null && !target.device.readOnly && name.isNotBlank() &&
                    (!incremental || (password.length >= 8 && password == confirm && saved)),
                onClick = {
                    val src = source.folder!!
                    val dst = target.folder!!
                    val folderName = name.trim().replace('/', '-')
                    error = null
                    checking = true
                    scope.launch {
                        val problem = if (src == dst) context.getString(R.string.backup_same_folder) else withContext(Dispatchers.IO) {
                            runCatching {
                                BackupTargets.refusal(context, JobStore.get(context).jobs.value, dst, folderName, incremental, password)
                            }.getOrElse {
                                app.fayaz.otgmaster.OtgMasterState.logFailure("backup-target", "Could not check the chosen target", it)
                                context.getString(R.string.backup_target_unreadable, it.message ?: it.javaClass.simpleName)
                            }
                        }
                        if (problem != null) {
                            app.fayaz.otgmaster.OtgMasterState.log("Backup \"${name.trim()}\" not created: $problem")
                            error = problem
                            checking = false
                            return@launch
                        }
                        error = runCatching {
                            val job = BackupJob(
                                name = name.trim(),
                                type = if (incremental) JobType.INCREMENTAL else JobType.ONE_TIME_COPY,
                                source = Endpoint(src.toString(), source.device.title, VolumeInfo.kindOf(src), VolumeInfo.capture(context, src, withFreeSpace = false)),
                                destination = Endpoint(dst.toString(), target.device.title, VolumeInfo.kindOf(dst), VolumeInfo.capture(context, dst, withFreeSpace = false)),
                                destinationFolder = folderName,
                                schedule = Schedule(intervalHours = intervalHours, autoRun = intervalHours > 0, remindAfterHours = 0),
                                retention = if (incremental) Retention(keepLast = 3, keepDaily = 7, keepWeekly = 4, keepMonthly = 12) else Retention(),
                            )
                            if (incremental) BackupSecrets(context).setPassword(job.id, password)
                            JobStore.get(context).upsert(job)
                            BackupExecutor.start(context, job.id, BackupExecutor.Op.Backup("first"))
                            BackupPrompts.showProgress(job.id)
                            app.fayaz.otgmaster.OtgMasterState.log("Backup \"${job.name}\" created: ${VolumeInfo.displayPath(context, src)} → " +
                                "${VolumeInfo.displayPath(context, dst)}/${job.destinationFolder} (${if (incremental) "incremental" else "plain"})")
                        }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
                        checking = false
                        if (error == null) onDismiss()
                    }
                },
                modifier = Modifier.semantics { contentDescription = "backup_create" },
            ) { Text(stringResource(R.string.backup_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (learnMore) BackupTypesInfoDialog { learnMore = false }
}

/** Learn more: what Plain and Incremental each give, and what each costs. */
@Composable
fun BackupTypesInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_learn_more_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_type_plain), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.backup_learn_plain), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.backup_type_incremental), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.backup_learn_incremental), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "backup_learn_more_close" }) {
            Text(stringResource(R.string.close)) } },
    )
}

/** "Backups" on a mounted partition: its jobs, with their state and actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupJobsDialog(driveId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { JobStore.get(context) }
    val all by store.jobs.collectAsState()
    store.revision.collectAsState().value // recompose on history changes
    val jobs = jobsForDrive(all, driveId)
    val actions = rememberJobActions()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_jobs_title)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (jobs.isEmpty()) Text(stringResource(R.string.backup_jobs_none))
                jobs.forEach { job ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(job.name, style = MaterialTheme.typography.titleMedium)
                        BackupEnds(job)
                        Text(stringResource(R.string.backup_last_success, Format.ago(job.lastSuccessAt)), style = MaterialTheme.typography.bodySmall)
                        job.lastMessage?.let { Text("${job.lastStatus?.lowercase() ?: ""}: $it", style = MaterialTheme.typography.bodySmall) }
                        val snaps = store.snapshots(job.id)
                        if (snaps.isNotEmpty()) Text(stringResource(R.string.backup_snapshots, snaps.size, Format.dateTime(snaps.last().time)),
                            style = MaterialTheme.typography.bodySmall)
                        JobActionsRow(job, actions)
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** Which of [jobs] can reach both ends right now; null until first checked. Reads the providers, so off the main thread. */
@Composable
fun rememberReachableJobs(jobs: List<BackupJob>): Set<String>? {
    val context = LocalContext.current
    val reachable by produceState<Set<String>?>(null, jobs) {
        while (true) {
            value = withContext(Dispatchers.IO) { jobs.filter { BackupScheduler.reachable(context, it) }.map { it.id }.toSet() }
            delay(2000)
        }
    }
    return reachable
}

/**
 * One line saying where a job stands: Corrupted, a run in progress (a link to its dialog),
 * waiting for another run, due, or its next time.
 */
@Composable
fun JobStatusLine(job: BackupJob, reachable: Boolean?) {
    val busy by BackupExecutor.busy.collectAsState()
    val runs by BackupExecutor.runs.collectAsState()
    val now = System.currentTimeMillis()
    val small = MaterialTheme.typography.bodySmall
    val tag = "backup_status_${job.name}"
    when {
        job.corruptedAt != null -> Column(Modifier.semantics(mergeDescendants = true) { contentDescription = tag }) {
            Text(stringResource(R.string.backup_state_corrupted), style = small, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            job.corruptedReason?.let { Text(it, style = small, color = MaterialTheme.colorScheme.error) }
        }
        job.id in busy && runs[job.id]?.waiting == true -> Text(stringResource(R.string.backup_state_waiting), style = small,
            modifier = Modifier.semantics { contentDescription = tag })
        job.id in busy -> TextButton(onClick = { BackupPrompts.showProgress(job.id) }, modifier = Modifier.semantics { contentDescription = tag }) {
            Text(stringResource(R.string.backup_state_in_progress), style = small)
        }
        job.schedule.isManual -> Unit
        job.isDue(now) -> Text(
            if (reachable == false && job.usesUsb) stringResource(R.string.backup_state_due_connect, BackupScheduler.driveNames(job))
            else stringResource(R.string.backup_state_due),
            style = small, modifier = Modifier.semantics { contentDescription = tag })
        else -> job.nextRunAt()?.let {
            Text(stringResource(R.string.backup_state_next, Format.whenAt(it, now)), style = small, modifier = Modifier.semantics { contentDescription = tag })
        }
    }
}

/** Everything a job's row can do, with the dialogs and pickers that needs. Compose once per screen. */
class JobActions(
    val restore: (BackupJob) -> Unit,
    val showPassword: (BackupJob) -> Unit,
    val remove: (BackupJob) -> Unit,
    val enterPassword: (BackupJob) -> Unit,
    val chooseFolder: (BackupJob) -> Unit,
)

@Composable
fun rememberJobActions(): JobActions {
    val context = LocalContext.current
    val store = remember { JobStore.get(context) }
    var showPassword by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<BackupJob?>(null) }
    var restoring by remember { mutableStateOf<BackupJob?>(null) }
    var entering by remember { mutableStateOf<BackupJob?>(null) }
    var relinking by remember { mutableStateOf<BackupJob?>(null) }
    var refusal by remember { mutableStateOf<String?>(null) }

    val pickRestoreTarget = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val job = restoring ?: return@rememberLauncherForActivityResult
        restoring = null
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .onFailure { app.fayaz.otgmaster.OtgMasterState.logFailure("backup-grant", "Could not keep access to the chosen folder", it) }
        val snap = store.snapshots(job.id).maxByOrNull { it.time }
        if (snap == null) app.fayaz.otgmaster.OtgMasterState.log("Restore \"${job.name}\": no snapshot yet")
        else {
            BackupExecutor.start(context, job.id, BackupExecutor.Op.Restore(snap.id, "", uri, "${job.name} ${Format.dateTime(snap.time).replace(':', '.')}", safety = false))
            BackupPrompts.showProgress(job.id)
        }
    }
    val pickFolderAgain = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val job = relinking ?: return@rememberLauncherForActivityResult
        relinking = null
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .onFailure { app.fayaz.otgmaster.OtgMasterState.logFailure("backup-grant", "Could not keep access to the chosen folder", it) }
        store.update(job.id) { j ->
            val end = { e: Endpoint -> e.copy(uri = uri.toString(), info = VolumeInfo.capture(context, uri, withFreeSpace = false)) }
            if (lostFolderIsTarget(context, j)) j.copy(destination = end(j.destination)) else j.copy(source = end(j.source))
        }
        app.fayaz.otgmaster.OtgMasterState.log("Backup \"${job.name}\": folder set to ${VolumeInfo.displayPath(context, uri)}")
    }

    val actions = remember {
        JobActions(
            restore = { restoring = it; pickRestoreTarget.launch(null) },
            showPassword = { job ->
                withDeviceUnlock(context, context.getString(R.string.backup_password), onOk = {
                    showPassword = BackupSecrets(context).password(job.id) ?: ""
                }, onRefused = { refusal = it })
            },
            remove = { removing = it },
            enterPassword = { entering = it },
            chooseFolder = { relinking = it; pickFolderAgain.launch(null) },
        )
    }

    refusal?.let { text ->
        AlertDialog(onDismissRequest = { refusal = null },
            title = { Text(stringResource(R.string.backup_password)) },
            text = { Text(text, modifier = Modifier.semantics { contentDescription = "backup_password_refused" }) },
            confirmButton = { TextButton(onClick = { refusal = null }) { Text(stringResource(R.string.close)) } })
    }
    showPassword?.let { pw ->
        AlertDialog(
            onDismissRequest = { showPassword = null },
            title = { Text(stringResource(R.string.backup_password)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.backup_password_desktop), style = MaterialTheme.typography.bodySmall)
                    SelectionContainer {
                        Text(pw.ifEmpty { stringResource(R.string.backup_password_missing) }, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.semantics { contentDescription = "backup_password_shown" })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { copy(context, pw); showPassword = null }) { Text(stringResource(R.string.backup_copy_password)) } },
            dismissButton = { TextButton(onClick = { showPassword = null }, modifier = Modifier.semantics { contentDescription = "backup_password_close" }) { Text(stringResource(R.string.close)) } },
        )
    }
    removing?.let { job ->
        val incremental = job.type == JobType.INCREMENTAL
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.backup_delete_job_title, job.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (incremental) R.string.backup_delete_job_text else R.string.backup_delete_job_text_plain))
                    if (incremental) TextButton(onClick = { actions.showPassword(job) }, modifier = Modifier.semantics { contentDescription = "backup_remove_show_password" }) {
                        Text(stringResource(R.string.cd_show_password)) }
                }
            },
            confirmButton = { TextButton(onClick = {
                removeJob(context, job)
                removing = null
            }, modifier = Modifier.semantics { contentDescription = "backup_remove_confirm" }) {
                Text(stringResource(if (job.schedule.isManual) R.string.backup_remove else R.string.backup_delete_job)) } },
            dismissButton = { TextButton(onClick = { removing = null }, modifier = Modifier.semantics { contentDescription = "backup_remove_cancel" }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    entering?.let { job -> EnterPasswordDialog(job) { entering = null } }
    return actions
}

/** Forgets a job: its notifications, its waiting start, its password, and the folder grants nothing else uses. The backups on the drive stay. */
private fun removeJob(context: Context, job: BackupJob) {
    val store = JobStore.get(context)
    BackupScheduler.forget(context, job.id)
    store.delete(job.id)
    BackupSecrets(context).forget(job.id)
    val stillUsed = store.jobs.value.flatMap { listOf(it.source.uri, it.destination.uri) }.toSet()
    listOf(job.source.uri, job.destination.uri).distinct().filterNot { it in stillUsed }.forEach { uri ->
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .recoverCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    app.fayaz.otgmaster.OtgMasterState.log("Backup \"${job.name}\" removed (the backups on the drive were kept)")
}

/** A password for a backup this phone has none for, as after moving to a new phone. It has to open the repository before it is saved. */
@Composable
private fun EnterPasswordDialog(job: BackupJob, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_enter_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_enter_password_text, job.name), style = MaterialTheme.typography.bodySmall)
                PasswordField(password, stringResource(R.string.backup_password), "backup_enter_password_field", newPassword = false) { password = it; error = null }
                if (checking) CircularProgressIndicator(Modifier.size(24.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { contentDescription = "backup_enter_password_error" }) }
            }
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty() && !checking, onClick = {
                checking = true
                scope.launch {
                    val problem = withContext(Dispatchers.IO) {
                        val dst = Uri.parse(job.destination.uri)
                        if (!VolumeInfo.isAvailable(context, dst)) return@withContext context.getString(R.string.backups_mount_connect, VolumeInfo.summary(job.destination.info))
                        runCatching {
                            val tree = app.fayaz.otgmaster.backup.SubTree(app.fayaz.otgmaster.backup.SafFileTree(context.contentResolver, dst), job.destinationFolder)
                            if (app.fayaz.otgmaster.backup.restic.ResticRepository.open(app.fayaz.otgmaster.backup.restic.FileTreeBackend(tree), password.toByteArray()) == null)
                                context.getString(R.string.backups_browse_wrong) else null
                        }.getOrElse {
                            app.fayaz.otgmaster.OtgMasterState.logFailure("backup-password", "Could not check the password of \"${job.name}\"", it)
                            context.getString(R.string.backups_mount_failed, job.name, it.message ?: it.javaClass.simpleName)
                        }
                    }
                    checking = false
                    if (problem != null) {
                        app.fayaz.otgmaster.OtgMasterState.log("Backup \"${job.name}\": password not saved: $problem")
                        error = problem
                    } else {
                        BackupSecrets(context).setPassword(job.id, password)
                        app.fayaz.otgmaster.OtgMasterState.log("Backup \"${job.name}\": password saved on this phone")
                        onDismiss()
                    }
                }
            }, modifier = Modifier.semantics { contentDescription = "backup_enter_password_save" }) { Text(stringResource(R.string.backup_enter_password_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun grantLost(context: Context, end: Endpoint): Boolean = end.kind != VolumeInfo.KIND_OTG &&
    context.contentResolver.persistedUriPermissions.none { it.uri.toString() == end.uri }

private fun lostFolderIsTarget(context: Context, job: BackupJob) = grantLost(context, job.destination)

/** Back up now, Cancel, Verify, Restore, its password, Remove, and whatever it needs to be put right. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JobActionsRow(job: BackupJob, actions: JobActions, runLabel: Int = R.string.backup_run_now) {
    val context = LocalContext.current
    val store = remember { JobStore.get(context) }
    val busySet by BackupExecutor.busy.collectAsState()
    val busy = job.id in busySet
    val incremental = job.type == JobType.INCREMENTAL
    fun tag(s: String) = "backup_${s}_${job.name}"
    fun run(op: BackupExecutor.Op) {
        BackupExecutor.start(context, job.id, op)
        BackupPrompts.showProgress(job.id)
    }
    val missingPassword = incremental && BackupSecrets(context).password(job.id) == null
    val folderLost = grantLost(context, job.source) || grantLost(context, job.destination)
    if (missingPassword) Text(stringResource(R.string.backup_enter_password_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { contentDescription = tag("password_missing") })
    if (folderLost) Text(stringResource(R.string.backup_folder_lost), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { contentDescription = tag("folder_lost") })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (job.corruptedAt != null) TextButton(enabled = !busy, onClick = { BackupPrompts.askRecreate(job.id) }, modifier = Modifier.semantics { contentDescription = tag("recreate") }) {
            Text(stringResource(R.string.backup_recreate)) }
        if (missingPassword) TextButton(onClick = { actions.enterPassword(job) }, modifier = Modifier.semantics { contentDescription = tag("enter_password") }) {
            Text(stringResource(R.string.backup_enter_password)) }
        if (folderLost) TextButton(onClick = { actions.chooseFolder(job) }, modifier = Modifier.semantics { contentDescription = tag("choose_folder") }) {
            Text(stringResource(R.string.backup_choose_folder_again)) }
        TextButton(enabled = !busy && job.corruptedAt == null, onClick = { run(BackupExecutor.Op.Backup("manual")) }, modifier = Modifier.semantics { contentDescription = tag("run_now") }) {
            Text(stringResource(runLabel)) }
        if (busy) TextButton(onClick = { BackupExecutor.cancel(job.id) }, modifier = Modifier.semantics { contentDescription = tag("cancel") }) { Text(stringResource(R.string.cancel)) }
        TextButton(enabled = !busy, onClick = { run(BackupExecutor.Op.Check(readData = true)) }, modifier = Modifier.semantics { contentDescription = tag("verify") }) {
            Text(stringResource(R.string.backup_check)) }
        if (incremental) {
            TextButton(enabled = !busy && store.snapshots(job.id).isNotEmpty(), onClick = { actions.restore(job) }, modifier = Modifier.semantics { contentDescription = tag("restore") }) {
                Text(stringResource(R.string.backup_restore_latest)) }
            TextButton(onClick = { actions.showPassword(job) }, modifier = Modifier.semantics { contentDescription = tag("show_password") }) {
                Text(stringResource(R.string.backup_show_password)) }
        }
        TextButton(enabled = !busy, onClick = { actions.remove(job) }, modifier = Modifier.semantics { contentDescription = tag("remove") }) {
            Text(stringResource(if (job.schedule.isManual) R.string.backup_remove else R.string.backup_delete_job)) }
    }
}

/**
 * Fingerprint or the device's PIN first, for anything that shows a backup password. A phone
 * with no screen lock has nothing to check, so it refuses. Cancelling shows nothing.
 */
fun withDeviceUnlock(context: Context, title: String, onOk: () -> Unit, onRefused: (String) -> Unit) {
    val auth = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
    var c: Context = context
    while (c is android.content.ContextWrapper && c !is androidx.fragment.app.FragmentActivity) c = c.baseContext
    val activity = c as? androidx.fragment.app.FragmentActivity
    val canCheck = androidx.biometric.BiometricManager.from(context).canAuthenticate(auth) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
    if (activity == null || !canCheck) {
        app.fayaz.otgmaster.OtgMasterState.log("Backup password not shown: this phone has no screen lock to check")
        onRefused(context.getString(R.string.backup_password_needs_lock))
        return
    }
    val prompt = androidx.biometric.BiometricPrompt(activity, androidx.core.content.ContextCompat.getMainExecutor(context),
        object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) = onOk()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                app.fayaz.otgmaster.OtgMasterState.log("Backup password not shown: $errString")
            }
        })
    prompt.authenticate(androidx.biometric.BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(auth).build())
}

/** The dialogs a run or a mount can raise by itself. On the main screen, and over Manage backups while that is open. */
@Composable
fun BackupPromptHost(onHideProgress: () -> Unit = {}) {
    val due by BackupPrompts.due.collectAsState()
    val progress by BackupPrompts.progress.collectAsState()
    val corrupted by BackupPrompts.corrupted.collectAsState()
    due?.let { BackupDueDialog(it) }
    progress?.let { BackupProgressDialog(it, onHideProgress) }
    if (progress == null) corrupted?.let { BackupCorruptedDialog(it) }
}

/** A run's progress: Hide leaves it running, with its notification; Cancel stops it. The result stays until closed. */
@Composable
fun BackupProgressDialog(jobId: String, onHide: () -> Unit) {
    val context = LocalContext.current
    val store = remember { JobStore.get(context) }
    val runs by BackupExecutor.runs.collectAsState()
    val jobs by store.jobs.collectAsState()
    val run = runs[jobId]
    if (run == null) {
        LaunchedEffect(jobId) { BackupPrompts.showProgress(null) }
        return
    }
    val job = jobs.firstOrNull { it.id == jobId }
    val corrupted = run.finished && job?.corruptedAt != null
    fun close() {
        BackupExecutor.clearResult(jobId)
        BackupPrompts.showProgress(null)
    }
    fun hide() {
        BackupPrompts.showProgress(null)
        onHide()
    }
    AlertDialog(
        onDismissRequest = { if (run.finished) close() else hide() },
        title = { Text(run.title, modifier = Modifier.semantics { contentDescription = "backup_progress_title" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    corrupted -> {
                        Text(stringResource(R.string.backup_state_corrupted), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { contentDescription = "backup_progress_corrupted" })
                        run.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text(stringResource(R.string.backup_corrupted_text), style = MaterialTheme.typography.bodySmall)
                    }
                    run.finished -> Text(run.message.orEmpty(), color = if (run.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { contentDescription = "backup_progress_result" })
                    run.waiting -> Text(stringResource(R.string.backup_state_waiting), modifier = Modifier.semantics { contentDescription = "backup_progress_waiting" })
                    else -> {
                        Text(run.phase, modifier = Modifier.semantics { contentDescription = "backup_progress_phase" })
                        val fraction = run.fraction
                        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            when {
                corrupted -> TextButton(onClick = { close(); BackupPrompts.askRecreate(jobId) }, modifier = Modifier.semantics { contentDescription = "backup_progress_recreate" }) {
                    Text(stringResource(R.string.backup_recreate)) }
                run.finished -> TextButton(onClick = ::close, modifier = Modifier.semantics { contentDescription = "backup_progress_close" }) { Text(stringResource(R.string.close)) }
                else -> TextButton(onClick = ::hide, modifier = Modifier.semantics { contentDescription = "backup_progress_hide" }) { Text(stringResource(R.string.backup_progress_hide)) }
            }
        },
        dismissButton = {
            when {
                corrupted -> TextButton(onClick = ::close, modifier = Modifier.semantics { contentDescription = "backup_progress_close" }) { Text(stringResource(R.string.close)) }
                !run.finished -> TextButton(onClick = { BackupExecutor.cancel(jobId) }, modifier = Modifier.semantics { contentDescription = "backup_progress_cancel" }) {
                    Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** Mounting a drive found backups due on it: start now, after some minutes, or not now. Nothing starts unasked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupDueDialog(due: BackupPrompts.Due) {
    val context = LocalContext.current
    val store = remember { JobStore.get(context) }
    val jobs = due.jobIds.mapNotNull(store::job)
    val reachable = rememberReachableJobs(jobs)
    var choosingLater by remember(due) { mutableStateOf(due.laterFirst) }
    val ready = jobs.filter { reachable?.contains(it.id) == true }
    AlertDialog(
        onDismissRequest = { BackupPrompts.answered() },
        title = { Text(stringResource(R.string.backup_due_title, due.driveName), modifier = Modifier.semantics { contentDescription = "backup_due_title" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                jobs.forEach { job ->
                    Text(job.name, style = MaterialTheme.typography.titleSmall)
                    if (reachable != null && job.id !in reachable) Text(
                        stringResource(R.string.backup_due_missing_end, BackupScheduler.missingEnds(context, job)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { contentDescription = "backup_due_missing_${job.name}" })
                }
                if (choosingLater) {
                    Text(stringResource(R.string.backup_later_heading))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BackupDelays.CHOICES_MINUTES.forEach { m ->
                            OutlinedButton(enabled = ready.isNotEmpty(), onClick = {
                                ready.forEach { BackupDelays.schedule(context, it.id, m) }
                                BackupPrompts.answered()
                            }, modifier = Modifier.semantics { contentDescription = "backup_later_$m" }) {
                                Text(stringResource(R.string.backup_later_minutes, m)) }
                        }
                    }
                } else Text(stringResource(R.string.backup_due_question))
            }
        },
        confirmButton = {
            if (!choosingLater) TextButton(enabled = ready.isNotEmpty(), onClick = {
                ready.forEach { BackupExecutor.start(context, it.id, BackupExecutor.Op.Backup("manual")) }
                BackupPrompts.answered()
                ready.firstOrNull()?.let { BackupPrompts.showProgress(it.id) }
            }, modifier = Modifier.semantics { contentDescription = "backup_due_start" }) { Text(stringResource(R.string.backup_due_start)) }
        },
        dismissButton = {
            Row {
                if (!choosingLater) TextButton(enabled = ready.isNotEmpty(), onClick = { choosingLater = true }, modifier = Modifier.semantics { contentDescription = "backup_due_later" }) {
                    Text(stringResource(R.string.backup_due_later)) }
                TextButton(onClick = {
                    jobs.forEach { BackupScheduler.snooze(context, it.id) }
                    BackupPrompts.answered()
                }, modifier = Modifier.semantics { contentDescription = "backup_due_not_now" }) { Text(stringResource(R.string.backup_due_not_now)) }
            }
        },
    )
}

/** A backup found damaged: make it again from the source, if the source is here. */
@Composable
fun BackupCorruptedDialog(jobId: String) {
    val context = LocalContext.current
    val store = remember { JobStore.get(context) }
    val jobs by store.jobs.collectAsState()
    val job = jobs.firstOrNull { it.id == jobId }
    if (job == null || job.corruptedAt == null) {
        LaunchedEffect(jobId) { BackupPrompts.askRecreate(null) }
        return
    }
    val reachable = rememberReachableJobs(listOf(job))
    AlertDialog(
        onDismissRequest = { BackupPrompts.askRecreate(null) },
        title = { Text(stringResource(R.string.backup_corrupted_title, job.name), modifier = Modifier.semantics { contentDescription = "backup_corrupted_title" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                job.corruptedReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.backup_corrupted_text))
                if (reachable != null && job.id !in reachable) Text(
                    stringResource(R.string.backup_corrupted_connect, BackupScheduler.missingEnds(context, job)),
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { contentDescription = "backup_corrupted_connect" })
            }
        },
        confirmButton = {
            TextButton(enabled = reachable?.contains(job.id) == true, onClick = {
                BackupPrompts.askRecreate(null)
                BackupExecutor.start(context, job.id, BackupExecutor.Op.Recreate)
                BackupPrompts.showProgress(job.id)
            }, modifier = Modifier.semantics { contentDescription = "backup_recreate_confirm" }) { Text(stringResource(R.string.backup_recreate)) }
        },
        dismissButton = {
            TextButton(onClick = { BackupPrompts.askRecreate(null) }, modifier = Modifier.semantics { contentDescription = "backup_recreate_not_now" }) {
                Text(stringResource(R.string.backup_due_not_now)) }
        },
    )
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("OTG Master backup password", text).apply {
        // Android 13+: keep it out of the clipboard preview and history, as password managers do.
        description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    })
}

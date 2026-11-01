package app.fayaz.otgmaster.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.fayaz.otgmaster.OtgMasterState
import app.fayaz.otgmaster.R
import app.fayaz.otgmaster.applySystemBarAppearance
import app.fayaz.otgmaster.backup.BackupExecutor
import app.fayaz.otgmaster.backup.BackupJob
import app.fayaz.otgmaster.backup.BackupMounts
import app.fayaz.otgmaster.backup.BackupSecrets
import app.fayaz.otgmaster.backup.Format
import app.fayaz.otgmaster.backup.JobStore
import app.fayaz.otgmaster.backup.JobType
import app.fayaz.otgmaster.backup.RunRecord
import app.fayaz.otgmaster.backup.RunStatus
import app.fayaz.otgmaster.backup.VolumeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manage backups, opened from the main screen: every backup job in one place, whichever
 * drives it uses, in two tabs.
 *
 * - **Schedules**: Create schedule, while a drive is mounted; each job's source and target, type and schedule (changeable here), when
 *   it last ran and how; Back up now, Check, Restore newest, Mount (an incremental backup
 *   as a read-only drive, see [BackupMounts]), its password, Delete.
 * - **History**: every run of every job, newest first — what ran, when, for how long, how
 *   many files and bytes, the snapshot it made, and the message of a failure.
 * - **Mount backups**: Browse for a backup in any folder, and the incremental jobs of this phone.
 */
class BackupsActivity : ComponentActivity() {

    companion object {
        /** Which tab to open on: [TAB_SCHEDULES], [TAB_HISTORY] or [TAB_MOUNT]. */
        const val EXTRA_TAB = "tab"
        const val TAB_SCHEDULES = 0
        const val TAB_HISTORY = 1
        const val TAB_MOUNT = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // The same theme preference MainActivity reads (as FeedbackActivity does).
            val dark = when (getSharedPreferences("otgmaster_prefs", MODE_PRIVATE).getString("theme_mode", "SYSTEM")) {
                "LIGHT" -> false
                "DARK" -> true
                else -> isSystemInDarkTheme()
            }
            LaunchedEffect(dark) { applySystemBarAppearance(dark) }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.safeDrawingPadding()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                            }
                            Text(stringResource(R.string.backups_manage_title), style = MaterialTheme.typography.titleLarge)
                        }
                        var tab by remember { mutableStateOf(intent.getIntExtra(EXTRA_TAB, TAB_SCHEDULES)) }
                        TabRow(selectedTabIndex = tab) {
                            Tab(tab == TAB_SCHEDULES, { tab = TAB_SCHEDULES }, text = { Text(stringResource(R.string.backups_tab_schedules)) },
                                modifier = Modifier.semantics { contentDescription = "backups_tab_schedules" })
                            Tab(tab == TAB_HISTORY, { tab = TAB_HISTORY }, text = { Text(stringResource(R.string.backups_tab_history)) },
                                modifier = Modifier.semantics { contentDescription = "backups_tab_history" })
                            Tab(tab == TAB_MOUNT, { tab = TAB_MOUNT }, text = { Text(stringResource(R.string.backups_tab_mount)) },
                                modifier = Modifier.semantics { contentDescription = "backups_tab_mount" })
                        }
                        when (tab) {
                            TAB_HISTORY -> History()
                            TAB_MOUNT -> MountBackups()
                            else -> Schedules()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Schedules() {
    val context = LocalContext.current
    val store = remember { JobStore.get(context) }
    val jobs by store.jobs.collectAsState()
    store.revision.collectAsState().value // recompose on history changes
    var showPassword by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<BackupJob?>(null) }
    var restoreJob by remember { mutableStateOf<BackupJob?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val pickRestoreTarget = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val job = restoreJob ?: return@rememberLauncherForActivityResult
        restoreJob = null
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        val snap = store.snapshots(job.id).maxByOrNull { it.time }
        if (snap == null) message = context.getString(R.string.backups_no_snapshot, job.name)
        else BackupExecutor.start(context, job.id, BackupExecutor.Op.Restore(snap.id, "", uri, "${job.name} ${Format.dateTime(snap.time).replace(':', '.')}", safety = false))
    }

    // The mounted partitions a schedule can use. A drive can come or go while this tab is up
    // and nothing here is told, so look again each second.
    var drives by remember { mutableStateOf(BackupDevice.available(context).filterNot { it.isPhone }) }
    var creating by remember { mutableStateOf<BackupDevice?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            drives = BackupDevice.available(context).filterNot { it.isPhone }
        }
    }
    creating?.let { d -> BackupSetupDialog(d.driveId!!, d.title, R.string.backups_create_schedule) { creating = null } }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    enabled = drives.isNotEmpty(),
                    onClick = { creating = drives.firstOrNull { !it.readOnly } ?: drives.first() },
                    modifier = Modifier.semantics { contentDescription = "backups_create_schedule" },
                ) { Text(stringResource(R.string.backups_create_schedule)) }
                if (drives.isEmpty()) Text(stringResource(R.string.backups_create_no_devices), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (jobs.isEmpty()) item { Text(stringResource(R.string.backups_none), Modifier.padding(vertical = 8.dp)) }
        message?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) } }
        items(jobs, key = { it.id }) { job ->
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(job.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(if (job.type == JobType.INCREMENTAL) R.string.backup_type_incremental else R.string.backup_type_plain),
                        style = MaterialTheme.typography.labelMedium)
                    Text("${VolumeInfo.summary(job.source.info)}\n→ ${VolumeInfo.summary(job.destination.info)} / ${job.destinationFolder}",
                        style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to R.string.backup_manual, 24 to R.string.backup_daily, 168 to R.string.backup_weekly).forEach { (h, label) ->
                            FilterChip(job.schedule.intervalHours == h, {
                                store.update(job.id) { it.copy(schedule = it.schedule.copy(intervalHours = h, autoRun = h > 0,
                                    remindAfterHours = if (h > 0) h * 3 else 0)) }
                            }, { Text(stringResource(label)) })
                        }
                    }
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
            }
        }
    }

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
            confirmButton = { TextButton(onClick = { showPassword = null }) { Text(stringResource(R.string.close)) } },
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

/**
 * Mount backups: each incremental backup, mountable as a read-only drive (one folder per
 * snapshot) for the Files app; a mounted one is unmounted from its card on the main screen.
 */
@Composable
private fun MountBackups() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val jobs by JobStore.get(context).jobs.collectAsState()
    val incremental = jobs.filter { it.type == JobType.INCREMENTAL }
    var message by remember { mutableStateOf<String?>(null) }
    var changed by remember { mutableStateOf(0) }
    // A browsed folder that holds a backup whose password this phone does not have.
    var asking by remember { mutableStateOf<Uri?>(null) }
    var wrongPassword by remember { mutableStateOf(false) }

    /** Mounts the browsed folder; false if the password does not open it. */
    suspend fun mountBrowsed(folder: Uri, password: String): Boolean = withContext(Dispatchers.IO) {
        val name = VolumeInfo.displayPath(context, folder)
        runCatching { BackupMounts.mountFolder(context, folder, password) }.fold(
            { drive ->
                if (drive != null) message = context.getString(R.string.backups_mounted, drive.name)
                drive != null
            },
            {
                OtgMasterState.logFailure("backup-browse", "Could not mount the backup at $name", it)
                message = context.getString(R.string.backups_mount_failed, name, it.message ?: it.javaClass.simpleName)
                true
            })
    }

    val browse = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure { OtgMasterState.logFailure("backup-grant", "Could not keep access to the chosen folder", it) }
        message = context.getString(R.string.backups_browse_opening)
        scope.launch {
            val found = withContext(Dispatchers.IO) { BackupMounts.holdsBackup(context, uri) }
            if (!found) {
                OtgMasterState.log("No backup found at ${VolumeInfo.displayPath(context, uri)}")
                message = context.getString(R.string.backups_browse_none)
                return@launch
            }
            val drive = withContext(Dispatchers.IO) { BackupMounts.mountAsJob(context, uri) }
            if (drive != null) {
                message = context.getString(R.string.backups_mounted, drive.name)
            } else {
                message = null
                wrongPassword = false
                asking = uri
            }
            changed++
        }
    }

    asking?.let { folder ->
        var password by remember(folder) { mutableStateOf("") }
        var opening by remember(folder) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(R.string.backup_password)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.backups_browse_password, VolumeInfo.displayPath(context, folder)), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(password, { password = it; wrongPassword = false }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text(stringResource(R.string.backup_password)) },
                        modifier = Modifier.semantics { contentDescription = "backups_browse_password" })
                    if (wrongPassword) Text(stringResource(R.string.backups_browse_wrong), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = password.isNotEmpty() && !opening, onClick = {
                    opening = true
                    scope.launch {
                        if (mountBrowsed(folder, password)) asking = null else wrongPassword = true
                        opening = false
                        changed++
                    }
                }, modifier = Modifier.semantics { contentDescription = "backups_browse_mount" }) { Text(stringResource(R.string.backups_mount)) }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Button(onClick = { browse.launch(null) }, modifier = Modifier.padding(top = 8.dp).semantics { contentDescription = "backups_browse" }) {
                Text(stringResource(R.string.backups_browse))
            }
        }
        item { Text(stringResource(R.string.backups_mount_help), style = MaterialTheme.typography.bodySmall) }
        message?.let { m -> item { Text(m, style = MaterialTheme.typography.bodyMedium) } }
        if (incremental.isEmpty()) item { Text(stringResource(R.string.backups_mount_none), style = MaterialTheme.typography.bodySmall) }
        items(incremental, key = { it.id }) { job ->
            changed.let { } // read so the state follows a mount
            val mounted = BackupMounts.isMounted(job.id)
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(job.name, style = MaterialTheme.typography.titleMedium)
                        Text("${VolumeInfo.summary(job.destination.info)} / ${job.destinationFolder}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(enabled = !mounted, modifier = Modifier.semantics { contentDescription = "backup_mount_${job.name}" }, onClick = {
                        scope.launch {
                            message = withContext(Dispatchers.IO) {
                                runCatching { BackupMounts.mount(context, job) }.fold(
                                    { context.getString(R.string.backups_mounted, job.name) },
                                    { context.getString(R.string.backups_mount_failed, job.name, it.message ?: it.javaClass.simpleName) })
                            }
                            changed++
                        }
                    }) { Text(stringResource(if (mounted) R.string.backups_is_mounted else R.string.backups_mount)) }
                }
            }
        }
    }
}

@Composable
private fun History() {
    val context = LocalContext.current
    val store = remember { JobStore.get(context) }
    val jobs by store.jobs.collectAsState()
    val revision by store.revision.collectAsState()
    val runs = remember(jobs, revision) {
        jobs.flatMap { j -> store.history(j.id).map { j.name to it } }.sortedByDescending { it.second.startedAt }
    }
    if (runs.isEmpty()) {
        Text(stringResource(R.string.backups_history_none), Modifier.padding(16.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(runs, key = { it.second.id }) { (name, run) -> RunRow(name, run) }
    }
}

@Composable
private fun RunRow(jobName: String, run: RunRecord) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val color = when (run.status) {
                RunStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                RunStatus.WARNING, RunStatus.NEEDS_CONFIRMATION, RunStatus.CANCELLED -> MaterialTheme.colorScheme.tertiary
                RunStatus.FAILED, RunStatus.UNAVAILABLE -> MaterialTheme.colorScheme.error
            }
            Text("$jobName · ${run.kind}", style = MaterialTheme.typography.titleSmall)
            Text("${run.status.name.lowercase().replace('_', ' ')} · ${Format.dateTime(run.startedAt)} · " +
                "${((run.finishedAt - run.startedAt) / 1000).coerceAtLeast(0)} s", color = color, style = MaterialTheme.typography.bodySmall)
            run.stats?.let { s ->
                Text(stringResource(R.string.backups_history_stats, s.files, s.filesNew, Format.bytes(s.totalBytes), Format.bytes(s.bytesAdded)),
                    style = MaterialTheme.typography.bodySmall)
            }
            run.snapshotId?.let { Text(stringResource(R.string.backups_history_snapshot, it.take(8)), style = MaterialTheme.typography.bodySmall) }
            run.copyFolder?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (run.message.isNotBlank()) Text(run.message, style = MaterialTheme.typography.bodySmall)
            run.errors.take(5).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

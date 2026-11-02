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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import app.fayaz.otgmaster.backup.BackupPrompts
import app.fayaz.otgmaster.backup.BackupRunner
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
 *   it last ran and how, where it stands now; Back up now, Verify backup, Restore newest,
 *   its password, Remove schedule. A manual backup has no schedule, so it is under History.
 * - **History**: every run of every job, newest first — what ran, when, for how long, how
 *   many files and bytes, the snapshot it made, and the message of a failure.
 * - **Mount backups**: Browse for a backup in any folder, and the incremental jobs of this phone.
 */
class BackupsActivity : androidx.fragment.app.FragmentActivity() {

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
                    // Hide goes back to the main screen, with the run carrying on behind it.
                    BackupPromptHost(onHideProgress = { finish() })
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
    val allJobs by store.jobs.collectAsState()
    store.revision.collectAsState().value // recompose on history changes
    // A manual backup has no schedule: it lives in History.
    val jobs = allJobs.filterNot { it.schedule.isManual }
    val actions = rememberJobActions()
    val reachable = rememberReachableJobs(jobs)

    // The mounted partitions a schedule can use. A drive can come or go while this tab is up
    // and nothing here is told, so look again each second.
    var drives by remember { mutableStateOf(BackupDevice.available(context).filterNot { it.isPhone }) }
    var creating by remember { mutableStateOf<BackupDevice?>(null) }
    // "Last backup: 19 min ago" is worked out when a card composes, so it has to compose again.
    var clock by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            drives = BackupDevice.available(context).filterNot { it.isPhone }
            clock = System.currentTimeMillis() / 60_000
        }
    }
    creating?.let { d -> BackupSetupDialog(d.driveId!!, d.title, R.string.backups_create_schedule, allowManual = false) { creating = null } }

    // Two columns where the main screen shows two panes, so an unfolded phone or a tablet
    // shows more than one and a half schedules at a time.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            GridCells.Fixed(if (isTwoPaneWindow(maxWidth, maxHeight)) 2 else 1),
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        enabled = drives.isNotEmpty(),
                        onClick = { creating = drives.firstOrNull { !it.readOnly } ?: drives.first() },
                        modifier = Modifier.semantics { contentDescription = "backups_create_schedule" },
                    ) { Text(stringResource(R.string.backups_create_schedule)) }
                    if (drives.isEmpty()) Text(stringResource(R.string.backups_create_no_devices), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (jobs.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.backups_none), Modifier.padding(vertical = 8.dp)) }
            items(jobs, key = { it.id }) { job ->
                clock.let { } // read so the times below follow the clock
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(job.name, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(if (job.type == JobType.INCREMENTAL) R.string.backup_type_incremental else R.string.backup_type_plain),
                            style = MaterialTheme.typography.labelMedium)
                        BackupEnds(job)
                        JobStatusLine(job, reachable?.contains(job.id))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(24 to R.string.backup_daily, 168 to R.string.backup_weekly).forEach { (h, label) ->
                                FilterChip(job.schedule.intervalHours == h, {
                                    store.update(job.id) { it.copy(schedule = it.schedule.copy(intervalHours = h, autoRun = true)) }
                                }, { Text(stringResource(label)) }, modifier = Modifier.semantics { contentDescription = "backup_interval_${h}_${job.name}" })
                            }
                        }
                        Text(stringResource(R.string.backup_last_success, Format.ago(job.lastSuccessAt)), style = MaterialTheme.typography.bodySmall)
                        job.lastMessage?.let { Text("${job.lastStatus?.lowercase() ?: ""}: $it", style = MaterialTheme.typography.bodySmall) }
                        val snaps = store.snapshots(job.id)
                        if (snaps.isNotEmpty()) Text(stringResource(R.string.backup_snapshots, snaps.size, Format.dateTime(snaps.last().time)),
                            style = MaterialTheme.typography.bodySmall)
                        JobActionsRow(job, actions)
                    }
                }
            }
        }
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
    // Backups whose drive or folder can be read right now; null until first checked.
    var reachable by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(incremental) {
        while (true) {
            reachable = withContext(Dispatchers.IO) {
                incremental.filter { VolumeInfo.isAvailable(context, Uri.parse(it.destination.uri)) }.map { it.id }.toSet()
            }
            delay(2000)
        }
    }

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

    // The job whose Mount is working, and whether a browsed folder is being opened: opening
    // a repository reads its index, which takes seconds on a slow drive with nothing else to see.
    var mounting by remember { mutableStateOf<String?>(null) }
    var openingFolder by remember { mutableStateOf(false) }

    val browse = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure { OtgMasterState.logFailure("backup-grant", "Could not keep access to the chosen folder", it) }
        message = context.getString(R.string.backups_browse_opening)
        openingFolder = true
        scope.launch {
            val found = withContext(Dispatchers.IO) { BackupMounts.holdsBackup(context, uri) }
            if (!found) {
                OtgMasterState.log("No backup found at ${VolumeInfo.displayPath(context, uri)}")
                message = context.getString(R.string.backups_browse_none)
                openingFolder = false
                return@launch
            }
            val drive = withContext(Dispatchers.IO) { BackupMounts.mountAsJob(context, uri) }
            openingFolder = false
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
                    PasswordField(password, stringResource(R.string.backup_password), "backups_browse_password", newPassword = false) { password = it; wrongPassword = false }
                    if (opening) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "backups_browse_opening" })
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
        if (openingFolder) item { LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "backups_browse_opening" }) }
        message?.let { m -> item { Text(m, style = MaterialTheme.typography.bodyMedium) } }
        if (incremental.isEmpty()) item { Text(stringResource(R.string.backups_mount_none), style = MaterialTheme.typography.bodySmall) }
        items(incremental, key = { it.id }) { job ->
            changed.let { } // read so the state follows a mount
            val mounted = BackupMounts.isMounted(job.id)
            val canReach = reachable?.contains(job.id)
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(job.name, style = MaterialTheme.typography.titleMedium)
                        BackupEnds(job)
                        if (!mounted && canReach == false) {
                            Text(
                                if (job.destination.kind == VolumeInfo.KIND_OTG) stringResource(R.string.backups_mount_connect, VolumeInfo.summary(job.destination.info))
                                else stringResource(R.string.backups_mount_unreachable),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (mounting == job.id) CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = "backup_mount_progress_${job.name}" })
                    TextButton(enabled = !mounted && canReach == true && mounting == null, modifier = Modifier.semantics { contentDescription = "backup_mount_${job.name}" }, onClick = {
                        mounting = job.id
                        scope.launch {
                            message = withContext(Dispatchers.IO) {
                                runCatching { BackupMounts.mount(context, job) }.fold(
                                    { context.getString(R.string.backups_mounted, job.name) },
                                    {
                                        OtgMasterState.logFailure("backup-mount", "Could not mount the backup \"${job.name}\"", it)
                                        if (BackupRunner.isDamage(it)) {
                                            BackupRunner(context).markCorrupted(job, it.message ?: "the backup is damaged")
                                            JobStore.get(context).job(job.id)?.let { j -> BackupPrompts.corruptionFound(context, j) }
                                        }
                                        context.getString(R.string.backups_mount_failed, job.name, it.message ?: it.javaClass.simpleName)
                                    })
                            }
                            mounting = null
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
        jobs.flatMap { j -> store.history(j.id).map { j to it } }.sortedByDescending { it.second.startedAt }
    }
    val actions = rememberJobActions()
    if (runs.isEmpty()) {
        Text(stringResource(R.string.backups_history_none), Modifier.padding(16.dp))
        return
    }
    // A manual backup has no row under Schedules, so its newest entry here carries its actions.
    val newestOfManual = runs.filter { it.first.schedule.isManual }.distinctBy { it.first.id }.map { it.second.id }.toSet()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(runs, key = { it.second.id }) { (job, run) -> RunRow(job, run, if (run.id in newestOfManual) actions else null) }
    }
}

@Composable
private fun RunRow(job: BackupJob, run: RunRecord, actions: JobActions? = null) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val color = when (run.status) {
                RunStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                RunStatus.WARNING, RunStatus.NEEDS_CONFIRMATION, RunStatus.CANCELLED -> MaterialTheme.colorScheme.tertiary
                RunStatus.FAILED, RunStatus.UNAVAILABLE, RunStatus.CORRUPTED -> MaterialTheme.colorScheme.error
            }
            Text("${job.name} · ${run.kind}", style = MaterialTheme.typography.titleSmall)
            if (run.kind == "restore") RestoreEnds(job, intoSource = run.reason == "switch") else BackupEnds(job)
            Text("${run.status.name.lowercase().replace('_', ' ')} · ${Format.dateTime(run.startedAt)} · " +
                "${((run.finishedAt - run.startedAt) / 1000).coerceAtLeast(0)} s", color = color, style = MaterialTheme.typography.bodySmall)
            run.stats?.let { s ->
                Text(stringResource(R.string.backups_history_stats, Format.files(s.files), s.filesNew, Format.bytes(s.totalBytes), Format.bytes(s.bytesAdded)),
                    style = MaterialTheme.typography.bodySmall)
            }
            run.snapshotId?.let { Text(stringResource(R.string.backups_history_snapshot, it.take(8)), style = MaterialTheme.typography.bodySmall) }
            run.copyFolder?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (run.message.isNotBlank()) Text(run.message, style = MaterialTheme.typography.bodySmall)
            run.errors.take(5).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            actions?.let { a ->
                JobStatusLine(job, null)
                JobActionsRow(job, a, runLabel = R.string.backup_run_again)
            }
        }
    }
}

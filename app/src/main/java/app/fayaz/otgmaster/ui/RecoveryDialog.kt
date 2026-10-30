package app.fayaz.otgmaster.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import app.fayaz.otgmaster.MountedDrive
import app.fayaz.otgmaster.OtgMasterState
import app.fayaz.otgmaster.R
import app.fayaz.otgmaster.backup.Format
import app.fayaz.otgmaster.backup.Paths
import app.fayaz.otgmaster.backup.SafFileTree
import app.fayaz.otgmaster.backup.VolumeInfo
import app.fayaz.otgmaster.provider.VeraCryptDocumentProvider
import app.fayaz.otgmaster.recovery.DeletedFile
import app.fayaz.otgmaster.recovery.ExFatRecovery
import app.fayaz.otgmaster.recovery.FatRecovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Whether [drive]'s filesystem can be scanned for deleted files; null if it can, else why not. */
fun recoveryUnavailable(drive: MountedDrive, notSupported: String): String? =
    if (drive.blockDevice != null && (drive.filesystemName.equals("FAT32", true) || drive.filesystemName.equals("exFAT", true))) null
    else notSupported

private interface Scanner { fun scan(cancel: () -> Boolean): List<DeletedFile>; fun read(f: DeletedFile, out: OutputStream) }

private fun scannerFor(drive: MountedDrive): Scanner {
    val dev = drive.blockDevice ?: throw IllegalStateException("no block device")
    return if (drive.filesystemName.equals("exFAT", true)) ExFatRecovery(dev).let { r ->
        object : Scanner { override fun scan(cancel: () -> Boolean) = r.scan(cancel); override fun read(f: DeletedFile, out: OutputStream) = r.read(f, out) }
    } else FatRecovery(dev).let { r ->
        object : Scanner { override fun scan(cancel: () -> Boolean) = r.scan(cancel); override fun read(f: DeletedFile, out: OutputStream) = r.read(f, out) }
    }
}

/**
 * "Recover deleted files…": lists the files this partition's filesystem has deleted (only
 * those: files still on the drive are browsed in the Files app as usual) and copies the
 * chosen ones to a folder somewhere else. Nothing is written to this partition: writing
 * here could land on the very clusters being recovered.
 */
@Composable
fun RecoveryDialog(drive: MountedDrive, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<DeletedFile>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val cancel = remember { AtomicBoolean(false) }
    val scanner = remember { runCatching { scannerFor(drive) } }
    val lockId = "${drive.id}:/"
    DisposableEffect(Unit) { onDispose { cancel.set(true) } }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            runCatching { VeraCryptDocumentProvider.onDrive(lockId, "recover-scan") { scanner.getOrThrow().scan { cancel.get() } } }
        }
        result.onSuccess { list ->
            files = list
            selected = list.filter { it.state == DeletedFile.State.RECOVERABLE }.map { it.path }.toSet()
            OtgMasterState.log("Recovery scan of ${drive.name}: ${list.size} deleted file(s), " +
                "${list.count { it.state == DeletedFile.State.RECOVERABLE }} recoverable")
        }.onFailure {
            error = "${it.javaClass.simpleName}: ${it.message}"
            OtgMasterState.logFailure("recovery", "Recovery scan of ${drive.name} failed", it)
        }
    }

    val pickTarget = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (VolumeInfo.otgDriveId(uri) == drive.id) {
            status = context.getString(R.string.recovery_same_drive)
            return@rememberLauncherForActivityResult
        }
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        val chosen = files.orEmpty().filter { it.path in selected }
        busy = true
        Thread {
            val tree = SafFileTree(context.contentResolver, uri)
            val folder = "Recovered ${Format.dateTime(System.currentTimeMillis()).replace(':', '.')}"
            var ok = 0
            val failed = ArrayList<String>()
            for ((i, f) in chosen.withIndex()) {
                if (cancel.get()) break
                status = context.getString(R.string.recovery_progress, i + 1, chosen.size)
                runCatching {
                    tree.openWrite(Paths.join(folder, f.path)).use { out ->
                        VeraCryptDocumentProvider.onDrive(lockId, "recover-read") { scanner.getOrThrow().read(f, out) }
                    }
                }.onSuccess { ok++ }.onFailure { failed += f.path; OtgMasterState.logFailure("recovery", "Recovering a file failed", it) }
            }
            OtgMasterState.log("Recovered $ok of ${chosen.size} file(s) from ${drive.name} to $folder" +
                if (failed.isNotEmpty()) "; ${failed.size} failed" else "")
            status = context.getString(R.string.recovery_done, ok, chosen.size, folder)
            busy = false
        }.start()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.recovery_title, drive.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val list = files
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    list == null -> Text(stringResource(R.string.recovery_scanning))
                    list.isEmpty() -> Text(stringResource(R.string.recovery_none))
                    else -> {
                        Text(stringResource(R.string.recovery_help), style = MaterialTheme.typography.bodySmall)
                        LazyColumn(Modifier.heightIn(max = 420.dp)) {
                            items(list, key = { it.path }) { f ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(f.path in selected, { on -> selected = if (on) selected + f.path else selected - f.path },
                                        enabled = f.state != DeletedFile.State.OVERWRITTEN)
                                    Column {
                                        Text(f.path, style = MaterialTheme.typography.bodyMedium)
                                        Text(listOfNotNull(Format.bytes(f.size), f.modifiedMillis?.let { Format.dateTime(it) },
                                            when (f.state) {
                                                DeletedFile.State.RECOVERABLE -> null
                                                DeletedFile.State.PARTLY_OVERWRITTEN -> stringResource(R.string.recovery_partly)
                                                DeletedFile.State.OVERWRITTEN -> stringResource(R.string.recovery_overwritten)
                                            }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && selected.isNotEmpty(), onClick = { pickTarget.launch(null) },
                modifier = Modifier.semantics { contentDescription = "recovery_recover" }) {
                Text(stringResource(R.string.recovery_recover, selected.size))
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

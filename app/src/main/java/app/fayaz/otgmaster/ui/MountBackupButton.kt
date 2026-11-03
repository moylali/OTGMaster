package app.fayaz.otgmaster.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.fayaz.otgmaster.OtgMasterState
import app.fayaz.otgmaster.R
import app.fayaz.otgmaster.backup.BackupMounts
import app.fayaz.otgmaster.backup.VolumeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opens the Files picker for a folder holding a backup, and mounts it: with the saved password if
 * it is one of this phone's backups, otherwise after asking. A mount announces itself through
 * [BackupMounts.justMounted], and the main screen opens the slider on it. [onProblem] gets what
 * went wrong, already logged.
 */
@Composable
fun MountBackupButton(onProblem: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Opening a repository reads its index, which takes seconds on a slow drive with nothing else to see.
    var opening by remember { mutableStateOf(false) }
    // A chosen folder holding a backup whose password this phone does not have.
    var asking by remember { mutableStateOf<Uri?>(null) }
    var wrongPassword by remember { mutableStateOf(false) }

    fun failed(folder: Uri, cause: Throwable) {
        val name = VolumeInfo.displayPath(context, folder)
        OtgMasterState.logFailure("backup-browse", "Could not mount the backup at $name", cause)
        onProblem(context.getString(R.string.backups_mount_failed, name, cause.message ?: cause.javaClass.simpleName))
    }

    val browse = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure { OtgMasterState.logFailure("backup-grant", "Could not keep access to the chosen folder", it) }
        opening = true
        scope.launch {
            val found = withContext(Dispatchers.IO) { BackupMounts.holdsBackup(context, uri) }
            if (!found) {
                OtgMasterState.log("No backup found at ${VolumeInfo.displayPath(context, uri)}")
                onProblem(context.getString(R.string.backups_browse_none))
                opening = false
                return@launch
            }
            withContext(Dispatchers.IO) { runCatching { BackupMounts.mountAsJob(context, uri) } }.fold(
                { drive -> if (drive == null) { wrongPassword = false; asking = uri } },
                { failed(uri, it) },
            )
            opening = false
        }
    }

    asking?.let { folder ->
        var password by remember(folder) { mutableStateOf("") }
        var mounting by remember(folder) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(R.string.backup_password)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.backups_browse_password, VolumeInfo.displayPath(context, folder)), style = MaterialTheme.typography.bodySmall)
                    PasswordField(password, stringResource(R.string.backup_password), "backups_browse_password", newPassword = false) { password = it; wrongPassword = false }
                    if (mounting) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "backups_browse_opening" })
                    if (wrongPassword) Text(stringResource(R.string.backups_browse_wrong), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = password.isNotEmpty() && !mounting, onClick = {
                    mounting = true
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { BackupMounts.mountFolder(context, folder, password) } }.fold(
                            { drive -> if (drive != null) asking = null else wrongPassword = true },
                            { failed(folder, it); asking = null },
                        )
                        mounting = false
                    }
                }, modifier = Modifier.semantics { contentDescription = "backups_browse_mount" }) { Text(stringResource(R.string.backups_mount)) }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    OutlinedButton(
        enabled = !opening,
        onClick = { browse.launch(null) },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        modifier = modifier.semantics { contentDescription = "backups_open_mount" },
    ) {
        if (opening) CircularProgressIndicator(Modifier.size(18.dp).semantics { contentDescription = "backups_browse_opening" }, strokeWidth = 2.dp)
        else Text(stringResource(R.string.backups_mount_backup), maxLines = 1, softWrap = false)
    }
}

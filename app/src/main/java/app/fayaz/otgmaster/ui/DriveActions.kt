package app.fayaz.otgmaster.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.fayaz.otgmaster.R
import app.fayaz.otgmaster.format.DriveFormatter
import app.fayaz.otgmaster.veracrypt.VeraCryptCipher
import app.fayaz.otgmaster.veracrypt.VeraCryptHash
import app.fayaz.otgmaster.veracrypt.VeraCryptVolumeCreator

/** The word the user types to confirm an erase. Not translated: it is matched exactly. */
const val ERASE_WORD = "ERASE"

/** What a card says about the volume it erases, for the confirmation screens. */
data class DriveSummary(
    val deviceName: String,
    val partitionLabel: String,
    val filesystem: String,
    val container: String,
    val sizeText: String,
    val serial: String?,
)

/** An Encrypt request from the screen; the activity wipes [password] once used. */
class EncryptRequest(
    val password: CharArray,
    val pim: Int?,
    val cipher: VeraCryptCipher,
    val hash: VeraCryptHash,
    val filesystem: DriveFormatter.Filesystem,
    val label: String,
    val full: Boolean,
)

/** A running or finished Format/Encrypt, as the progress dialog shows it. */
data class DriveOperation(
    val title: String,
    val phase: String,
    val fraction: Float?,
    val finished: Boolean = false,
    val error: String? = null,
)

/**
 * The ⋮ menu on a mounted card. A disabled item says why in its second line, rather
 * than vanishing, so a user looking for Format on an NTFS card learns it is not there yet.
 */
@Composable
fun DriveMoreMenu(
    canClearCredentials: Boolean,
    onClearCredentials: () -> Unit,
    formatUnavailable: String?,
    onFormat: () -> Unit,
    encryptUnavailable: String?,
    onEncrypt: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "more_actions_button" }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_actions))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { MenuText(stringResource(R.string.format_drive_menu), formatUnavailable) },
                enabled = formatUnavailable == null,
                onClick = { open = false; onFormat() },
                modifier = Modifier.semantics { contentDescription = "format_menu_item" },
            )
            DropdownMenuItem(
                text = { MenuText(stringResource(R.string.encrypt_drive_menu), encryptUnavailable) },
                enabled = encryptUnavailable == null,
                onClick = { open = false; onEncrypt() },
                modifier = Modifier.semantics { contentDescription = "encrypt_menu_item" },
            )
            if (canClearCredentials) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.clear_cached_credentials)) },
                    onClick = { open = false; onClearCredentials() },
                    modifier = Modifier.semantics { contentDescription = "clear_credentials_menu_item" },
                )
            }
        }
    }
}

@Composable
private fun MenuText(title: String, unavailable: String?) {
    Column {
        Text(title)
        if (unavailable != null) {
            Text(unavailable, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DriveDetails(s: DriveSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        DetailRow(stringResource(R.string.erase_detail_device), s.deviceName)
        s.serial?.let { DetailRow(stringResource(R.string.erase_detail_serial), it) }
        DetailRow(stringResource(R.string.erase_detail_partition), s.partitionLabel.ifEmpty { "—" })
        DetailRow(stringResource(R.string.erase_detail_size), s.sizeText)
        DetailRow(stringResource(R.string.erase_detail_contents), "${s.container} · ${s.filesystem}")
    }
}

@Composable
private fun DetailRow(name: String, value: String) {
    Row {
        Text("$name: ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The quick/full choice. Full is the default; quick says what it leaves behind. */
@Composable
private fun QuickOption(quick: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = quick, onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = "quick_checkbox" })
        Column {
            Text(stringResource(R.string.erase_quick))
            Text(stringResource(if (quick) R.string.erase_quick_on else R.string.erase_quick_off),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EraseConfirmField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.erase_type_to_confirm, ERASE_WORD)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "erase_confirm_field" },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EraseScreen(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    },
                )
            },
        ) { inner ->
            Column(
                modifier = Modifier.fillMaxSize().padding(inner).imePadding()
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
        }
    }
}

/**
 * Format: the same filesystem, size, cluster size and label, written over the volume;
 * inside a container, the container and its password stay.
 */
@Composable
fun FormatScreen(
    drive: DriveSummary,
    insideContainer: Boolean,
    onConfirm: (full: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var quick by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    EraseScreen(stringResource(R.string.format_title, drive.filesystem), onDismiss) {
        Text(stringResource(R.string.format_warning), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleSmall)
        DriveDetails(drive)
        Text(stringResource(if (insideContainer) R.string.format_keeps_container else R.string.format_keeps_plain,
            drive.filesystem), style = MaterialTheme.typography.bodyMedium)
        QuickOption(quick) { quick = it }
        EraseConfirmField(typed) { typed = it }
        Button(
            onClick = { onConfirm(!quick) },
            enabled = typed == ERASE_WORD,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "format_confirm_button" },
        ) { Text(stringResource(R.string.format_button, drive.filesystem)) }
    }
}

/**
 * Encrypt: a new VeraCrypt volume over the whole partition. It erases — the old files are
 * not carried into the new volume — and the screen says so before anything else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncryptScreen(
    drive: DriveSummary,
    onConfirm: (EncryptRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var pimText by remember { mutableStateOf("") }
    var cipher by remember { mutableStateOf(VeraCryptCipher.AES) }
    var hash by remember { mutableStateOf(VeraCryptHash.SHA512) }
    var fs by remember { mutableStateOf(DriveFormatter.Filesystem.EXFAT) }
    var label by remember { mutableStateOf("") }
    var quick by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var cipherOpen by remember { mutableStateOf(false) }
    var hashOpen by remember { mutableStateOf(false) }

    val pim = pimText.toIntOrNull()?.takeIf { it > 0 }
    val pimInvalid = pimText.isNotEmpty() && pimText.toIntOrNull() == null
    val problem = when {
        password.isEmpty() -> null
        password != confirm -> stringResource(R.string.encrypt_passwords_differ)
        pimInvalid -> stringResource(R.string.encrypt_pim_invalid)
        else -> VeraCryptVolumeCreator.passwordProblem(password.toCharArray(), pim)
    }

    EraseScreen(stringResource(R.string.encrypt_title), onDismiss) {
        Text(stringResource(R.string.encrypt_warning), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleSmall)
        DriveDetails(drive)

        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text(stringResource(R.string.encrypt_password)) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "encrypt_password" },
        )
        OutlinedTextField(
            value = confirm, onValueChange = { confirm = it },
            label = { Text(stringResource(R.string.encrypt_password_confirm)) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "encrypt_password_confirm" },
        )
        if (password.isNotEmpty() && password.length < VeraCryptVolumeCreator.SHORT_PASSWORD_CHARS) {
            Text(stringResource(R.string.encrypt_short_password, VeraCryptVolumeCreator.SHORT_PASSWORD_CHARS),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        ExposedDropdownMenuBox(expanded = cipherOpen, onExpandedChange = { cipherOpen = it }) {
            OutlinedTextField(
                value = cipher.displayName, onValueChange = {}, readOnly = true,
                label = { Text(stringResource(R.string.encrypt_cipher)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cipherOpen) },
                modifier = Modifier.menuAnchor().fillMaxWidth().semantics { contentDescription = "encrypt_cipher" },
            )
            ExposedDropdownMenu(expanded = cipherOpen, onDismissRequest = { cipherOpen = false }) {
                VeraCryptCipher.entries.filter { it.isSupported }.forEach { c ->
                    DropdownMenuItem(text = { Text(c.displayName) }, onClick = { cipher = c; cipherOpen = false })
                }
            }
        }
        ExposedDropdownMenuBox(expanded = hashOpen, onExpandedChange = { hashOpen = it }) {
            OutlinedTextField(
                value = hash.displayName, onValueChange = {}, readOnly = true,
                label = { Text(stringResource(R.string.encrypt_kdf)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = hashOpen) },
                modifier = Modifier.menuAnchor().fillMaxWidth().semantics { contentDescription = "encrypt_kdf" },
            )
            ExposedDropdownMenu(expanded = hashOpen, onDismissRequest = { hashOpen = false }) {
                VeraCryptHash.entries.filter { it.isSupported }.forEach { h ->
                    DropdownMenuItem(text = { Text(h.displayName) }, onClick = { hash = h; hashOpen = false })
                }
            }
        }
        OutlinedTextField(
            value = pimText, onValueChange = { pimText = it.filter(Char::isDigit).take(7) },
            label = { Text(stringResource(R.string.encrypt_pim)) },
            supportingText = { Text(stringResource(
                if (hash == VeraCryptHash.ARGON2) R.string.encrypt_pim_hint_argon2 else R.string.encrypt_pim_hint_pbkdf2)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "encrypt_pim" },
        )

        Text(stringResource(R.string.encrypt_filesystem), style = MaterialTheme.typography.titleSmall)
        DriveFormatter.Filesystem.entries.forEach { f ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .selectable(selected = fs == f, onClick = { fs = f })
                    .semantics { contentDescription = "encrypt_fs_${f.name.lowercase()}" },
            ) {
                RadioButton(selected = fs == f, onClick = { fs = f })
                Text(f.displayName)
            }
        }
        OutlinedTextField(
            value = label, onValueChange = { label = it.take(11) },
            label = { Text(stringResource(R.string.encrypt_label)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "encrypt_label" },
        )
        QuickOption(quick) { quick = it }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        EraseConfirmField(typed) { typed = it }
        Button(
            onClick = {
                onConfirm(EncryptRequest(password.toCharArray(), pim, cipher, hash, fs, label.trim(), !quick))
                password = ""; confirm = ""
            },
            enabled = typed == ERASE_WORD && password.isNotEmpty() && problem == null,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "encrypt_confirm_button" },
        ) { Text(stringResource(R.string.encrypt_button)) }
        Spacer(Modifier.height(8.dp))
    }
}

/** Progress while the drive is written; closable only once it has finished or failed. */
@Composable
fun DriveOperationDialog(op: DriveOperation, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (op.finished) onClose() },
        title = { Text(op.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    op.error != null -> Text(op.error, color = MaterialTheme.colorScheme.error)
                    op.finished -> Text(op.phase)
                    else -> {
                        Text(op.phase)
                        if (op.fraction != null) {
                            LinearProgressIndicator(progress = { op.fraction }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(stringResource(R.string.erase_keep_attached), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (op.finished) {
                TextButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "operation_done_button" }) {
                    Text(stringResource(R.string.ok))
                }
            }
        },
    )
}

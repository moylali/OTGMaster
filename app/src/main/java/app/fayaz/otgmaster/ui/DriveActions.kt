package app.fayaz.otgmaster.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.fayaz.otgmaster.R
import app.fayaz.otgmaster.format.DriveFormatter
import app.fayaz.otgmaster.veracrypt.VeraCryptCipher
import app.fayaz.otgmaster.veracrypt.VeraCryptHash
import app.fayaz.otgmaster.veracrypt.VeraCryptKeyfiles
import app.fayaz.otgmaster.veracrypt.VeraCryptVolumeCreator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/** What Encrypt can create. VeraCrypt only for now; the form under the picker is the type's own. */
enum class EncryptionType(val displayName: String) { VERACRYPT("VeraCrypt") }

/** An Encrypt request from the screen; the activity wipes [password] once used. */
class EncryptRequest(
    val type: EncryptionType,
    val password: CharArray,
    val pim: Int?,
    val cipher: VeraCryptCipher,
    val hash: VeraCryptHash,
    val keyfiles: List<Uri>,
    val filesystem: DriveFormatter.Filesystem,
    val label: String,
    val full: Boolean,
)

/** A Format request from the screen: [filesystem] may differ from the volume's current one. */
class FormatRequest(val filesystem: DriveFormatter.Filesystem, val label: String, val full: Boolean)

/** A running or finished Format/Encrypt, as the progress dialog shows it. */
data class DriveOperation(
    val title: String,
    val phase: String,
    val fraction: Float?,
    val finished: Boolean = false,
    val error: String? = null,
)

/**
 * One action on a mounted card. [id] is its test handle (the same whether it shows as a
 * button or in the menu); [unavailable] says why it is disabled, or is null when it is not.
 */
class DriveAction(
    val id: String?,
    val title: String,
    val unavailable: String? = null,
    val onClick: () -> Unit,
)

/**
 * A mounted card's actions. [primary] are always buttons. [secondary] take whatever room is
 * left on the same line, in order, as the same buttons; what does not fit goes into a
 * "More actions ▾" menu, measured at the card's current width — so a wide tablet card shows
 * every action and a phone keeps the two everyday ones. A disabled action always goes into
 * the menu, where its second line says why, rather than sitting as a greyed button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DriveActionRow(primary: List<DriveAction>, secondary: List<DriveAction>) {
    val gap = 8.dp
    val usable = secondary.filter { it.unavailable == null }
    SubcomposeLayout { constraints ->
        val fits = if (!constraints.hasBoundedWidth) usable.size else {
            val loose = Constraints()
            fun widthOf(slot: String, content: @Composable () -> Unit) =
                subcompose(slot, content).sumOf { it.measure(loose).width }
            val gapPx = gap.roundToPx()
            val start = primary.indices.sumOf { i -> widthOf("p$i") { ActionButton(primary[i]) } + gapPx }
            val widths = usable.indices.map { i -> widthOf("s$i") { ActionButton(usable[i]) } }
            val moreWidth = widthOf("more") { MoreActionsButton(emptyList()) }
            val disabledLeft = secondary.size > usable.size
            fun lineWidth(k: Int): Int {
                val more = k < usable.size || disabledLeft
                return start + widths.take(k).sum() + gapPx * k + if (more) moreWidth else -gapPx
            }
            var k = usable.size
            while (k > 0 && lineWidth(k) > constraints.maxWidth) k--
            k
        }
        val shown = usable.take(fits)
        val overflow = secondary.filter { it !in shown }
        val row = subcompose("row") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
                primary.forEach { ActionButton(it) }
                shown.forEach { ActionButton(it) }
                if (overflow.isNotEmpty()) MoreActionsButton(overflow)
            }
        }.map { it.measure(constraints) }
        layout(row.maxOfOrNull { it.width } ?: 0, row.maxOfOrNull { it.height } ?: 0) {
            row.forEach { it.place(0, 0) }
        }
    }
}

private fun Modifier.actionId(id: String?) = if (id == null) this else semantics { contentDescription = id }

/** Every action on the row looks the same; which ones show is the only difference. */
@Composable
private fun ActionButton(a: DriveAction) {
    // One line, never clipped: the row measures each button at its full width and moves
    // whatever does not fit into More actions instead.
    Button(onClick = a.onClick, enabled = a.unavailable == null, modifier = Modifier.actionId(a.id)) {
        Text(a.title, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun MoreActionsButton(items: List<DriveAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "more_actions_button" }) {
            Text(stringResource(R.string.more_actions))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { a ->
                DropdownMenuItem(
                    text = { MenuText(a.title, a.unavailable) },
                    enabled = a.unavailable == null,
                    onClick = { open = false; a.onClick() },
                    modifier = Modifier.actionId(a.id),
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

/** A read-only field that opens a menu of [options]; [id] is the test handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Picker(label: String, value: T, options: List<T>, name: (T) -> String, id: String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = name(value), onValueChange = {}, readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor().fillMaxWidth().semantics { contentDescription = id },
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(name(o)) }, onClick = { onPick(o); open = false })
            }
        }
    }
}

/** A password field with the unlock form's show/hide eye; hidden to start with. */
@Composable
private fun PasswordField(value: String, label: String, id: String, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = stringResource(if (visible) R.string.cd_hide_password else R.string.cd_show_password))
            }
        },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = id },
    )
}

@Composable
private fun LabelField(value: String, id: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.take(11)) },
        label = { Text(stringResource(R.string.erase_label)) },
        supportingText = { Text(stringResource(R.string.erase_label_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = id },
    )
}

/**
 * The last step before anything is written: a dialog that says what will be lost, and
 * goes ahead only once ERASE is typed. The form behind it stays as it was if cancelled.
 */
@Composable
private fun EraseConfirmDialog(drive: DriveSummary, confirmText: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.erase_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.erase_confirm_warning,
                    drive.partitionLabel.ifEmpty { drive.deviceName }, drive.sizeText),
                    color = MaterialTheme.colorScheme.error)
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.erase_type_to_confirm, ERASE_WORD)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "erase_confirm_field" },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = typed == ERASE_WORD,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.semantics { contentDescription = "erase_dialog_confirm" },
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
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
            ) {
                content()
                // Room to scroll the last button clear of the system bars. A Galaxy Tab's
                // taskbar sits over the bottom of this dialog in landscape without being
                // reported as an inset to it, so a fixed margin backs up the inset.
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                Spacer(Modifier.height(72.dp))
            }
        }
    }
}

/**
 * Format: an empty exFAT or FAT32 of the volume's size, the current one by default;
 * inside a container, the container and its password stay.
 */
@Composable
fun FormatScreen(
    drive: DriveSummary,
    current: DriveFormatter.Filesystem?,
    suggestedLabel: String,
    insideContainer: Boolean,
    onConfirm: (FormatRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    var fs by remember { mutableStateOf(current ?: DriveFormatter.Filesystem.EXFAT) }
    var label by remember { mutableStateOf(suggestedLabel) }
    var quick by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    EraseScreen(stringResource(R.string.format_title, drive.filesystem), onDismiss) {
        Text(stringResource(R.string.format_warning), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleSmall)
        DriveDetails(drive)
        Text(stringResource(if (insideContainer) R.string.format_keeps_container else R.string.format_keeps_plain),
            style = MaterialTheme.typography.bodyMedium)
        Picker(stringResource(R.string.erase_filesystem), fs, DriveFormatter.Filesystem.entries, { it.displayName },
            "format_fs") { fs = it }
        LabelField(label, "format_label") { label = it }
        QuickOption(quick) { quick = it }
        Button(
            onClick = { confirming = true },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "format_confirm_button" },
        ) { Text(stringResource(R.string.format_button)) }
    }
    if (confirming) {
        EraseConfirmDialog(drive, stringResource(R.string.format_button),
            onConfirm = { confirming = false; onConfirm(FormatRequest(fs, label.trim(), !quick)) },
            onDismiss = { confirming = false })
    }
}

/**
 * Encrypt: a new encrypted volume over the whole partition. It erases — the old files are
 * not carried into the new volume — and the screen says so before anything else. The type
 * picker decides the form under it; VeraCrypt is the only type for now.
 */
@Composable
fun EncryptScreen(
    drive: DriveSummary,
    suggestedLabel: String,
    onConfirm: (EncryptRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    var type by remember { mutableStateOf(EncryptionType.VERACRYPT) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var pimText by remember { mutableStateOf("") }
    var cipher by remember { mutableStateOf(VeraCryptCipher.AES) }
    var hash by remember { mutableStateOf(VeraCryptHash.SHA512) }
    var keyfiles by remember { mutableStateOf(emptyList<Uri>()) }
    var fs by remember { mutableStateOf(DriveFormatter.Filesystem.EXFAT) }
    var label by remember { mutableStateOf(suggestedLabel) }
    var quick by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var keyfileError by remember { mutableStateOf<String?>(null) }
    // Read once, when the volume is created; nothing needs the grant after that. Picking
    // adds to the list, so selected and newly created keyfiles can be combined.
    val keyfileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        keyfiles = (keyfiles + uris).distinct()
    }
    // A new keyfile: the user picks where it goes, then it gets VeraCrypt's 64 random bytes.
    val createKeyfile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            keyfileError = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { VeraCryptKeyfiles.generate(it) }
                        ?: throw java.io.IOException("could not open it for writing")
                }.exceptionOrNull()?.let { context.getString(R.string.encrypt_keyfile_create_failed, it.message ?: it.javaClass.simpleName) }
            }
            if (keyfileError == null) keyfiles = (keyfiles + uri).distinct()
        }
    }
    val keyfileNames = remember(keyfiles) { keyfiles.map { displayName(context, it) } }

    val pim = pimText.toIntOrNull()?.takeIf { it > 0 }
    val pimInvalid = pimText.isNotEmpty() && pimText.toIntOrNull() == null
    val hasCredential = password.isNotEmpty() || keyfiles.isNotEmpty()
    val problem = when {
        !hasCredential -> null
        password != confirm -> stringResource(R.string.encrypt_passwords_differ)
        pimInvalid -> stringResource(R.string.encrypt_pim_invalid)
        else -> VeraCryptVolumeCreator.passwordProblem(password.toCharArray(), pim, keyfiles.isNotEmpty())
    }

    EraseScreen(stringResource(R.string.encrypt_title), onDismiss) {
        Text(stringResource(R.string.encrypt_warning), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleSmall)
        DriveDetails(drive)

        Picker(stringResource(R.string.encrypt_type), type, EncryptionType.entries, { it.displayName },
            "encrypt_type") { type = it }

        when (type) {
            EncryptionType.VERACRYPT -> {
                PasswordField(password, stringResource(R.string.encrypt_password), "encrypt_password") { password = it }
                PasswordField(confirm, stringResource(R.string.encrypt_password_confirm), "encrypt_password_confirm") { confirm = it }
                if (password.isNotEmpty() && password.length < VeraCryptVolumeCreator.SHORT_PASSWORD_CHARS) {
                    Text(stringResource(R.string.encrypt_short_password, VeraCryptVolumeCreator.SHORT_PASSWORD_CHARS),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { keyfileLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f).semantics { contentDescription = "encrypt_keyfiles" },
                    ) { Text(stringResource(R.string.select_keyfiles, keyfiles.size), maxLines = 1, softWrap = false) }
                    OutlinedButton(
                        onClick = { keyfileError = null; createKeyfile.launch("otgmaster.key") },
                        modifier = Modifier.weight(1f).semantics { contentDescription = "encrypt_create_keyfile" },
                    ) { Text(stringResource(R.string.encrypt_create_keyfile), maxLines = 1, softWrap = false) }
                    if (keyfiles.isNotEmpty()) {
                        IconButton(onClick = { keyfiles = emptyList() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_clear_keyfiles))
                        }
                    }
                }
                keyfileError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (keyfiles.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        keyfileNames.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                    Text(stringResource(R.string.encrypt_keyfiles_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Picker(stringResource(R.string.encrypt_cipher), cipher, VeraCryptCipher.entries.filter { it.isSupported },
                    { it.displayName }, "encrypt_cipher") { cipher = it }
                Picker(stringResource(R.string.encrypt_kdf), hash, VeraCryptHash.entries.filter { it.isSupported },
                    { it.displayName }, "encrypt_kdf") { hash = it }
                OutlinedTextField(
                    value = pimText, onValueChange = { pimText = it.filter(Char::isDigit).take(7) },
                    label = { Text(stringResource(R.string.encrypt_pim)) },
                    supportingText = { Text(stringResource(
                        if (hash == VeraCryptHash.ARGON2) R.string.encrypt_pim_hint_argon2 else R.string.encrypt_pim_hint_pbkdf2)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "encrypt_pim" },
                )
            }
        }

        Picker(stringResource(R.string.encrypt_filesystem), fs, DriveFormatter.Filesystem.entries, { it.displayName },
            "encrypt_fs") { fs = it }
        LabelField(label, "encrypt_label") { label = it }
        QuickOption(quick) { quick = it }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = { confirming = true },
            enabled = hasCredential && problem == null,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "encrypt_confirm_button" },
        ) { Text(stringResource(R.string.encrypt_button)) }
    }
    if (confirming) {
        EraseConfirmDialog(drive, stringResource(R.string.encrypt_button),
            onConfirm = {
                confirming = false
                onConfirm(EncryptRequest(type, password.toCharArray(), pim, cipher, hash, keyfiles, fs, label.trim(), !quick))
                password = ""; confirm = ""
            },
            onDismiss = { confirming = false })
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

/** A document's name as its provider shows it, or the last part of its URI. */
private fun displayName(context: android.content.Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()


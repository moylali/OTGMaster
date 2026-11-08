package app.fayaz.otgmaster.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.fayaz.otgmaster.R
import app.fayaz.otgmaster.veracrypt.HiddenVolumeCredentials
import app.fayaz.otgmaster.veracrypt.VeraCryptCipher
import app.fayaz.otgmaster.veracrypt.VeraCryptHash

private const val PROTECTION_HELP_URL = "https://veracrypt.io/en/Protection%20of%20Hidden%20Volumes.html"

/**
 * VeraCrypt's Mount Options, the Hidden Volume Protection group. It is never saved or reused:
 * VeraCrypt turns the option off after every mount attempt, successful or not, so that a volume
 * is not mounted protected by accident in front of someone who could then learn of the hidden one.
 */
class HiddenProtectionFormState {
    var enabled by mutableStateOf(false)
    var password by mutableStateOf("")
    var pim by mutableStateOf("")
    var keyfiles by mutableStateOf(emptyList<Uri>())
    var cipher by mutableStateOf(VeraCryptCipher.DEFAULT)
    var hash by mutableStateOf(VeraCryptHash.DEFAULT)

    /** A password, keyfiles or both are needed, as for the volume itself. */
    val isComplete: Boolean get() = !enabled || password.isNotEmpty() || keyfiles.isNotEmpty()

    /** What the unlock needs, or null when protection is off. */
    fun credentials(): HiddenVolumeCredentials? = if (!enabled) null else
        HiddenVolumeCredentials(password.toCharArray(), pim.toIntOrNull(), keyfiles, cipher, hash)

    fun reset() {
        enabled = false
        password = ""
        pim = ""
        keyfiles = emptyList()
        cipher = VeraCryptCipher.DEFAULT
        hash = VeraCryptHash.DEFAULT
    }
}

@Composable
fun HiddenVolumeProtectionSection(state: HiddenProtectionFormState, mountReadOnly: Boolean) {
    // As in VeraCrypt's dialog: a read-only mount cannot damage anything, so the option is
    // unticked and greyed out.
    LaunchedEffect(mountReadOnly) { if (mountReadOnly) state.reset() }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.hidden_protection_switch),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = state.enabled,
                enabled = !mountReadOnly,
                onCheckedChange = { if (it) state.enabled = true else state.reset() },
                modifier = Modifier.semantics { contentDescription = "hidden_protect_switch" },
            )
        }
        if (state.enabled) ProtectionFields(state)
    }
}

@Composable
private fun ProtectionFields(state: HiddenProtectionFormState) {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(false) }
    val keyfilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        state.keyfiles = (state.keyfiles + uris).distinct()
    }
    OutlinedTextField(
        value = state.password,
        onValueChange = { state.password = it },
        label = { Text(stringResource(R.string.hidden_protection_password)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = stringResource(if (visible) R.string.cd_hide_password else R.string.cd_show_password),
                )
            }
        },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "hidden_protect_password" },
    )
    OutlinedTextField(
        value = state.pim,
        onValueChange = { state.pim = it },
        label = { Text(stringResource(R.string.label_pim)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrect = false),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "hidden_protect_pim" },
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { keyfilePicker.launch(arrayOf("*/*")) },
            modifier = Modifier.weight(1f).semantics { contentDescription = "hidden_protect_keyfiles_button" },
        ) { Text(stringResource(R.string.select_keyfiles, state.keyfiles.size)) }
        if (state.keyfiles.isNotEmpty()) {
            IconButton(onClick = { state.keyfiles = emptyList() }) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_clear_keyfiles))
            }
        }
    }
    Picker(
        label = stringResource(R.string.label_encryption_algorithm),
        current = state.cipher.displayName,
        description = "hidden_protect_cipher",
        options = VeraCryptCipher.entries.filter { it.isSupported },
        name = { it.displayName },
        onPick = { state.cipher = it },
    )
    Picker(
        label = stringResource(R.string.label_hash_algorithm),
        current = state.hash.displayName,
        description = "hidden_protect_hash",
        options = VeraCryptHash.entries.filter { it.isSupported },
        name = { it.displayName },
        onPick = { state.hash = it },
    )
    TextButton(onClick = {
        runCatching {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(PROTECTION_HELP_URL)))
        }
    }) { Text(stringResource(R.string.hidden_protection_help)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Picker(
    label: String,
    current: String,
    description: String,
    options: List<T>,
    name: (T) -> String,
    onPick: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                .semantics { contentDescription = description },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(name(option)) }, onClick = { onPick(option); expanded = false })
            }
        }
    }
}

/** VeraCrypt's message after a protected mount (HIDVOL_PROT_WARN_AFTER_MOUNT). */
@Composable
fun HiddenProtectionActiveDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hidden_protection_active_title)) },
        text = { Text(stringResource(R.string.hidden_protection_active_text)) },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "hidden_protect_active_ok" }) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

/** VeraCrypt's warning once a write has been stopped (DAMAGE_TO_HIDDEN_VOLUME_PREVENTED). */
@Composable
fun HiddenProtectionTriggeredDialog(volumeName: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hidden_protection_triggered_title)) },
        text = { Text(stringResource(R.string.hidden_protection_triggered_text, volumeName)) },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "hidden_protect_triggered_ok" }) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

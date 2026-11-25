package app.fayaz.containermaster

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pick a container file, enter its password and PIM, unlock; the open containers are listed
 * with Lock and Lock all. Every control carries a content description, which the E2E suite
 * finds them by.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }
}

private fun Modifier.desc(d: String) = this.semantics { contentDescription = d }.testTag(d)

@Composable
private fun Screen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val open by ContainerSessions.open.collectAsState()
    var picked by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var password by remember { mutableStateOf("") }
    var pim by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment ?: "container"
            picked = uri to name
            message = null
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.intro))
        OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.desc("choose_file")) {
            Text(stringResource(R.string.choose_file))
        }
        picked?.let { Text(stringResource(R.string.chosen_file, it.second), Modifier.desc("chosen_file")) }
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text(stringResource(R.string.password)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true, modifier = Modifier.fillMaxWidth().desc("password"),
        )
        OutlinedTextField(
            value = pim, onValueChange = { pim = it.filter(Char::isDigit) },
            label = { Text(stringResource(R.string.pim)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, modifier = Modifier.fillMaxWidth().desc("pim"),
        )
        val wrong = stringResource(R.string.wrong_password)
        val already = stringResource(R.string.already_open)
        val failed = stringResource(R.string.open_failed, "%s")
        val pimInvalid = stringResource(R.string.pim_invalid)
        Button(
            enabled = picked != null && !busy,
            modifier = Modifier.desc("unlock"),
            onClick = {
                val (uri, name) = picked ?: return@Button
                val pimValue = if (pim.isEmpty()) null else pim.toIntOrNull()
                if (pim.isNotEmpty() && pimValue == null) { message = pimInvalid; return@Button }
                val chars = password.toCharArray()
                busy = true; message = null
                SessionService.opening = true
                SessionService.start(context)
                scope.launch {
                    message = withContext(Dispatchers.IO) {
                        try {
                            ContainerSessions.openVeraCrypt(context.applicationContext, uri, name, chars, pimValue)
                            null
                        } catch (e: IllegalArgumentException) {
                            wrong
                        } catch (e: ContainerSessions.AlreadyOpenException) {
                            already
                        } catch (e: Exception) {
                            failed.replace("%s", e.message ?: e.javaClass.simpleName)
                        } finally {
                            chars.fill('\u0000')
                        }
                    }
                    SessionService.opening = false
                    // Nudges the service to stop if the unlock failed and nothing is open.
                    SessionService.start(context)
                    busy = false
                    if (message == null) { password = ""; pim = ""; picked = null }
                }
            },
        ) { Text(stringResource(if (busy) R.string.unlocking else R.string.unlock)) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.desc("message")) }

        Text(stringResource(R.string.open_containers), style = MaterialTheme.typography.titleMedium)
        if (open.isEmpty()) Text(stringResource(R.string.none_open), Modifier.desc("none_open"))
        for (s in open) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, Modifier.weight(1f).desc("open_${s.id}"))
                OutlinedButton(onClick = {
                    scope.launch(Dispatchers.IO) { ContainerSessions.lock(context.applicationContext, s.id) }
                }, modifier = Modifier.desc("lock_${s.id}")) { Text(stringResource(R.string.lock)) }
            }
        }
        if (open.isNotEmpty()) {
            Button(onClick = {
                scope.launch(Dispatchers.IO) { ContainerSessions.lockAll(context.applicationContext) }
            }, modifier = Modifier.desc("lock_all")) { Text(stringResource(R.string.lock_all)) }
        }
    }
}

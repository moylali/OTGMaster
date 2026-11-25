package app.fayaz.containermaster

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.fayaz.otgmaster.applySystemBarAppearance
import app.fayaz.otgmaster.feedback.FeedbackActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Container Master's screen, laid out the way OTG Master's is: a top bar with the open
 * containers (a badge with their count) and Settings; below it, choose a file, the form for
 * the format the file turns out to be, Unlock, and the log with Report issue, Clear and Copy.
 * The open containers, with Lock and Lock all, are in a panel from the top bar's icon, as OTG
 * Master's mounted drives are. Every control carries a content description, which the E2E
 * suite finds it by.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val prefs = ContainerMasterApp.prefs(this)
        setContent {
            var theme by remember { mutableStateOf(prefs.getString(ContainerMasterApp.THEME, "SYSTEM") ?: "SYSTEM") }
            val dark = when (theme) { "LIGHT" -> false; "DARK" -> true; else -> isSystemInDarkTheme() }
            LaunchedEffect(dark) { applySystemBarAppearance(dark) }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Screen(theme) { t -> theme = t; prefs.edit().putString(ContainerMasterApp.THEME, t).apply() }
                }
            }
        }
    }
}

private fun Modifier.desc(d: String) = this.semantics { contentDescription = d }.testTag(d)

private data class Picked(val uri: Uri, val name: String, val format: ContainerFormat)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun Screen(theme: String, onTheme: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val open by ContainerSessions.open.collectAsState()
    val logs by CmLog.lines.collectAsState()
    var picked by remember { mutableStateOf<Picked?>(null) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var pim by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showOpen by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            message = null
            scope.launch {
                picked = withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment ?: "container"
                    val format = runCatching { ContainerSessions.detect(context, uri, name) }
                        .getOrElse { CmLog.log("Could not read the chosen file: ${it.message}"); ContainerFormat.VERACRYPT }
                    Picked(uri, name, format)
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        IconButton(onClick = { showOpen = true }, modifier = Modifier.desc("open_containers")) {
                            BadgedBox(badge = { if (open.isNotEmpty()) Badge { Text(open.size.toString()) } }) {
                                Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.cd_open_containers, open.size))
                            }
                        }
                        IconButton(onClick = { showSettings = true }, modifier = Modifier.desc("settings")) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.padding(padding).imePadding()
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.intro))
                OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.desc("choose_file")) {
                    Text(stringResource(R.string.choose_file))
                }
                picked?.let { p ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.chosen_file, p.name), Modifier.desc("chosen_file"))
                            Text(stringResource(when (p.format) {
                                ContainerFormat.VERACRYPT -> R.string.format_veracrypt
                                ContainerFormat.LUKS -> R.string.format_luks_later
                                ContainerFormat.ZIP -> R.string.format_zip_later
                            }), style = MaterialTheme.typography.bodySmall, modifier = Modifier.desc("format"))
                        }
                    }
                }
                // The form follows the chosen file's format. VeraCrypt: a password and a PIM.
                // LUKS and ZIP are recognised and named, and come in later versions.
                val isLuks = picked?.format == ContainerFormat.LUKS
                if (picked == null || picked?.format == ContainerFormat.VERACRYPT || isLuks) {
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text(stringResource(R.string.password)) },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = stringResource(R.string.cd_show_password))
                            }
                        },
                        singleLine = true, modifier = Modifier.fillMaxWidth().desc("password"),
                    )
                    if (!isLuks) OutlinedTextField(
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
                            val p = picked ?: return@Button
                            val pimValue = if (pim.isEmpty()) null else pim.toIntOrNull()
                            if (pim.isNotEmpty() && pimValue == null) { message = pimInvalid; return@Button }
                            val chars = password.toCharArray()
                            busy = true; message = null
                            SessionService.opening = true
                            SessionService.start(context)
                            scope.launch {
                                message = withContext(Dispatchers.IO) {
                                    try {
                                        if (p.format == ContainerFormat.LUKS) ContainerSessions.openLuks(context.applicationContext, p.uri, p.name, chars)
                                        else ContainerSessions.openVeraCrypt(context.applicationContext, p.uri, p.name, chars, pimValue)
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
                                SessionService.start(context)
                                busy = false
                                if (message == null) { password = ""; pim = ""; picked = null }
                            }
                        },
                    ) { Text(stringResource(if (busy) R.string.unlocking else R.string.unlock)) }
                }
                if (picked?.format == ContainerFormat.ZIP) {
                    val already = stringResource(R.string.already_open)
                    val failed = stringResource(R.string.open_failed, "%s")
                    Button(enabled = !busy, modifier = Modifier.desc("open_zip"), onClick = {
                        val p = picked ?: return@Button
                        busy = true; message = null
                        SessionService.opening = true
                        SessionService.start(context)
                        scope.launch {
                            message = withContext(Dispatchers.IO) {
                                try { ContainerSessions.openZip(context.applicationContext, p.uri, p.name); null }
                                catch (e: ContainerSessions.AlreadyOpenException) { already }
                                catch (e: Exception) { failed.replace("%s", e.message ?: e.javaClass.simpleName) }
                            }
                            SessionService.opening = false
                            SessionService.start(context)
                            busy = false
                            if (message == null) picked = null
                        }
                    }) { Text(stringResource(R.string.open_archive)) }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.desc("message")) }

                LogsSection(logs)
            }
        }

        if (showOpen) SidePanel(stringResource(R.string.open_containers), "close_open_containers", { showOpen = false }) {
            if (open.isEmpty()) Text(stringResource(R.string.none_open), Modifier.desc("none_open"))
            for (s in open) {
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(s.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.desc("open_${s.id}"))
                        Text(stringResource(R.string.open_details, if (s.format == "ZIP") "ZIP" else "${s.format}, ${s.fileSystemName}"),
                            style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            OutlinedButton(onClick = {
                                scope.launch(Dispatchers.IO) { ContainerSessions.lock(context.applicationContext, s.id) }
                            }, modifier = Modifier.desc("lock_${s.id}")) { Text(stringResource(R.string.lock)) }
                        }
                    }
                }
            }
            if (open.isNotEmpty()) {
                Button(onClick = {
                    scope.launch(Dispatchers.IO) { ContainerSessions.lockAll(context.applicationContext) }
                }, modifier = Modifier.fillMaxWidth().desc("lock_all")) { Text(stringResource(R.string.lock_all)) }
            }
        }

        if (showSettings) SidePanel(stringResource(R.string.settings), "close_settings", { showSettings = false }) {
            Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleMedium)
            for ((value, label) in listOf("SYSTEM" to R.string.theme_system, "LIGHT" to R.string.theme_light, "DARK" to R.string.theme_dark)) {
                Row(Modifier.fillMaxWidth().clickable { onTheme(value) }.desc("theme_$value"), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = theme == value, onClick = { onTheme(value) })
                    Text(stringResource(label))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            val prefs = remember { ContainerMasterApp.prefs(context) }
            var idle by remember { mutableStateOf(ContainerSessions.idleMinutes) }
            Text(stringResource(R.string.idle_lock), style = MaterialTheme.typography.titleMedium)
            for (m in listOf(1, 5, 15, 30, 60, 0)) {
                val pickIdle = {
                    idle = m; ContainerSessions.idleMinutes = m
                    prefs.edit().putInt(ContainerMasterApp.IDLE_MINUTES, m).apply()
                    CmLog.log(if (m == 0) "Idle lock: never" else "Idle lock: after $m min")
                }
                Row(Modifier.fillMaxWidth().clickable { pickIdle() }.desc("idle_$m"), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = idle == m, onClick = { pickIdle() })
                    Text(if (m == 0) stringResource(R.string.idle_never) else pluralStringResource(R.plurals.idle_minutes, m, m))
                }
            }
            var screenOff by remember { mutableStateOf(prefs.getBoolean(ContainerMasterApp.LOCK_ON_SCREEN_OFF, false)) }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.lock_on_screen_off), Modifier.weight(1f))
                Switch(checked = screenOff, onCheckedChange = {
                    screenOff = it; prefs.edit().putBoolean(ContainerMasterApp.LOCK_ON_SCREEN_OFF, it).apply()
                }, modifier = Modifier.desc("lock_on_screen_off"))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = { context.startActivity(Intent(context, FeedbackActivity::class.java)) },
                modifier = Modifier.desc("settings_report_issue")) {
                Text(stringResource(app.fayaz.otgmaster.common.R.string.feedback_report_issue))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.version_label, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.GIT_COMMIT),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.desc("version"))
        }
    }
}

@Composable
private fun LogsSection(logs: List<String>) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        val label = stringResource(R.string.logs)
        Text(label, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { context.startActivity(Intent(context, FeedbackActivity::class.java)) },
                modifier = Modifier.desc("report_issue")) {
                Text(stringResource(app.fayaz.otgmaster.common.R.string.feedback_report_issue))
            }
            IconButton(onClick = { CmLog.clear() }, modifier = Modifier.desc("clear_logs")) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_clear_logs))
            }
            IconButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(label, logs.joinToString("\n")))
            }, modifier = Modifier.desc("copy_logs")) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.cd_copy_logs))
            }
        }
    }
    Card(Modifier.fillMaxWidth().height(180.dp)) {
        // Newest first, as in OTG Master, so the latest step shows without scrolling.
        LazyColumn(Modifier.padding(8.dp).desc("log")) {
            items(logs.asReversed()) { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** A panel sliding in from the end, over a scrim, like OTG Master's settings drawer. */
@Composable
private fun SidePanel(title: String, closeDesc: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).clickable(onClick = onDismiss))
        Surface(Modifier.fillMaxHeight().width(320.dp).align(Alignment.CenterEnd), tonalElevation = 4.dp) {
            Column(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
                    .padding(16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = onDismiss, modifier = Modifier.desc(closeDesc)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp)) { content() }
            }
        }
    }
}

package app.fayaz.containermaster

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.fragment.app.FragmentActivity
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
import androidx.compose.ui.platform.LocalUriHandler
import android.widget.Toast
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
class MainActivity : FragmentActivity() {
    /** A file handed over by Open with, until the screen has taken it. */
    private val incoming = mutableStateOf<Uri?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewedUri(intent)?.let { incoming.value = it }
    }

    private fun viewedUri(intent: Intent?): Uri? = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val prefs = ContainerMasterApp.prefs(this)
        incoming.value = viewedUri(intent)
        setContent {
            var theme by remember { mutableStateOf(prefs.getString(ContainerMasterApp.THEME, "SYSTEM") ?: "SYSTEM") }
            val dark = when (theme) { "LIGHT" -> false; "DARK" -> true; else -> isSystemInDarkTheme() }
            LaunchedEffect(dark) { applySystemBarAppearance(dark) }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Screen(theme, incoming.value, { incoming.value = null }) { t -> theme = t; prefs.edit().putString(ContainerMasterApp.THEME, t).apply() }
                }
            }
        }
    }
}

private fun Modifier.desc(d: String) = this.semantics { contentDescription = d }.testTag(d)

private data class Picked(val uri: Uri, val name: String, val format: ContainerFormat, val passwordId: String?)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun Screen(theme: String, incoming: Uri?, onIncomingTaken: () -> Unit, onTheme: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The engine runs in another process (Milestone 2b): the screen holds copies, refreshed
    // whenever the engine announces a change.
    val engine = remember { EngineClient(context) }
    var state by remember { mutableStateOf(EngineClient.State(emptyList(), emptyList(), 15)) }
    LaunchedEffect(Unit) {
        engine.states().collect { state = withContext(Dispatchers.IO) { runCatching { engine.state() }.getOrDefault(state) } }
    }
    val open = state.open
    val logs = state.logs
    var picked by remember { mutableStateOf<Picked?>(null) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var pim by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showOpen by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val prefs = remember { ContainerMasterApp.prefs(context) }
    val saved = remember { SavedPasswords(context) }
    var remember by remember { mutableStateOf(prefs.getBoolean(ContainerMasterApp.REMEMBER_PASSWORDS, false)) }
    var readOnly by remember { mutableStateOf(prefs.getBoolean(ContainerMasterApp.READ_ONLY, true)) }
    val savedWrong = stringResource(R.string.saved_password_wrong)
    val savedUnreadable = stringResource(R.string.saved_password_unreadable)
    val already = stringResource(R.string.already_open)
    val failed = stringResource(R.string.open_failed, "%s")

    fun openWithoutPassword(p: Picked) {
        busy = true; message = null
        SessionService.start(context)
        scope.launch {
            message = withContext(Dispatchers.IO) {
                when (val r = engine.open(p.format, p.uri, p.name)) {
                    EngineClient.OpenResult.Ok -> null
                    EngineClient.OpenResult.AlreadyOpen -> already
                    EngineClient.OpenResult.WrongPassword -> failed.replace("%s", "wrong password")
                    is EngineClient.OpenResult.Failed -> failed.replace("%s", r.message)
                }
            }
            busy = false
            if (message == null) { picked = null; showOpen = true }
        }
    }

    // A file chosen with the button, or handed over by Open with ([handedOver]): its format, then
    // the saved password if there is one; a file handed over that needs no password opens at once.
    fun take(uri: Uri, handedOver: Boolean) {
            message = null
            scope.launch {
                picked = withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment ?: "container"
                    val d = runCatching { engine.detect(uri, name) }.getOrDefault(EngineClient.Detected(ContainerFormat.VERACRYPT, null))
                    Picked(uri, name, d.format, d.passwordId)
                }
                // A saved password for this container: the phone's lock, then open it with that.
                val p = picked
                if (p?.passwordId != null && remember && withContext(Dispatchers.IO) { saved.has(p.passwordId) }) {
                    (context as? FragmentActivity)?.askPhoneLock(context.getString(R.string.saved_prompt_title), p.name) {
                        busy = true; message = null
                        SessionService.start(context)
                        scope.launch {
                            message = withContext(Dispatchers.IO) {
                                val s = runCatching { saved.load(p.passwordId) }.getOrNull()
                                    ?: return@withContext savedUnreadable
                                when (val r = engine.open(p.format, p.uri, p.name, s.password, s.pim, writable = !readOnly)) {
                                    EngineClient.OpenResult.Ok -> null
                                    // The container's password was changed since: the saved one only fails now.
                                    EngineClient.OpenResult.WrongPassword -> { saved.forget(p.passwordId); savedWrong }
                                    EngineClient.OpenResult.AlreadyOpen -> already
                                    is EngineClient.OpenResult.Failed -> failed.replace("%s", r.message)
                                }
                            }
                            busy = false
                            if (message == null) { picked = null; showOpen = true }
                        }
                    }
                }
                if (handedOver && p != null && (p.format == ContainerFormat.ZIP || p.format == ContainerFormat.ISO)) openWithoutPassword(p)
            }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) take(uri, handedOver = false) }
    LaunchedEffect(incoming) { incoming?.let { onIncomingTaken(); take(it, handedOver = true) } }

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
                                ContainerFormat.ISO -> R.string.format_iso
                                ContainerFormat.AESCRYPT -> R.string.format_aescrypt
                            }), style = MaterialTheme.typography.bodySmall, modifier = Modifier.desc("format"))
                        }
                    }
                }
                // The form follows the chosen file's format. VeraCrypt: a password and a PIM.
                // LUKS and ZIP are recognised and named, and come in later versions.
                val isLuks = picked?.format == ContainerFormat.LUKS || picked?.format == ContainerFormat.AESCRYPT
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
                    val pimInvalid = stringResource(R.string.pim_invalid)
                    val notSaved = stringResource(R.string.saved_password_no_lock)
                    Button(
                        enabled = picked != null && !busy,
                        modifier = Modifier.desc("unlock"),
                        onClick = {
                            val p = picked ?: return@Button
                            val pimValue = if (pim.isEmpty()) null else pim.toIntOrNull()
                            if (pim.isNotEmpty() && pimValue == null) { message = pimInvalid; return@Button }
                            val chars = password.toCharArray()
                            // Kept for saving: open() clears what it sends.
                            val toSave = if (remember && p.passwordId != null) password.toCharArray() else null
                            busy = true; message = null
                            // Started from the visible screen, before the key derivation (F4).
                            SessionService.start(context)
                            scope.launch {
                                message = withContext(Dispatchers.IO) {
                                    try {
                                        when (val r = engine.open(p.format, p.uri, p.name, chars, pimValue, writable = !readOnly)) {
                                            EngineClient.OpenResult.Ok ->
                                                // Saved only once it has opened, so a typo is never kept.
                                                if (toSave != null && !saved.save(p.passwordId!!, toSave, pimValue)) notSaved else null
                                            EngineClient.OpenResult.WrongPassword -> wrong
                                            EngineClient.OpenResult.AlreadyOpen -> already
                                            is EngineClient.OpenResult.Failed -> failed.replace("%s", r.message)
                                        }
                                    } finally {
                                        toSave?.fill('\u0000')
                                    }
                                }
                                busy = false
                                if (message == null || message == notSaved) { password = ""; pim = ""; picked = null; showOpen = true }
                            }
                        },
                    ) { Text(stringResource(if (busy) R.string.unlocking else R.string.unlock)) }
                }
                if (picked?.format == ContainerFormat.ZIP || picked?.format == ContainerFormat.ISO) {
                    Button(enabled = !busy, modifier = Modifier.desc("open_zip"), onClick = {
                        picked?.let { openWithoutPassword(it) }
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
                        Text(stringResource(if (s.readOnly) R.string.open_details else R.string.open_details_writable,
                            if (s.format == "ZIP" || s.format == "ISO" || s.format == "AES Crypt") s.fileSystem else "${s.format}, ${s.fileSystem}"),
                            style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            OutlinedButton(onClick = {
                                scope.launch(Dispatchers.IO) { engine.lock(s.id) }
                            }, modifier = Modifier.desc("lock_${s.id}")) { Text(stringResource(R.string.lock)) }
                        }
                    }
                }
            }
            if (open.isNotEmpty()) {
                Button(onClick = {
                    scope.launch(Dispatchers.IO) { engine.lockAll() }
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
            var idle by remember(state.idleMinutes) { mutableStateOf(state.idleMinutes) }
            Text(stringResource(R.string.idle_lock), style = MaterialTheme.typography.titleMedium)
            for (m in listOf(1, 5, 15, 30, 60, 0)) {
                val pickIdle = {
                    idle = m
                    scope.launch(Dispatchers.IO) { engine.setIdle(m) }
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.read_only_mode), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = readOnly, onCheckedChange = {
                    readOnly = it; prefs.edit().putBoolean(ContainerMasterApp.READ_ONLY, it).apply()
                }, modifier = Modifier.desc("read_only_mode"))
            }
            Text(stringResource(R.string.read_only_mode_description), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.remember_passwords), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = remember, onCheckedChange = {
                    remember = it; prefs.edit().putBoolean(ContainerMasterApp.REMEMBER_PASSWORDS, it).apply()
                }, modifier = Modifier.desc("remember_passwords"))
            }
            Text(stringResource(R.string.remember_passwords_description), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                saved.forgetAll()
                Toast.makeText(context, R.string.saved_passwords_forgotten, Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.desc("forget_passwords")) { Text(stringResource(R.string.forget_passwords)) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = { context.startActivity(Intent(context, FeedbackActivity::class.java)) },
                modifier = Modifier.desc("settings_report_issue")) {
                Text(stringResource(app.fayaz.otgmaster.common.R.string.feedback_report_issue))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // Support, as in OTG Master's Settings: Ko-fi, and the site's Support section.
            val uriHandler = LocalUriHandler.current
            val donateUrl = stringResource(R.string.donate_url)
            Text(stringResource(R.string.donate_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.donate_summary), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uriHandler.openUri(donateUrl) }, modifier = Modifier.desc("donate_kofi")) {
                Text(stringResource(R.string.donate_kofi))
            }
            val supportUrl = stringResource(R.string.support_page_url)
            TextButton(onClick = { uriHandler.openUri(supportUrl) }, modifier = Modifier.desc("donate_support_link")) {
                Text(stringResource(R.string.support_page))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            val versionText = stringResource(R.string.version_label, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.GIT_COMMIT)
            Text(stringResource(R.string.app_version), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().clickable {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_version), versionText))
            }.padding(vertical = 4.dp).desc("version"), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(versionText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.cd_copy_version))
            }
        }
    }
}

@Composable
private fun LogsSection(logs: List<String>) {
    val context = LocalContext.current
    val engine = remember { EngineClient(context) }
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        val label = stringResource(R.string.logs)
        Text(label, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { context.startActivity(Intent(context, FeedbackActivity::class.java)) },
                modifier = Modifier.desc("report_issue")) {
                Text(stringResource(app.fayaz.otgmaster.common.R.string.feedback_report_issue))
            }
            IconButton(onClick = { scope.launch(Dispatchers.IO) { engine.clearLogs() } }, modifier = Modifier.desc("clear_logs")) {
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

/**
 * The phone's lock in front of a saved password: a fingerprint (class 3) or the screen lock,
 * as OTG Master's Auto mount asks. [onOk] runs on the main thread after it is given; the
 * keystore then releases the password key for [app.fayaz.otgmaster.security.AndroidLockKeys.WINDOW_SECONDS].
 * Cancelling leaves the form for typing the password.
 */
private fun FragmentActivity.askPhoneLock(title: String, subtitle: String, onOk: () -> Unit) {
    val prompt = androidx.biometric.BiometricPrompt(this, androidx.core.content.ContextCompat.getMainExecutor(this),
        object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) = onOk()
        })
    val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        info.setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
    } else {
        info.setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(getString(R.string.saved_prompt_type_instead))
    }
    prompt.authenticate(info.build())
}

package app.fayaz.otgmaster

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.IconButton
import android.content.ClipboardManager
import android.content.ClipData
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import app.fayaz.otgmaster.usb.RealUsbDeviceProvider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.fs.DetectedFilesystem
import app.fayaz.otgmaster.fs.FilesystemDetector
import app.fayaz.otgmaster.usb.LibaumsRawBlockDeviceOpener
import app.fayaz.otgmaster.veracrypt.VeraCryptUnlocker
import app.fayaz.otgmaster.veracrypt.VolumeCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystemCreator
import me.jahnen.libaums.core.fs.FileSystemFactory
import me.jahnen.libaums.core.partition.PartitionTableEntry
import app.fayaz.otgmaster.exfat.ExFatFileSystemCreator
import java.util.UUID

class MainActivity : ComponentActivity() {
    // Provider for USB device handling (real implementation)
    private lateinit var usbDeviceProvider: app.fayaz.otgmaster.usb.UsbDeviceProvider
    private val _candidates = mutableStateOf<List<VolumeCandidate>>(emptyList())
    private var openedBlockDevice: RawBlockDevice? = null

    // For Compose state hoisting
    private val mountedDrivesState = mutableStateOf<List<MountedDrive>>(emptyList())
    private val logsState = mutableStateListOf<String>()

    companion object {
        const val TAG = "OTGMaster"
        private const val REQUEST_USB_PERMISSION = "app.fayaz.otgmaster.USB_PERMISSION"
    }

    private lateinit var sharedPreferences: SharedPreferences
    private val _themeMode = mutableStateOf(ThemeMode.SYSTEM)

    private val versionName: String by lazy {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }
    private val versionCode: Long by lazy {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private val permissionIntent: PendingIntent by lazy {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val intent = Intent(REQUEST_USB_PERMISSION).apply { setPackage(packageName) }
        PendingIntent.getBroadcast(this, 0, intent, flags)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                REQUEST_USB_PERMISSION -> {
                    val device = intent.getParcelableExtraCompat<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null && granted) {
                        appendLog(getString(R.string.log_usb_permission_granted, device.deviceName))
                        openAndProbeUsb()
                    } else {
                        appendLog(getString(R.string.log_usb_permission_denied))
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    appendLog(getString(R.string.log_usb_device_attached))
                    refreshDevices()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    appendLog(getString(R.string.log_usb_device_detached))
                    refreshDevices()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        FileSystemFactory.registerFileSystem(ExFatFileSystemCreator(), 1)
        
        val usbMgr = getSystemService(Context.USB_SERVICE) as UsbManager
        usbDeviceProvider = RealUsbDeviceProvider(usbMgr, permissionIntent)

        registerUsbReceiver()
        
        sharedPreferences = getSharedPreferences("otgmaster_prefs", Context.MODE_PRIVATE)
        val savedTheme = sharedPreferences.getString("theme_mode", ThemeMode.SYSTEM.name)
        _themeMode.value = ThemeMode.valueOf(savedTheme ?: ThemeMode.SYSTEM.name)

        setContent {
            val themeMode = _themeMode.value
            val isDarkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            MaterialTheme(
                colorScheme = if (isDarkTheme) darkColorScheme() else lightColorScheme()
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OtgMasterApp(
                        candidates = _candidates.value,
                        mountedDrives = mountedDrivesState.value,
                        logs = logsState,
                        themeMode = themeMode,
                        versionName = versionName,
                        versionCode = versionCode,
                        onRefreshDevices = { refreshDevices() },
                        onUnlock = { candidate, pwd, pim, keyfiles, cipher, hash, onComplete ->
                            attemptUnlock(candidate, pwd, pim, keyfiles, cipher, hash, onComplete)
                        },
                        onUnmount = { drive -> unmountDrive(drive) },
                        onOpenFilesApp = { openFilesApp() },
                        onClearLogs = { clearLogs() },
                        onCopyText = { text, label -> copyText(text, label) },
                        onThemeChange = { newMode ->
                            _themeMode.value = newMode
                            sharedPreferences.edit().putString("theme_mode", newMode.name).apply()
                        }
                    )
                }
            }
        }
        
        updateMountedDrives()
        refreshDevices()
    }

    override fun onDestroy() {
        openedBlockDevice?.close()
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(REQUEST_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun refreshDevices() {
        val devices = usbDeviceProvider.getDevices()
        appendLog(getString(R.string.log_found_usb_devices, devices.size))

        val usbDevice = devices.firstOrNull()
        if (usbDevice == null) {
            val sda = java.io.File("/dev/block/sda")
            if (sda.exists() && sda.canRead()) {
                openedBlockDevice?.close()
                openedBlockDevice = app.fayaz.otgmaster.block.FileBlockDevice(sda)
                
                Thread {
                    try {
                        val candidates = app.fayaz.otgmaster.veracrypt.VeraCryptUnlocker().probeCandidates(openedBlockDevice!!)
                        runOnUiThread {
                            _candidates.value = candidates
                            appendLog(getString(R.string.log_found_candidates_qemu, candidates.size))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        runOnUiThread { appendLog(getString(R.string.log_error_probing_qemu_candidates, e.message)) }
                    }
                }.start()
                return
            }

            _candidates.value = emptyList()
            openedBlockDevice?.close()
            openedBlockDevice = null
            appendLog(getString(R.string.log_no_usb_devices))
            return
        }

        if (usbDeviceProvider.hasPermission(usbDevice)) {
            openAndProbeUsb()
        } else {
            usbDeviceProvider.requestPermission(usbDevice, permissionIntent)
            appendLog(getString(R.string.log_requested_usb_permission))
        }
    }

    private fun openAndProbeUsb() {
        val devices = usbDeviceProvider.getDevices()
        if (devices.isEmpty()) return
        val device = devices.firstOrNull()

        Thread {
            try {
                val opened = LibaumsRawBlockDeviceOpener(this).openFirstAvailable()
                if (opened == null) {
                    runOnUiThread { appendLog(getString(R.string.log_could_not_open_block_device)) }
                    return@Thread
                }
                openedBlockDevice = opened.blockDevice
                runOnUiThread { appendLog(getString(R.string.log_opened_block_device)) }

                val candidates = VeraCryptUnlocker().probeCandidates(opened.blockDevice)
                runOnUiThread {
                    _candidates.value = candidates
                    appendLog(getString(R.string.log_found_candidates, candidates.size))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { appendLog(getString(R.string.log_error_probing_candidates, e.message)) }
            }
        }.start()
    }

    private fun attemptUnlock(
        candidate: VolumeCandidate,
        password: String,
        pim: Int?,
        keyfiles: List<Uri>,
        cipher: app.fayaz.otgmaster.veracrypt.VeraCryptCipher,
        hash: app.fayaz.otgmaster.veracrypt.VeraCryptHash,
        onComplete: () -> Unit
    ) {
        if (openedBlockDevice == null) {
            appendLog(getString(R.string.log_no_block_device_opened))
            onComplete()
            return
        }

        appendLog(getString(R.string.log_unlock_attempt, pim.toString(), cipher.displayName, hash.displayName))

        if (!cipher.isSupported || !hash.isSupported) {
            val unsupportedName = if (!cipher.isSupported) cipher.displayName else hash.displayName
            appendLog(getString(R.string.log_cannot_mount_unsupported, unsupportedName))
            onComplete()
            return
        }

        Thread {
            try {
                val decryptedDevice = VeraCryptUnlocker().unlock(
                    openedBlockDevice!!, candidate, password.toCharArray(), pim, keyfiles, contentResolver,
                    cipher, hash
                )
                runOnUiThread { appendLog(getString(R.string.log_unlock_successful)) }

                val detected = FilesystemDetector.detect(decryptedDevice)
                runOnUiThread { appendLog(getString(R.string.log_detected_filesystem, detected.displayName)) }

                if (detected is DetectedFilesystem.Unsupported) {
                    runOnUiThread {
                        onComplete()
                        appendLog(getString(R.string.log_cannot_mount_reason, detected.reason))
                    }
                    return@Thread
                }

                if (detected is DetectedFilesystem.Unknown) {
                    runOnUiThread { appendLog(getString(R.string.log_filesystem_unrecognized)) }
                }

                val dummyEntry = PartitionTableEntry(0, 0, 0)
                val byteDevice = me.jahnen.libaums.core.driver.ByteBlockDevice(
                    decryptedDevice as me.jahnen.libaums.core.driver.BlockDeviceDriver
                )
                val fileSystem = try {
                    FileSystemFactory.createFileSystem(dummyEntry, byteDevice)
                } catch (e: Exception) {
                    android.util.Log.e("OTG_MOUNT", "Failed to mount file system", e)
                    val msg = if (detected is DetectedFilesystem.Unknown)
                        getString(R.string.log_unrecognized_fs_not_mounted)
                    else
                        getString(R.string.log_failed_to_mount, detected.displayName, e.message)
                    runOnUiThread {
                        onComplete()
                        appendLog(msg)
                    }
                    return@Thread
                }
                
                app.fayaz.otgmaster.provider.VeraCryptDocumentProvider.mountedFileSystem = fileSystem
                
                val driveId = UUID.randomUUID().toString().substring(0, 8)
                val mountedDrive = MountedDrive(
                    id = driveId,
                    name = getString(R.string.mounted_drive_name, driveId),
                    fileSystem = fileSystem,
                    blockDevice = decryptedDevice
                )

                OtgMasterState.addDrive(mountedDrive)
                contentResolver.notifyChange(
                    android.provider.DocumentsContract.buildRootsUri("app.fayaz.otgmaster.documents"), null
                )
                
                runOnUiThread {
                    onComplete()
                    updateMountedDrives()
                    appendLog(getString(R.string.log_mounted_successfully, fileSystem.capacity / (1024 * 1024)))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    onComplete()
                    appendLog(getString(R.string.log_failed_to_unlock, e.message))
                }
            }
        }.start()
    }

    private fun unmountDrive(drive: MountedDrive) {
        OtgMasterState.removeDrive(drive.id)
        app.fayaz.otgmaster.provider.VeraCryptDocumentProvider.mountedFileSystem = null
        contentResolver.notifyChange(
            android.provider.DocumentsContract.buildRootsUri("app.fayaz.otgmaster.documents"), null
        )
        drive.blockDevice?.close()
        updateMountedDrives()
        appendLog(getString(R.string.log_drive_unmounted, drive.name))
    }

    private fun updateMountedDrives() {
        mountedDrivesState.value = OtgMasterState.mountedDrives.toList()
    }

    private fun openFilesApp() {
        val rootUri = DocumentsContract.buildRootUri("app.fayaz.otgmaster.documents", "veracrypt_root")
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(rootUri, "vnd.android.document/root")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            appendLog(getString(R.string.log_could_not_open_files_app, e.message))
        }
    }



    private fun clearLogs() {
        logsState.clear()
    }

    private fun copyText(text: String, label: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        appendLog(getString(R.string.log_copied_to_clipboard, label))
    }

    private fun appendLog(line: String) {
        if (logsState.size > 50) logsState.removeAt(0)
        logsState.add(line)
    }

    private inline fun <reified T> Intent.getParcelableExtraCompat(name: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }
    }


}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun OtgMasterApp(
    candidates: List<VolumeCandidate>,
    mountedDrives: List<MountedDrive>,
    logs: List<String>,
    themeMode: ThemeMode,
    versionName: String,
    versionCode: Long,
    onRefreshDevices: () -> Unit,
    onUnlock: (VolumeCandidate, String, Int?, List<Uri>, app.fayaz.otgmaster.veracrypt.VeraCryptCipher, app.fayaz.otgmaster.veracrypt.VeraCryptHash, () -> Unit) -> Unit,
    onUnmount: (MountedDrive) -> Unit,
    onOpenFilesApp: () -> Unit,
    onClearLogs: () -> Unit,
    onCopyText: (String, String) -> Unit,
    onThemeChange: (ThemeMode) -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    var isVeraCryptExpanded by remember { mutableStateOf(true) }
    val scrollState = rememberScrollState()
    var previousMountedCount by remember { mutableIntStateOf(mountedDrives.size) }

    LaunchedEffect(mountedDrives.size) {
        if (mountedDrives.size > previousMountedCount) {
            scrollState.animateScrollTo(0)
        }
        previousMountedCount = mountedDrives.size
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_settings))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true }
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        
        Button(onClick = onRefreshDevices, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.scan_usb_devices))
        }

        // Section: Mounted Devices
        if (mountedDrives.isNotEmpty()) {
            Text(stringResource(R.string.mounted_devices), style = MaterialTheme.typography.titleLarge)
            mountedDrives.forEach { drive ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val totalSpace = drive.fileSystem.capacity
                        val freeSpace = drive.fileSystem.freeSpace
                        val usedSpace = totalSpace - freeSpace
                        val progress = if (totalSpace > 0) usedSpace.toFloat() / totalSpace.toFloat() else 0f
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = drive.name, style = MaterialTheme.typography.titleMedium)
                            Text(text = formatSize(totalSpace), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = progress,
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.primaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = stringResource(R.string.used_label, formatSize(usedSpace)), style = MaterialTheme.typography.bodySmall)
                            Text(text = stringResource(R.string.free_label, formatSize(freeSpace)), style = MaterialTheme.typography.bodySmall)
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onOpenFilesApp() }) {
                                Text(stringResource(R.string.open_files_app))
                            }
                            Button(
                                onClick = { onUnmount(drive) },
                                modifier = Modifier.semantics { contentDescription = "unmount_button" }
                            ) {
                                Text(stringResource(R.string.unmount))
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Section: Supported Encryptions - VeraCrypt
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isVeraCryptExpanded = !isVeraCryptExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.mount_veracrypt_drive), style = MaterialTheme.typography.titleMedium)
                    Icon(
                        imageVector = if (isVeraCryptExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.cd_expand)
                    )
                }

                AnimatedVisibility(visible = isVeraCryptExpanded) {
                    VeraCryptMountSection(candidates, onUnlock = onUnlock)
                }
            }
        }

        // Logs
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val logsLabel = stringResource(R.string.logs)
            Text(logsLabel, style = MaterialTheme.typography.titleMedium)
            Row {
                IconButton(onClick = { onClearLogs() }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_clear_logs))
                }
                IconButton(onClick = { onCopyText(logs.joinToString("\n"), logsLabel) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.cd_copy_logs))
                }
            }
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
        ) {
            // Newest entries first so the latest status is visible without scrolling
            // (the page itself, and this card, both have bounded/scrollable height).
            LazyColumn(modifier = Modifier.padding(8.dp)) {
                items(logs.asReversed()) { log ->
                    Text(text = log, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    }

    SettingsDrawer(
        visible = showSettings,
        currentTheme = themeMode,
        versionName = versionName,
        versionCode = versionCode,
        onThemeSelected = onThemeChange,
        onCopyText = onCopyText,
        onDismiss = { showSettings = false }
    )
    }
}

@Composable
fun VeraCryptMountSection(
    candidates: List<VolumeCandidate>,
    onUnlock: (VolumeCandidate, String, Int?, List<Uri>, app.fayaz.otgmaster.veracrypt.VeraCryptCipher, app.fayaz.otgmaster.veracrypt.VeraCryptHash, () -> Unit) -> Unit
) {
    var isUnlocking by remember { mutableStateOf(false) }
    var selectedCandidate by remember(candidates) { mutableStateOf(candidates.firstOrNull()) }
    var expanded by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var pim by remember { mutableStateOf("") }
    var keyfiles by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selectedCipher by remember { mutableStateOf(app.fayaz.otgmaster.veracrypt.VeraCryptCipher.DEFAULT) }
    var cipherExpanded by remember { mutableStateOf(false) }
    var selectedHash by remember { mutableStateOf(app.fayaz.otgmaster.veracrypt.VeraCryptHash.DEFAULT) }
    var hashExpanded by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    val keyfileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        keyfiles = uris
    }

    Column(
        modifier = Modifier.padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (candidates.isEmpty()) {
            Text(stringResource(R.string.no_candidates_found), color = MaterialTheme.colorScheme.error)
        } else {
            @OptIn(ExperimentalMaterial3Api::class)
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = selectedCandidate?.label ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.label_volume_to_unlock)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    candidates.forEach { candidate ->
                        DropdownMenuItem(
                            text = { Text(candidate.label) },
                            onClick = {
                                selectedCandidate = candidate
                                expanded = false
                            }
                        )
                    }
                }
            }
            
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.label_veracrypt_password)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { 
                    focusManager.clearFocus() 
                }),
                visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (passwordVisible) stringResource(R.string.cd_hide_password) else stringResource(R.string.cd_show_password)
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "password_input" }
            )
            
            OutlinedTextField(
                value = pim,
                onValueChange = { pim = it },
                label = { Text(stringResource(R.string.label_pim)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrect = false, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { 
                    focusManager.clearFocus() 
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "pim_input" }
            )
            
            Button(onClick = { keyfileLauncher.launch(arrayOf("*/*")) }) {
                Text(stringResource(R.string.select_keyfiles, keyfiles.size))
            }

            @OptIn(ExperimentalMaterial3Api::class)
            ExposedDropdownMenuBox(
                expanded = cipherExpanded,
                onExpandedChange = { cipherExpanded = !cipherExpanded }
            ) {
                OutlinedTextField(
                    value = selectedCipher.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.label_encryption_algorithm)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cipherExpanded) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier.menuAnchor().fillMaxWidth().semantics { contentDescription = "cipher_picker" }
                )
                ExposedDropdownMenu(
                    expanded = cipherExpanded,
                    onDismissRequest = { cipherExpanded = false }
                ) {
                    app.fayaz.otgmaster.veracrypt.VeraCryptCipher.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(if (option.isSupported) option.displayName else stringResource(R.string.algorithm_not_supported, option.displayName)) },
                            onClick = {
                                selectedCipher = option
                                cipherExpanded = false
                            }
                        )
                    }
                }
            }

            @OptIn(ExperimentalMaterial3Api::class)
            ExposedDropdownMenuBox(
                expanded = hashExpanded,
                onExpandedChange = { hashExpanded = !hashExpanded }
            ) {
                OutlinedTextField(
                    value = selectedHash.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.label_hash_algorithm)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = hashExpanded) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier.menuAnchor().fillMaxWidth().semantics { contentDescription = "hash_picker" }
                )
                ExposedDropdownMenu(
                    expanded = hashExpanded,
                    onDismissRequest = { hashExpanded = false }
                ) {
                    app.fayaz.otgmaster.veracrypt.VeraCryptHash.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(if (option.isSupported) option.displayName else stringResource(R.string.algorithm_not_supported, option.displayName)) },
                            onClick = {
                                selectedHash = option
                                hashExpanded = false
                            }
                        )
                    }
                }
            }

            Button(
                onClick = {
                    focusManager.clearFocus()
                    isUnlocking = true
                    selectedCandidate?.let {
                        onUnlock(it, password, pim.toIntOrNull(), keyfiles, selectedCipher, selectedHash) { isUnlocking = false }
                    }
                },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "mount_button" },
                enabled = selectedCandidate != null && (password.isNotEmpty() || keyfiles.isNotEmpty()) && !isUnlocking
            ) {
                if (isUnlocking) {
                    androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.unlocking_in_progress))
                } else {
                    Text(stringResource(R.string.unlock_and_mount))
                }
            }
        }
    }
}

enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

fun themeStringRes(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

/**
 * A single collapsed-by-default settings entry: a tappable row showing a title and a
 * one-line summary of its current value, expanding in place to reveal its full content.
 * Keeping each setting in its own row like this lets new settings be added later without
 * the drawer growing into an unscannable wall of controls.
 */
@Composable
fun SettingsExpandableRow(
    title: String,
    summary: String,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                content()
            }
        }
        HorizontalDivider()
    }
}

@Composable
fun SettingsDrawer(
    visible: Boolean,
    currentTheme: ThemeMode,
    versionName: String,
    versionCode: Long,
    onThemeSelected: (ThemeMode) -> Unit,
    onCopyText: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val versionText = stringResource(R.string.version_label, versionName, versionCode)
    val versionLabelShort = stringResource(R.string.version_label_short)

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(onClick = onDismiss)
        )

        AnimatedVisibility(
            visible = true,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(300.dp),
                tonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close_settings))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()

                    SettingsExpandableRow(
                        title = stringResource(R.string.theme_label),
                        summary = stringResource(themeStringRes(currentTheme))
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onThemeSelected(mode) }
                                    .padding(vertical = 4.dp)
                            ) {
                                RadioButton(
                                    selected = mode == currentTheme,
                                    onClick = { onThemeSelected(mode) }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(themeStringRes(mode)))
                            }
                        }
                    }

                    SettingsExpandableRow(
                        title = stringResource(R.string.app_version_label),
                        summary = versionText
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onCopyText(versionText, versionLabelShort) }
                                .padding(vertical = 4.dp)
                        ) {
                            Text(versionText, style = MaterialTheme.typography.bodyMedium)
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.cd_copy_version),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

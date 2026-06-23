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
                        appendLog("USB permission granted for ${device.deviceName}.")
                        openAndProbeUsb()
                    } else {
                        appendLog("USB permission denied.")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    appendLog("USB device attached.")
                    refreshDevices()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    appendLog("USB device detached.")
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
                        onRefreshDevices = { refreshDevices() },
                        onUnlock = { candidate, pwd, pim, keyfiles, onComplete -> 
                            attemptUnlock(candidate, pwd, pim, keyfiles, onComplete) 
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
        appendLog("Found ${devices.size} USB devices.")

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
                            appendLog("Found ${candidates.size} VeraCrypt volume candidates on QEMU disk.")
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        runOnUiThread { appendLog("Error probing QEMU candidates: ${e.message}") }
                    }
                }.start()
                return
            }

            _candidates.value = emptyList()
            openedBlockDevice?.close()
            openedBlockDevice = null
            appendLog("No USB devices found.")
            return
        }

        if (usbDeviceProvider.hasPermission(usbDevice)) {
            openAndProbeUsb()
        } else {
            usbDeviceProvider.requestPermission(usbDevice, permissionIntent)
            appendLog("Requested USB permission...")
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
                    runOnUiThread { appendLog("Could not open RawBlockDevice via libaums.") }
                    return@Thread
                }
                openedBlockDevice = opened.blockDevice
                runOnUiThread { appendLog("Successfully opened USB RawBlockDevice.") }

                val candidates = VeraCryptUnlocker().probeCandidates(opened.blockDevice)
                runOnUiThread {
                    _candidates.value = candidates
                    appendLog("Found ${candidates.size} VeraCrypt volume candidates.")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { appendLog("Error probing candidates: ${e.message}") }
            }
        }.start()
    }

    private fun attemptUnlock(
        candidate: VolumeCandidate,
        password: String,
        pim: Int?,
        keyfiles: List<Uri>,
        onComplete: () -> Unit
    ) {
        if (openedBlockDevice == null) {
            appendLog("No block device opened.")
            onComplete()
            return
        }
        
        appendLog("Unlock password='${password}', pim=$pim")

        Thread {
            try {
                val decryptedDevice = VeraCryptUnlocker().unlock(
                    openedBlockDevice!!, candidate, password.toCharArray(), pim, keyfiles, contentResolver
                )
                runOnUiThread { appendLog("Unlock successful! Detecting filesystem...") }

                val detected = FilesystemDetector.detect(decryptedDevice)
                runOnUiThread { appendLog("Detected filesystem: ${detected.displayName}") }

                if (detected is DetectedFilesystem.Unsupported) {
                    runOnUiThread {
                        onComplete()
                        appendLog("Cannot mount: ${detected.reason}")
                    }
                    return@Thread
                }

                if (detected is DetectedFilesystem.Unknown) {
                    runOnUiThread { appendLog("Filesystem unrecognized — attempting mount anyway") }
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
                        "Unrecognized filesystem could not be mounted. Supported: FAT32, exFAT."
                    else
                        "Failed to mount ${detected.displayName}: ${e.message}"
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
                    name = "VeraCrypt Drive ($driveId)",
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
                    appendLog("Mounted successfully. Root capacity: ${fileSystem.capacity / (1024 * 1024)} MB")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { 
                    onComplete()
                    appendLog("Failed to unlock: ${e.message}") 
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
        appendLog("Drive '${drive.name}' unmounted securely.")
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
            appendLog("Could not open files app directly: ${e.message}")
        }
    }



    private fun clearLogs() {
        logsState.clear()
    }

    private fun copyText(text: String, label: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        appendLog("Copied $label to clipboard")
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
    onRefreshDevices: () -> Unit,
    onUnlock: (VolumeCandidate, String, Int?, List<Uri>, () -> Unit) -> Unit,
    onUnmount: (MountedDrive) -> Unit,
    onOpenFilesApp: () -> Unit,
    onClearLogs: () -> Unit,
    onCopyText: (String, String) -> Unit,
    onThemeChange: (ThemeMode) -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    var isVeraCryptExpanded by remember { mutableStateOf(true) }

    if (showSettings) {
        SettingsDialog(
            currentTheme = themeMode,
            onThemeSelected = onThemeChange,
            onDismiss = { showSettings = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OTG Master") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        
        Button(onClick = onRefreshDevices, modifier = Modifier.fillMaxWidth()) {
            Text("Scan USB Devices")
        }

        // Section: Mounted Devices
        if (mountedDrives.isNotEmpty()) {
            Text("Mounted Devices", style = MaterialTheme.typography.titleLarge)
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
                            Text(text = "Used: ${formatSize(usedSpace)}", style = MaterialTheme.typography.bodySmall)
                            Text(text = "Free: ${formatSize(freeSpace)}", style = MaterialTheme.typography.bodySmall)
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onOpenFilesApp() }) {
                                Text("Open Files App")
                            }
                            Button(
                                onClick = { onUnmount(drive) },
                                modifier = Modifier.semantics { contentDescription = "unmount_button" }
                            ) {
                                Text("Unmount")
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
                    Text("Mount New Drive (VeraCrypt)", style = MaterialTheme.typography.titleMedium)
                    Icon(
                        imageVector = if (isVeraCryptExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Expand"
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
            Text("Logs", style = MaterialTheme.typography.titleMedium)
            Row {
                IconButton(onClick = { onClearLogs() }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear Logs")
                }
                IconButton(onClick = { onCopyText(logs.joinToString("\n"), "Logs") }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Logs")
                }
            }
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
        ) {
            LazyColumn(modifier = Modifier.padding(8.dp)) {
                items(logs) { log ->
                    Text(text = log, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    }
}

@Composable
fun VeraCryptMountSection(
    candidates: List<VolumeCandidate>,
    onUnlock: (VolumeCandidate, String, Int?, List<Uri>, () -> Unit) -> Unit
) {
    var isUnlocking by remember { mutableStateOf(false) }
    var selectedCandidate by remember(candidates) { mutableStateOf(candidates.firstOrNull()) }
    var expanded by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var pim by remember { mutableStateOf("") }
    var keyfiles by remember { mutableStateOf<List<Uri>>(emptyList()) }
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
            Text("No VeraCrypt volume candidates found.", color = MaterialTheme.colorScheme.error)
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
                    label = { Text("Volume to Unlock") },
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
                label = { Text("VeraCrypt Password") },
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
                            contentDescription = if (passwordVisible) "Hide password" else "Show password"
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
                label = { Text("PIM (leave blank for default)") },
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
                Text("Select Keyfiles (${keyfiles.size})")
            }
            
            Button(
                onClick = { 
                    isUnlocking = true
                    selectedCandidate?.let { onUnlock(it, password, pim.toIntOrNull(), keyfiles) { isUnlocking = false } } 
                },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "mount_button" },
                enabled = selectedCandidate != null && (password.isNotEmpty() || keyfiles.isNotEmpty()) && !isUnlocking
            ) {
                if (isUnlocking) {
                    androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Unlocking... This may take a while")
                } else {
                    Text("Unlock & Mount")
                }
            }
        }
    }
}

enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

@Composable
fun SettingsDialog(
    currentTheme: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                Text("Theme", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                ThemeMode.entries.forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onThemeSelected(mode) }
                            .padding(8.dp)
                    ) {
                        RadioButton(
                            selected = mode == currentTheme,
                            onClick = { onThemeSelected(mode) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

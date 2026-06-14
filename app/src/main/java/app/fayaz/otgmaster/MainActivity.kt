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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.IconButton
import android.content.ClipboardManager
import android.content.ClipData
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.fayaz.otgmaster.block.RawBlockDevice
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
    private lateinit var usbManager: UsbManager
    private val _candidates = mutableStateOf<List<VolumeCandidate>>(emptyList())
    private var openedBlockDevice: RawBlockDevice? = null

    // For Compose state hoisting
    private val mountedDrivesState = mutableStateOf<List<MountedDrive>>(emptyList())
    private val logsState = mutableStateListOf<String>()

    private lateinit var sharedPreferences: SharedPreferences
    private val _themeMode = mutableStateOf(ThemeMode.SYSTEM)

    private val permissionIntent: PendingIntent by lazy {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) }
        PendingIntent.getBroadcast(this, 0, intent, flags)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
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
        
        usbManager = getSystemService(USB_SERVICE) as UsbManager
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
                        onUnlock = { candidate, pwd, pim, keyfiles -> attemptUnlock(candidate, pwd, pim, keyfiles) },
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
            addAction(ACTION_USB_PERMISSION)
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
        val devices = usbManager.deviceList.values.toList()
        appendLog("Found ${devices.size} USB devices.")

        val massStorageDevice = devices.firstOrNull { device ->
            (0 until device.interfaceCount).any { i ->
                device.getInterface(i).interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_MASS_STORAGE
            }
        }

        if (massStorageDevice == null) {
            _candidates.value = emptyList()
            openedBlockDevice?.close()
            openedBlockDevice = null
            appendLog("No USB Mass Storage devices found.")
            return
        }

        if (!usbManager.hasPermission(massStorageDevice)) {
            appendLog("Requesting permission for ${massStorageDevice.deviceName}...")
            usbManager.requestPermission(massStorageDevice, permissionIntent)
        } else {
            openAndProbeUsb()
        }
    }

    private fun openAndProbeUsb() {
        val devices = usbManager.deviceList.values.toList()
        if (devices.isEmpty()) return
        val device = devices.first()

        Thread {
            try {
                val opened = LibaumsRawBlockDeviceOpener(this).openFirstAvailable()
                openedBlockDevice = opened.blockDevice
                
                val candidates = VeraCryptUnlocker().probeCandidates(opened.blockDevice)
                runOnUiThread {
                    _candidates.value = candidates
                    appendLog("Found ${candidates.size} VeraCrypt volume candidates.")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    appendLog("Error: ${e.message}")
                }
            }
        }.start()
    }

    private fun attemptUnlock(candidate: VolumeCandidate, password: String, pim: Int?, keyfiles: List<Uri>) {
        val device = openedBlockDevice
        if (device == null) {
            appendLog("No device to unlock.")
            return
        }

        appendLog("Attempting unlock of ${candidate.label}...")

        Thread {
            try {
                val decryptedDevice = VeraCryptUnlocker().unlock(
                    device, candidate, password.toCharArray(), pim, keyfiles, contentResolver
                )
                runOnUiThread { appendLog("Unlock successful! Reading File System...") }

                val dummyEntry = PartitionTableEntry(0, 0, 0)
                val byteDevice = me.jahnen.libaums.core.driver.ByteBlockDevice(
                    decryptedDevice as me.jahnen.libaums.core.driver.BlockDeviceDriver
                )
                val fileSystem = try {
                    FileSystemFactory.createFileSystem(dummyEntry, byteDevice)
                } catch (e: Exception) {
                    try {
                        val buffer = java.nio.ByteBuffer.allocate(512)
                        byteDevice.read(0, buffer)
                        buffer.clear()
                        val oemName = ByteArray(8)
                        buffer.position(3)
                        buffer.get(oemName)
                        if (String(oemName).startsWith("NTFS")) {
                            runOnUiThread { appendLog("Unlock successful, but NTFS file system is not supported yet.") }
                            return@Thread
                        }
                    } catch (ignore: Exception) {}
                    
                    runOnUiThread { appendLog("Failed to mount file system: ${e.message}\nCheck if the filesystem is supported.") }
                    return@Thread
                }
                
                app.fayaz.otgmaster.provider.VeraCryptDocumentProvider.mountedFileSystem = fileSystem
                runOnUiThread { appendLog("Mounted successfully! You can now browse files in the Android 'Files' app.") }

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
                    updateMountedDrives()
                    appendLog("Mounted successfully. Root capacity: ${fileSystem.capacity / (1024 * 1024)} MB")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unlock/Read failed", e)
                runOnUiThread { appendLog("Failed: ${e.message}") }
            }
        }.start()
    }

    private fun unmountDrive(drive: MountedDrive) {
        OtgMasterState.removeDrive(drive.id)
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

    companion object {
        const val TAG = "OTGMaster"
        const val ACTION_USB_PERMISSION = "app.fayaz.otgmaster.USB_PERMISSION"
    }
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtgMasterApp(
    candidates: List<VolumeCandidate>,
    mountedDrives: List<MountedDrive>,
    logs: List<String>,
    themeMode: ThemeMode,
    onRefreshDevices: () -> Unit,
    onUnlock: (VolumeCandidate, String, Int?, List<Uri>) -> Unit,
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
                            Button(onClick = { onUnmount(drive) }) {
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
    onUnlock: (VolumeCandidate, String, Int?, List<Uri>) -> Unit
) {
    var selectedCandidate by remember { mutableStateOf(candidates.firstOrNull()) }
    var expanded by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var pim by remember { mutableStateOf("") }
    var keyfiles by remember { mutableStateOf<List<Uri>>(emptyList()) }

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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            
            OutlinedTextField(
                value = pim,
                onValueChange = { pim = it },
                label = { Text("PIM (leave blank for default)") },
                modifier = Modifier.fillMaxWidth()
            )
            
            Button(onClick = { keyfileLauncher.launch(arrayOf("*/*")) }) {
                Text("Select Keyfiles (${keyfiles.size})")
            }
            
            Button(
                onClick = { selectedCandidate?.let { onUnlock(it, password, pim.toIntOrNull(), keyfiles) } },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedCandidate != null && (password.isNotEmpty() || keyfiles.isNotEmpty())
            ) {
                Text("Unlock & Mount")
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

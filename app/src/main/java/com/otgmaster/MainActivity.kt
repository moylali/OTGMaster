package com.otgmaster

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.otgmaster.block.RawBlockDevice
import com.otgmaster.usb.LibaumsRawBlockDeviceOpener
import com.otgmaster.veracrypt.VeraCryptUnlocker
import com.otgmaster.veracrypt.VolumeCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystemCreator
import me.jahnen.libaums.core.partition.PartitionTableEntry
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var usbManager: UsbManager
    private val _candidates = mutableStateOf<List<VolumeCandidate>>(emptyList())
    private var openedBlockDevice: RawBlockDevice? = null

    // For Compose state hoisting
    private val mountedDrivesState = mutableStateOf<List<MountedDrive>>(emptyList())
    private val logsState = mutableStateListOf<String>()

    private val permissionIntent: PendingIntent by lazy {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
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
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        registerUsbReceiver()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OtgMasterApp(
                        candidates = _candidates.value,
                        mountedDrives = mountedDrivesState.value,
                        logs = logsState,
                        onRefreshDevices = { refreshDevices() },
                        onUnlock = { pwd, pim, keyfiles -> attemptUnlock(pwd, pim, keyfiles) },
                        onUnmount = { drive -> unmountDrive(drive) }
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

        if (devices.isEmpty()) {
            _candidates.value = emptyList()
            openedBlockDevice?.close()
            openedBlockDevice = null
            return
        }

        val device = devices.first()
        if (!usbManager.hasPermission(device)) {
            appendLog("Requesting permission for ${device.deviceName}...")
            usbManager.requestPermission(device, permissionIntent)
        } else {
            openAndProbeUsb()
        }
    }

    private fun openAndProbeUsb() {
        val devices = usbManager.deviceList.values.toList()
        if (devices.isEmpty()) return
        val device = devices.first()

        try {
            Thread {
                val opened = LibaumsRawBlockDeviceOpener(this).openFirstAvailable()
                openedBlockDevice = opened.blockDevice
                
                val candidates = VeraCryptUnlocker().probeCandidates(opened.blockDevice)
                runOnUiThread {
                    _candidates.value = candidates
                }
                appendLog("Found ${candidates.size} VeraCrypt volume candidates.")
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open device", e)
            appendLog("Error: ${e.message}")
        }
    }

    private fun attemptUnlock(password: String, pim: Int?, keyfiles: List<Uri>) {
        val device = openedBlockDevice
        val candidate = _candidates.value.firstOrNull()
        if (device == null || candidate == null) {
            appendLog("No device or candidate to unlock.")
            return
        }

        appendLog("Attempting unlock of ${candidate.label}...")

        Thread {
            try {
                val decryptedDevice = VeraCryptUnlocker().unlock(
                    device, candidate, password.toCharArray(), pim, keyfiles, contentResolver
                )
                runOnUiThread { appendLog("Unlock successful! Reading FAT32...") }

                val dummyEntry = PartitionTableEntry(0, 0, 0)
                val byteDevice = me.jahnen.libaums.core.driver.ByteBlockDevice(
                    decryptedDevice as me.jahnen.libaums.core.driver.BlockDeviceDriver
                )
                val fileSystem = Fat32FileSystemCreator().read(dummyEntry, byteDevice)
                
                if (fileSystem == null) {
                    runOnUiThread { appendLog("Failed to mount FAT32 file system") }
                    return@Thread
                }

                val driveId = UUID.randomUUID().toString().substring(0, 8)
                val mountedDrive = MountedDrive(
                    id = driveId,
                    name = "VeraCrypt Drive ($driveId)",
                    fileSystem = fileSystem,
                    blockDevice = decryptedDevice
                )

                OtgMasterState.addDrive(mountedDrive)
                contentResolver.notifyChange(
                    android.provider.DocumentsContract.buildRootsUri("com.otgmaster.documents"), null
                )
                
                runOnUiThread {
                    updateMountedDrives()
                    appendLog("Mounted FAT32 successfully. Root capacity: ${fileSystem.capacity / (1024 * 1024)} MB")
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
            android.provider.DocumentsContract.buildRootsUri("com.otgmaster.documents"), null
        )
        drive.blockDevice?.close()
        updateMountedDrives()
        appendLog("Drive '${drive.name}' unmounted securely.")
    }

    private fun updateMountedDrives() {
        mountedDrivesState.value = OtgMasterState.mountedDrives.toList()
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
        const val ACTION_USB_PERMISSION = "com.otgmaster.USB_PERMISSION"
    }
}

@Composable
fun OtgMasterApp(
    candidates: List<VolumeCandidate>,
    mountedDrives: List<MountedDrive>,
    logs: List<String>,
    onRefreshDevices: () -> Unit,
    onUnlock: (String, Int?, List<Uri>) -> Unit,
    onUnmount: (MountedDrive) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var isVeraCryptExpanded by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "OTG Master", style = MaterialTheme.typography.headlineLarge)
        
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
                        Text(text = drive.name, style = MaterialTheme.typography.titleMedium)
                        Text(text = "Capacity: ${drive.fileSystem.capacity / (1024 * 1024)} MB")
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { onUnmount(drive) }) {
                            Text("Unmount Drive")
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
                    VeraCryptMountSection(candidates, onUnlock)
                }
            }
        }

        // Logs
        Text("Logs", style = MaterialTheme.typography.titleMedium)
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

@Composable
fun VeraCryptMountSection(
    candidates: List<VolumeCandidate>,
    onUnlock: (String, Int?, List<Uri>) -> Unit
) {
    var password by remember { mutableStateOf("") }
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
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("VeraCrypt Password") },
                visualTransformation = PasswordVisualTransformation(),
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
                onClick = { onUnlock(password, pim.toIntOrNull(), keyfiles) },
                modifier = Modifier.fillMaxWidth(),
                enabled = password.isNotEmpty() || keyfiles.isNotEmpty()
            ) {
                Text("Unlock & Mount")
            }
        }
    }
}

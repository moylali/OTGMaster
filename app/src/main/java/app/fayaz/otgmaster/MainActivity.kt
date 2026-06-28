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
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.delay
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
import androidx.compose.material.icons.filled.Fingerprint
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
import app.fayaz.otgmaster.usb.UsbDeviceDescriber
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

/**
 * One attached, not-yet-mounted USB mass-storage device that's been opened and probed for
 * VeraCrypt volume candidates, with a human-readable [displayName] so the user can tell
 * devices apart in a dropdown when more than one is plugged in.
 */
data class UsbDeviceCandidate(
    val deviceName: String,
    val displayName: String,
    val blockDevice: RawBlockDevice,
    val candidates: List<VolumeCandidate>
)

class MainActivity : AppCompatActivity() {
    // Provider for USB device handling (real implementation)
    private lateinit var usbDeviceProvider: app.fayaz.otgmaster.usb.UsbDeviceProvider
    // Keyed by UsbDevice.deviceName. Kept open (not closed) while listed here, since the
    // user may switch the dropdown selection before deciding which one to unlock.
    private val openedDevices = mutableMapOf<String, RawBlockDevice>()
    private val _deviceCandidates = mutableStateOf<List<UsbDeviceCandidate>>(emptyList())
    // Guards against overlapping probes (e.g. from rapid repeated ATTACHED broadcasts on a
    // flaky connection) racing to open/add the same device twice. Touched only on the UI
    // thread, so no synchronization is needed.
    private var isProbingDevices = false

    // For Compose state hoisting
    private val mountedDrivesState = mutableStateOf<List<MountedDrive>>(emptyList())
    private val logsState = mutableStateListOf<String>()
    private val toastState = mutableStateOf<Pair<String, Boolean>?>(null) // message to isSuccess
    private val newlyMountedDriveIds = mutableStateOf<Set<String>>(emptySet())
    private val pendingToastMountNames = mutableListOf<String>()
    private val toastMountHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val toastMountRunnable = Runnable {
        val names = pendingToastMountNames.toList()
        pendingToastMountNames.clear()
        toastState.value = if (names.size == 1) Pair("Mounted: ${names[0]}", true)
                           else Pair("Mounted ${names.size} drives", true)
    }

    companion object {
        const val TAG = "OTGMaster"
        private const val REQUEST_USB_PERMISSION = "app.fayaz.otgmaster.USB_PERMISSION"
        private const val QEMU_DEVICE_KEY = "qemu:/dev/block/sda"
    }

    private lateinit var sharedPreferences: SharedPreferences
    private val _themeMode = mutableStateOf(ThemeMode.SYSTEM)

    private lateinit var credentialStore: app.fayaz.otgmaster.security.CredentialStore
    private val autoMountEnabled = mutableStateOf(false)
    private val mountFormResetKey = mutableStateOf(0)
    private val autoMountAttempted = mutableSetOf<String>()
    private var isAutoMountPromptShowing = false
    private val sessionPlaintextCreds = androidx.compose.runtime.snapshots.SnapshotStateMap<String, app.fayaz.otgmaster.security.CredentialStore.Credentials>()
    private val excludedDeviceKeys = mutableStateOf<Set<String>>(emptySet())

    // Debounce buffer: collects hub devices that arrive in rapid succession so they
    // all go into a single biometric prompt rather than triggering separate ones.
    private val pendingAutoMountDevices = mutableListOf<UsbDeviceCandidate>()
    private val autoMountDebounceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoMountDebounceRunnable = Runnable {
        val batch = pendingAutoMountDevices.toList()
        pendingAutoMountDevices.clear()
        if (batch.isNotEmpty()) showAutoMountPrompt(batch)
    }

    // Guard against concurrent probeQemuDisk calls: the underlying block device can
    // still be flushing after an ExFAT unmount, making a second concurrent probe slow
    // and producing a different FileBlockDevice instance that causes a stale
    // accessibility node in the Compose form (UiAutomator StaleObjectException).
    private var isQemuProbing = false

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
                    val detached = intent.getParcelableExtraCompat<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (detached != null) {
                        val detachedKey = UsbDeviceDescriber.stableKey(detached, usbDeviceProvider.hasPermission(detached))
                        OtgMasterState.mountedDrives
                            .filter { it.sourceDeviceName == detachedKey }
                            .forEach { unmountDrive(it) }
                    }
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
        credentialStore = app.fayaz.otgmaster.security.CredentialStore(this)
        autoMountEnabled.value = sharedPreferences.getBoolean("auto_mount", false)
        excludedDeviceKeys.value = credentialStore.loadExcludedKeys()

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
                        deviceCandidates = _deviceCandidates.value,
                        mountedDrives = mountedDrivesState.value,
                        logs = logsState,
                        themeMode = themeMode,
                        versionName = versionName,
                        versionCode = versionCode,
                        formResetKey = mountFormResetKey.value,
                        autoMountEnabled = autoMountEnabled.value,
                        sessionCredentials = sessionPlaintextCreds,
                        hasCachedCreds = { deviceKey -> credentialStore.has(deviceKey) },
                        isExcluded = { deviceKey -> deviceKey in excludedDeviceKeys.value },
                        onRefreshDevices = { refreshDevices() },
                        onUnlock = { deviceName, candidate, pwd, pim, keyfiles, cipher, hash, onComplete ->
                            attemptUnlock(deviceName, candidate, pwd, pim, keyfiles, cipher, hash, onComplete = onComplete)
                        },
                        onUnmount = { drive -> unmountDrive(drive) },
                        onOpenFilesApp = { drive -> openFilesApp(drive) },
                        onClearLogs = { clearLogs() },
                        onCopyText = { text, label -> copyText(text, label) },
                        onThemeChange = { newMode ->
                            _themeMode.value = newMode
                            sharedPreferences.edit().putString("theme_mode", newMode.name).apply()
                        },
                        onAutoMountEnabledChange = { enabled ->
                            autoMountEnabled.value = enabled
                            sharedPreferences.edit().putBoolean("auto_mount", enabled).apply()
                            if (!enabled) {
                                credentialStore.clearAll()
                                sessionPlaintextCreds.clear()
                                appendLog(getString(R.string.log_auto_mount_credentials_cleared))
                            }
                        },
                        onClearAllCredentials = {
                            credentialStore.clearAll()
                            sessionPlaintextCreds.clear()
                            appendLog(getString(R.string.log_auto_mount_credentials_cleared))
                        },
                        onClearDeviceCreds = { deviceKey ->
                            credentialStore.delete(deviceKey)
                            sessionPlaintextCreds.remove(deviceKey)
                            appendLog(getString(R.string.log_credentials_cleared_for, deviceKey))
                        },
                        onSetExcluded = { deviceKey, excluded ->
                            credentialStore.setExcluded(deviceKey, excluded)
                            excludedDeviceKeys.value = credentialStore.loadExcludedKeys()
                        },
                        onQuickUnlock = { deviceName, onComplete ->
                            showQuickUnlockPrompt(deviceName, onComplete)
                        },
                        toastMessage = toastState.value?.first,
                        toastIsSuccess = toastState.value?.second ?: true,
                        onToastDismiss = { toastState.value = null },
                        newlyMountedDriveIds = newlyMountedDriveIds.value,
                        onHighlightConsumed = { driveId -> newlyMountedDriveIds.value = newlyMountedDriveIds.value - driveId }
                    )
                }
            }
        }
        
        updateMountedDrives()
        refreshDevices()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // With singleTop launch mode, a USB_DEVICE_ATTACHED intent delivered while this
        // activity is already at the top of the stack arrives here instead of creating a
        // new instance. Trigger a device refresh so the newly attached drive is probed.
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            refreshDevices()
        }
    }

    override fun onDestroy() {
        closeOpenedDevices()
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    private fun closeOpenedDevices() {
        openedDevices.values.forEach { it.close() }
        openedDevices.clear()
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

        val mountedDeviceKeys = OtgMasterState.mountedDrives.mapNotNull { it.sourceDeviceName }.toSet()

        // Drop any previously-opened, not-yet-mounted device that's no longer attached.
        val attachedKeys = devices.map { UsbDeviceDescriber.stableKey(it, usbDeviceProvider.hasPermission(it)) }.toSet()
        val staleKeys = openedDevices.keys.filter { it != QEMU_DEVICE_KEY && it !in attachedKeys }
        if (staleKeys.isNotEmpty()) {
            staleKeys.forEach { key -> openedDevices.remove(key)?.close() }
            _deviceCandidates.value = _deviceCandidates.value.filterNot { it.deviceName in staleKeys }
        }

        if (devices.isEmpty()) {
            val qemuAlreadyHandled = QEMU_DEVICE_KEY in mountedDeviceKeys ||
                QEMU_DEVICE_KEY in openedDevices ||
                isQemuProbing
            if (!qemuAlreadyHandled) {
                val sda = java.io.File("/dev/block/sda")
                if (sda.exists() && sda.canRead()) {
                    probeQemuDisk(sda)
                    return
                }
                _deviceCandidates.value = emptyList()
                appendLog(getString(R.string.log_no_usb_devices))
            }
            return
        }

        // Devices not already mounted, not already sitting in the dropdown unprobed, and
        // that actually expose a mass-storage interface (skips USB hubs/keyboards/etc.).
        val candidateDevices = devices.filter {
            val key = UsbDeviceDescriber.stableKey(it, usbDeviceProvider.hasPermission(it))
            key !in mountedDeviceKeys && key !in openedDevices && UsbDeviceDescriber.isMassStorageDevice(it)
        }
        if (candidateDevices.isEmpty()) return

        val deviceNeedingPermission = candidateDevices.firstOrNull { !usbDeviceProvider.hasPermission(it) }
        if (deviceNeedingPermission != null) {
            usbDeviceProvider.requestPermission(deviceNeedingPermission, permissionIntent)
            appendLog(getString(R.string.log_requested_usb_permission))
        } else {
            openAndProbeUsb()
        }
    }

    private fun probeQemuDisk(sda: java.io.File) {
        isQemuProbing = true
        val device = app.fayaz.otgmaster.block.FileBlockDevice(sda)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val candidates = app.fayaz.otgmaster.veracrypt.VeraCryptUnlocker().probeCandidates(device)
                withContext(Dispatchers.Main) {
                    isQemuProbing = false
                    val qemuCandidate = UsbDeviceCandidate(QEMU_DEVICE_KEY, getString(R.string.qemu_test_disk_label), device, candidates)
                    openedDevices[QEMU_DEVICE_KEY] = device
                    _deviceCandidates.value = listOf(qemuCandidate)
                    appendLog(getString(R.string.log_found_candidates_qemu, candidates.size))
                    triggerAutoMount(listOf(qemuCandidate))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                device.close()
                withContext(Dispatchers.Main) {
                    isQemuProbing = false
                    appendLog(getString(R.string.log_error_probing_qemu_candidates, e.message))
                }
            }
        }
    }

    /**
     * Opens and probes every newly-attached device not already mounted or already sitting
     * in [_deviceCandidates], adding results to the dropdown rather than replacing it, so an
     * in-progress unlock attempt on one device isn't disturbed by a second device appearing.
     */
    private fun openAndProbeUsb() {
        // Rapid repeated ATTACHED broadcasts (e.g. a flaky OTG connection re-enumerating)
        // could otherwise start several overlapping probes that all race to open and add
        // the same physical device, producing duplicate dropdown entries — most of which
        // lose the race for the underlying connection and end up broken.
        if (isProbingDevices) return
        isProbingDevices = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val excludeKeys = OtgMasterState.mountedDrives.mapNotNull { it.sourceDeviceName }.toSet() + openedDevices.keys
                val openedList = try {
                    LibaumsRawBlockDeviceOpener(this@MainActivity).openAllAvailable(excludeKeys)
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) { appendLog(getString(R.string.log_error_probing_candidates, e.message)) }
                    return@launch
                }

                if (openedList.isEmpty()) {
                    withContext(Dispatchers.Main) { appendLog(getString(R.string.log_could_not_open_block_device)) }
                    return@launch
                }

                val baseIndex = _deviceCandidates.value.size
                val newCandidates = openedList.mapIndexed { index, opened ->
                    val displayName = UsbDeviceDescriber.friendlyName(opened.usbDevice, baseIndex + index)
                    val candidates = try {
                        VeraCryptUnlocker().probeCandidates(opened.blockDevice)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        emptyList()
                    }
                    UsbDeviceCandidate(opened.deviceKey, displayName, opened.blockDevice, candidates)
                }

                withContext(Dispatchers.Main) {
                    val existingKeys = _deviceCandidates.value.map { it.deviceName }.toSet()
                    val (uniqueNew, duplicates) = newCandidates.partition { it.deviceName !in existingKeys }
                    // Close rather than leak the redundant connections opened for devices
                    // that turned out to already be in the dropdown by the time we got here.
                    duplicates.forEach { it.blockDevice.close() }
                    uniqueNew.forEach { openedDevices[it.deviceName] = it.blockDevice }
                    _deviceCandidates.value = _deviceCandidates.value + uniqueNew
                    if (uniqueNew.isNotEmpty()) {
                        appendLog(getString(R.string.log_probed_devices, uniqueNew.joinToString(", ") { it.displayName }))
                        triggerAutoMount(uniqueNew)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { isProbingDevices = false }
            }
        }
    }

    private fun attemptUnlock(
        deviceName: String,
        candidate: VolumeCandidate,
        password: String,
        pim: Int?,
        keyfiles: List<Uri>,
        cipher: app.fayaz.otgmaster.veracrypt.VeraCryptCipher,
        hash: app.fayaz.otgmaster.veracrypt.VeraCryptHash,
        fromCache: Boolean = false,
        onComplete: () -> Unit
    ) {
        val device = openedDevices[deviceName]
        if (device == null) {
            appendLog(getString(R.string.log_no_block_device_opened))
            onComplete()
            return
        }
        val deviceDisplayName = _deviceCandidates.value.find { it.deviceName == deviceName }?.displayName ?: deviceName

        appendLog(getString(R.string.log_unlock_attempt, deviceDisplayName, pim.toString(), cipher.displayName, hash.displayName))

        if (!cipher.isSupported || !hash.isSupported) {
            val unsupportedName = if (!cipher.isSupported) cipher.displayName else hash.displayName
            appendLog(getString(R.string.log_cannot_mount_unsupported, unsupportedName))
            onComplete()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val decryptedDevice = VeraCryptUnlocker().unlock(
                    device, candidate, password.toCharArray(), pim, keyfiles, contentResolver,
                    cipher, hash
                )
                withContext(Dispatchers.Main) { appendLog(getString(R.string.log_unlock_successful)) }

                val detected = FilesystemDetector.detect(decryptedDevice)
                withContext(Dispatchers.Main) { appendLog(getString(R.string.log_detected_filesystem, detected.displayName)) }

                if (detected is DetectedFilesystem.Unsupported) {
                    withContext(Dispatchers.Main) {
                        onComplete()
                        appendLog(getString(R.string.log_cannot_mount_reason, detected.reason))
                    }
                    return@launch
                }

                if (detected is DetectedFilesystem.Unknown) {
                    withContext(Dispatchers.Main) { appendLog(getString(R.string.log_filesystem_unrecognized)) }
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
                    withContext(Dispatchers.Main) {
                        onComplete()
                        appendLog(msg)
                    }
                    return@launch
                }
                val driveId = UUID.randomUUID().toString().substring(0, 8)
                val mountedDrive = MountedDrive(
                    id = driveId,
                    name = getString(R.string.mounted_drive_name, deviceDisplayName, driveId),
                    fileSystem = fileSystem,
                    blockDevice = decryptedDevice,
                    sourceDeviceName = deviceName,
                    sourceDeviceDisplayName = deviceDisplayName
                )

                OtgMasterState.addDrive(mountedDrive)
                contentResolver.notifyChange(
                    android.provider.DocumentsContract.buildRootsUri("app.fayaz.otgmaster.documents"), null
                )

                withContext(Dispatchers.Main) {
                    onComplete()
                    mountFormResetKey.value++
                    if (autoMountEnabled.value) {
                        credentialStore.save(deviceName, password, pim?.toString() ?: "", keyfiles, cipher.name, hash.name, candidate.startBlock)
                        sessionPlaintextCreds[deviceName] = app.fayaz.otgmaster.security.CredentialStore.Credentials(
                            password, pim?.toString() ?: "", keyfiles, cipher.name, hash.name, candidate.startBlock
                        )
                        appendLog(getString(R.string.log_credentials_saved, deviceDisplayName))
                    }
                    newlyMountedDriveIds.value = newlyMountedDriveIds.value + driveId
                    pendingToastMountNames.add(deviceDisplayName)
                    toastMountHandler.removeCallbacks(toastMountRunnable)
                    toastMountHandler.postDelayed(toastMountRunnable, 300L)
                    updateMountedDrives()
                    appendLog(getString(R.string.log_mounted_successfully, deviceDisplayName, fileSystem.capacity / (1024 * 1024)))
                    // Stop tracking it as "available to unlock" — don't close it, it's now
                    // owned by the mounted filesystem's underlying decrypted device chain.
                    openedDevices.remove(deviceName)
                    _deviceCandidates.value = _deviceCandidates.value.filterNot { it.deviceName == deviceName }
                }
                // Pick up any newly attached device since the last probe (and prompt for
                // permission if needed) now that this one is out of the dropdown.
                withContext(Dispatchers.Main) { refreshDevices() }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onComplete()
                    if (fromCache) {
                        credentialStore.delete(deviceName)
                        sessionPlaintextCreds.remove(deviceName)
                        appendLog(getString(R.string.log_cached_credentials_invalid, deviceDisplayName))
                    }
                    toastState.value = Pair("Mount failed: ${e.message ?: "Unknown error"}", false)
                    appendLog(getString(R.string.log_failed_to_unlock, deviceDisplayName, e.message))
                }
            }
        }
    }

    private fun unmountDrive(drive: MountedDrive) {
        OtgMasterState.removeDrive(drive.id)
        contentResolver.notifyChange(
            android.provider.DocumentsContract.buildRootsUri("app.fayaz.otgmaster.documents"), null
        )
        updateMountedDrives()
        lifecycleScope.launch(Dispatchers.IO) {
            // Wait for any in-flight ProxyFileDescriptor onRelease() callbacks to finish
            // before unmounting — prevents a use-after-free if the OS is still flushing a
            // file write when exfat_unmount frees the ef pointer.
            app.fayaz.otgmaster.provider.VeraCryptDocumentProvider.drainCallbacks()
            if (drive.fileSystem is app.fayaz.otgmaster.exfat.ExFatFileSystem) {
                drive.fileSystem.unmount()
            }
            drive.blockDevice?.close()
            withContext(Dispatchers.Main) {
                toastState.value = Pair("Unmounted: ${drive.name}", true)
                appendLog(getString(R.string.log_drive_unmounted, drive.name))
                // The device's sourceDeviceName is now free (no longer in mountedDrives), so
                // this picks it back up and re-probes it for the dropdown.
                refreshDevices()
            }
        }
    }

    private fun updateMountedDrives() {
        mountedDrivesState.value = OtgMasterState.mountedDrives.toList()
    }

    private fun openFilesApp(drive: MountedDrive) {
        val authority = app.fayaz.otgmaster.provider.VeraCryptDocumentProvider.AUTHORITY
        val rootId = app.fayaz.otgmaster.provider.VeraCryptDocumentProvider.rootIdForDrive(drive.id)
        // BROWSE with a root URI (content://authority/root/rootId) tells DocumentsUI to open
        // directly at that root rather than its default Downloads location. A document URI
        // doesn't pass the isRootUri() check in FilesActivity and falls back to Downloads.
        val rootUri = Uri.parse("content://$authority/root/$rootId")
        val candidates = listOf(
            Intent("android.provider.action.BROWSE").apply {
                data = rootUri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(rootUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        appendLog(getString(R.string.log_could_not_open_files_app, "no handler found"))
    }



    private fun triggerAutoMount(newCandidates: List<UsbDeviceCandidate>) {
        if (!autoMountEnabled.value) return
        val excluded = excludedDeviceKeys.value
        val toMount = newCandidates.filter {
            it.deviceName !in autoMountAttempted &&
            // If credentials are already in session memory the form will pre-fill —
            // no biometric needed (handles the "unmount then reconnect same session" case).
            it.deviceName !in sessionPlaintextCreds &&
            credentialStore.has(it.deviceName) &&
            it.deviceName !in excluded
        }
        if (toMount.isEmpty()) return
        toMount.forEach { autoMountAttempted.add(it.deviceName) }
        // Debounce: accumulate hub devices that arrive in rapid succession, then
        // fire one combined biometric prompt for all of them after a short delay.
        pendingAutoMountDevices.addAll(toMount)
        autoMountDebounceHandler.removeCallbacks(autoMountDebounceRunnable)
        autoMountDebounceHandler.postDelayed(autoMountDebounceRunnable, 400L)
    }

    private fun showAutoMountPrompt(devices: List<UsbDeviceCandidate>) {
        if (isAutoMountPromptShowing) return
        isAutoMountPromptShowing = true
        val executor = androidx.core.content.ContextCompat.getMainExecutor(this)
        val callback = object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                isAutoMountPromptShowing = false
                devices.forEach { device ->
                    val creds = credentialStore.load(device.deviceName) ?: return@forEach
                    val candidate = device.candidates.find { it.startBlock == creds.candidateStartBlock }
                        ?: device.candidates.firstOrNull() ?: return@forEach
                    sessionPlaintextCreds[device.deviceName] = creds
                    val cipher = app.fayaz.otgmaster.veracrypt.VeraCryptCipher.entries
                        .find { it.name == creds.cipherName }
                        ?: app.fayaz.otgmaster.veracrypt.VeraCryptCipher.DEFAULT
                    val hash = app.fayaz.otgmaster.veracrypt.VeraCryptHash.entries
                        .find { it.name == creds.hashName }
                        ?: app.fayaz.otgmaster.veracrypt.VeraCryptHash.DEFAULT
                    attemptUnlock(device.deviceName, candidate, creds.password, creds.pim.toIntOrNull(), creds.keyfileUris, cipher, hash, fromCache = true) {}
                }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                isAutoMountPromptShowing = false
            }
            override fun onAuthenticationFailed() {}
        }
        val prompt = androidx.biometric.BiometricPrompt(this, executor, callback)
        val subtitle = if (devices.size == 1)
            getString(R.string.auto_mount_prompt_subtitle_single, devices[0].displayName)
        else
            getString(R.string.auto_mount_prompt_subtitle_multi, devices.size)
        val builder = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.auto_mount_prompt_title))
            .setSubtitle(subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            builder.setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(getString(R.string.auto_mount_prompt_negative))
        }
        prompt.authenticate(builder.build())
    }

    private fun showQuickUnlockPrompt(deviceName: String, onComplete: () -> Unit) {
        val creds = sessionPlaintextCreds[deviceName] ?: credentialStore.load(deviceName) ?: run {
            appendLog("No credentials found for $deviceName")
            onComplete()
            return
        }
        val device = _deviceCandidates.value.find { it.deviceName == deviceName } ?: run {
            appendLog("Device not found: $deviceName")
            onComplete()
            return
        }
        val candidate = device.candidates.find { it.startBlock == creds.candidateStartBlock }
            ?: device.candidates.firstOrNull() ?: run {
            appendLog("No volume candidate found for $deviceName")
            onComplete()
            return
        }
        val cipher = app.fayaz.otgmaster.veracrypt.VeraCryptCipher.entries
            .find { it.name == creds.cipherName } ?: app.fayaz.otgmaster.veracrypt.VeraCryptCipher.DEFAULT
        val hash = app.fayaz.otgmaster.veracrypt.VeraCryptHash.entries
            .find { it.name == creds.hashName } ?: app.fayaz.otgmaster.veracrypt.VeraCryptHash.DEFAULT

        val executor = androidx.core.content.ContextCompat.getMainExecutor(this)
        val callback = object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                attemptUnlock(deviceName, candidate, creds.password, creds.pim.toIntOrNull(),
                    creds.keyfileUris, cipher, hash, fromCache = true, onComplete = onComplete)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onComplete() }
            override fun onAuthenticationFailed() {}
        }
        val prompt = androidx.biometric.BiometricPrompt(this, executor, callback)
        val builder = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.auto_mount_prompt_title))
            .setSubtitle(device.displayName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            builder.setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(getString(R.string.auto_mount_prompt_negative))
        }
        prompt.authenticate(builder.build())
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
    deviceCandidates: List<UsbDeviceCandidate>,
    mountedDrives: List<MountedDrive>,
    logs: List<String>,
    themeMode: ThemeMode,
    versionName: String,
    versionCode: Long,
    formResetKey: Int,
    autoMountEnabled: Boolean,
    sessionCredentials: Map<String, app.fayaz.otgmaster.security.CredentialStore.Credentials>,
    hasCachedCreds: (String) -> Boolean,
    isExcluded: (String) -> Boolean,
    onRefreshDevices: () -> Unit,
    onUnlock: (String, VolumeCandidate, String, Int?, List<Uri>, app.fayaz.otgmaster.veracrypt.VeraCryptCipher, app.fayaz.otgmaster.veracrypt.VeraCryptHash, () -> Unit) -> Unit,
    onUnmount: (MountedDrive) -> Unit,
    onOpenFilesApp: (MountedDrive) -> Unit,
    onClearLogs: () -> Unit,
    onCopyText: (String, String) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onAutoMountEnabledChange: (Boolean) -> Unit,
    onClearAllCredentials: () -> Unit,
    onClearDeviceCreds: (String) -> Unit = {},
    onSetExcluded: (String, Boolean) -> Unit,
    onQuickUnlock: (String, () -> Unit) -> Unit = { _, cb -> cb() },
    toastMessage: String? = null,
    toastIsSuccess: Boolean = true,
    onToastDismiss: () -> Unit = {},
    newlyMountedDriveIds: Set<String> = emptySet(),
    onHighlightConsumed: (String) -> Unit = {}
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
                key(drive.id) {
                val highlightAlpha = remember { Animatable(0f) }
                val isNewlyMounted = drive.id in newlyMountedDriveIds
                LaunchedEffect(isNewlyMounted) {
                    if (isNewlyMounted) {
                        repeat(3) {
                            highlightAlpha.animateTo(1f, tween(250))
                            highlightAlpha.animateTo(0f, tween(250))
                        }
                        onHighlightConsumed(drive.id)
                    }
                }
                val normalColor = MaterialTheme.colorScheme.secondaryContainer
                val cardColor = lerp(normalColor, Color(0xFF2E7D32), highlightAlpha.value * 0.6f)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val totalSpace = drive.fileSystem.capacity
                        val freeSpace = drive.fileSystem.freeSpace
                        val usedSpace = totalSpace - freeSpace
                        val progress = if (totalSpace > 0) usedSpace.toFloat() / totalSpace.toFloat() else 0f
                        
                        Text(text = drive.name, style = MaterialTheme.typography.titleMedium)
                        Text(text = formatSize(totalSpace), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
                            Button(onClick = { onOpenFilesApp(drive) }) {
                                Text(stringResource(R.string.open_files_app))
                            }
                            Button(
                                onClick = { onUnmount(drive) },
                                modifier = Modifier.semantics { contentDescription = "unmount_button" }
                            ) {
                                Text(stringResource(R.string.unmount))
                            }
                            val deviceKey = drive.sourceDeviceName
                            if (deviceKey != null && hasCachedCreds(deviceKey)) {
                                OutlinedButton(onClick = { onClearDeviceCreds(deviceKey) }) {
                                    Text(stringResource(R.string.clear_cached_credentials))
                                }
                            }
                        }
                    }
                }
                } // key(drive.id)
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
                    key(formResetKey) {
                        VeraCryptMountSection(
                            deviceCandidates = deviceCandidates,
                            onUnlock = onUnlock,
                            autoMountEnabled = autoMountEnabled,
                            sessionCredentials = sessionCredentials,
                            hasCachedCreds = hasCachedCreds,
                            isExcluded = isExcluded,
                            onSetExcluded = onSetExcluded,
                            onQuickUnlock = onQuickUnlock
                        )
                    }
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
        autoMountEnabled = autoMountEnabled,
        onThemeSelected = onThemeChange,
        onAutoMountEnabledChange = onAutoMountEnabledChange,
        onClearAllCredentials = onClearAllCredentials,
        onCopyText = onCopyText,
        onDismiss = { showSettings = false }
    )

    AnimatedVisibility(
        visible = toastMessage != null,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(16.dp)
    ) {
        toastMessage?.let { msg ->
            LaunchedEffect(msg) {
                delay(2500)
                onToastDismiss()
            }
            Surface(
                color = if (toastIsSuccess) Color(0xFF2E7D32) else Color(0xFFC62828),
                shape = RoundedCornerShape(8.dp),
                shadowElevation = 6.dp
            ) {
                Text(
                    text = msg,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
    }
}

@Composable
fun VeraCryptMountSection(
    deviceCandidates: List<UsbDeviceCandidate>,
    onUnlock: (String, VolumeCandidate, String, Int?, List<Uri>, app.fayaz.otgmaster.veracrypt.VeraCryptCipher, app.fayaz.otgmaster.veracrypt.VeraCryptHash, () -> Unit) -> Unit,
    autoMountEnabled: Boolean = false,
    sessionCredentials: Map<String, app.fayaz.otgmaster.security.CredentialStore.Credentials> = emptyMap(),
    hasCachedCreds: (String) -> Boolean = { false },
    isExcluded: (String) -> Boolean = { false },
    onSetExcluded: (String, Boolean) -> Unit = { _, _ -> },
    onQuickUnlock: ((String, () -> Unit) -> Unit)? = null
) {
    var isUnlocking by remember { mutableStateOf(false) }
    var selectedDevice by remember(deviceCandidates) { mutableStateOf(deviceCandidates.firstOrNull()) }
    var deviceExpanded by remember { mutableStateOf(false) }
    val candidates = selectedDevice?.candidates.orEmpty()

    val sessionCreds = sessionCredentials[selectedDevice?.deviceName]
    val isPreFilled = sessionCreds != null

    var selectedCandidate by remember(selectedDevice) { mutableStateOf(
        sessionCreds?.let { sc -> selectedDevice?.candidates?.find { it.startBlock == sc.candidateStartBlock } }
            ?: selectedDevice?.candidates?.firstOrNull()
    ) }
    var expanded by remember { mutableStateOf(false) }

    var password by remember(selectedDevice) { mutableStateOf(sessionCreds?.password ?: "") }
    var passwordVisible by remember { mutableStateOf(false) }
    var pim by remember(selectedDevice) { mutableStateOf(sessionCreds?.pim ?: "") }
    var keyfiles by remember(selectedDevice) { mutableStateOf(sessionCreds?.keyfileUris ?: emptyList<Uri>()) }
    var selectedCipher by remember(selectedDevice) { mutableStateOf(
        sessionCreds?.cipherName?.let { n -> app.fayaz.otgmaster.veracrypt.VeraCryptCipher.entries.find { it.name == n } }
            ?: app.fayaz.otgmaster.veracrypt.VeraCryptCipher.DEFAULT
    ) }
    var cipherExpanded by remember { mutableStateOf(false) }
    var selectedHash by remember(selectedDevice) { mutableStateOf(
        sessionCreds?.hashName?.let { n -> app.fayaz.otgmaster.veracrypt.VeraCryptHash.entries.find { it.name == n } }
            ?: app.fayaz.otgmaster.veracrypt.VeraCryptHash.DEFAULT
    ) }
    var hashExpanded by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val context = androidx.compose.ui.platform.LocalContext.current

    val keyfileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
        }
        keyfiles = uris
    }

    Column(
        modifier = Modifier.padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (deviceCandidates.isEmpty()) {
            Text(stringResource(R.string.no_devices_available), color = MaterialTheme.colorScheme.error)
        } else {
            if (deviceCandidates.size == 1) {
                Text(
                    stringResource(R.string.unlocking_drive_label, selectedDevice?.displayName ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (deviceCandidates.size > 1) {
                @OptIn(ExperimentalMaterial3Api::class)
                ExposedDropdownMenuBox(
                    expanded = deviceExpanded,
                    onExpandedChange = { deviceExpanded = !deviceExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedDevice?.displayName ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.label_usb_drive)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = deviceExpanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier.menuAnchor().fillMaxWidth().semantics { contentDescription = "device_picker" }
                    )
                    ExposedDropdownMenu(
                        expanded = deviceExpanded,
                        onDismissRequest = { deviceExpanded = false }
                    ) {
                        deviceCandidates.forEach { device ->
                            DropdownMenuItem(
                                text = { Text(device.displayName) },
                                onClick = {
                                    selectedDevice = device
                                    deviceExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            val currentDeviceName = selectedDevice?.deviceName ?: ""
            val showQuickUnlock = (isPreFilled || hasCachedCreds(currentDeviceName)) && onQuickUnlock != null

            if (candidates.isEmpty()) {
                Text(stringResource(R.string.no_candidates_found), color = MaterialTheme.colorScheme.error)
            } else if (showQuickUnlock) {
                Button(
                    onClick = {
                        isUnlocking = true
                        val device = selectedDevice
                        if (device != null) {
                            onQuickUnlock!!(device.deviceName) { isUnlocking = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "mount_button" },
                    enabled = selectedDevice != null && !isUnlocking
                ) {
                    if (isUnlocking) {
                        androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.unlocking_in_progress))
                    } else {
                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.unlock_and_mount))
                    }
                }
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
                    modifier = Modifier.menuAnchor().fillMaxWidth().semantics { contentDescription = "candidate_picker" }
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
                onValueChange = { if (!isPreFilled) password = it },
                label = { Text(stringResource(R.string.label_veracrypt_password)) },
                singleLine = true,
                readOnly = isPreFilled,
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
                onValueChange = { if (!isPreFilled) pim = it },
                label = { Text(stringResource(R.string.label_pim)) },
                singleLine = true,
                readOnly = isPreFilled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrect = false, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                    focusManager.clearFocus()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "pim_input" }
            )

            if (!isPreFilled) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { keyfileLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.select_keyfiles, keyfiles.size))
                }
                if (keyfiles.isNotEmpty()) {
                    IconButton(onClick = { keyfiles = emptyList() }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_clear_keyfiles)
                        )
                    }
                }
            }
            } else if (keyfiles.isNotEmpty()) {
                Text(
                    stringResource(R.string.select_keyfiles, keyfiles.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (autoMountEnabled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.auto_mount_exclude_device),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = isExcluded(currentDeviceName),
                        onCheckedChange = { onSetExcluded(currentDeviceName, it) }
                    )
                }
            }

            @OptIn(ExperimentalMaterial3Api::class)
            ExposedDropdownMenuBox(
                expanded = if (isPreFilled) false else cipherExpanded,
                onExpandedChange = { if (!isPreFilled) cipherExpanded = !cipherExpanded }
            ) {
                OutlinedTextField(
                    value = selectedCipher.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.label_encryption_algorithm)) },
                    trailingIcon = { if (!isPreFilled) ExposedDropdownMenuDefaults.TrailingIcon(expanded = cipherExpanded) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier.menuAnchor().fillMaxWidth().semantics { contentDescription = "cipher_picker" }
                )
                if (!isPreFilled) {
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
            }

            @OptIn(ExperimentalMaterial3Api::class)
            ExposedDropdownMenuBox(
                expanded = if (isPreFilled) false else hashExpanded,
                onExpandedChange = { if (!isPreFilled) hashExpanded = !hashExpanded }
            ) {
                OutlinedTextField(
                    value = selectedHash.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.label_hash_algorithm)) },
                    trailingIcon = { if (!isPreFilled) ExposedDropdownMenuDefaults.TrailingIcon(expanded = hashExpanded) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier.menuAnchor().fillMaxWidth().semantics { contentDescription = "hash_picker" }
                )
                if (!isPreFilled) {
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
            }

            Button(
                onClick = {
                    focusManager.clearFocus()
                    isUnlocking = true
                    val device = selectedDevice
                    val candidate = selectedCandidate
                    if (device != null && candidate != null) {
                        onUnlock(device.deviceName, candidate, password, pim.toIntOrNull(), keyfiles, selectedCipher, selectedHash) { isUnlocking = false }
                    }
                },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "mount_button" },
                enabled = selectedDevice != null && selectedCandidate != null &&
                    (password.isNotEmpty() || keyfiles.isNotEmpty()) && !isUnlocking
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
    autoMountEnabled: Boolean,
    onThemeSelected: (ThemeMode) -> Unit,
    onAutoMountEnabledChange: (Boolean) -> Unit,
    onClearAllCredentials: () -> Unit,
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
                        title = stringResource(R.string.auto_mount_title),
                        summary = if (autoMountEnabled) "On" else "Off"
                    ) {
                        Text(
                            stringResource(R.string.auto_mount_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(R.string.auto_mount_title),
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = autoMountEnabled,
                                onCheckedChange = onAutoMountEnabledChange
                            )
                        }
                        if (autoMountEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onClearAllCredentials,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.auto_mount_clear_credentials))
                            }
                        }
                    }

                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                    val donateUrl = stringResource(R.string.donate_url)
                    SettingsExpandableRow(
                        title = stringResource(R.string.donate_title),
                        summary = stringResource(R.string.donate_summary)
                    ) {
                        TextButton(
                            onClick = { uriHandler.openUri(donateUrl) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(donateUrl)
                        }
                        Text(
                            stringResource(R.string.donate_thank_you),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
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

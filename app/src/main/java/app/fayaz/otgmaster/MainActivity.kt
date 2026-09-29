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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystemCreator
import me.jahnen.libaums.core.fs.FileSystemFactory
import me.jahnen.libaums.core.partition.PartitionTableEntry
import app.fayaz.otgmaster.exfat.ExFatFileSystemCreator
import java.util.UUID

/**
 * One attached, not-yet-mounted USB mass-storage device that's been opened and probed for
 * VeraCrypt volume candidates, with a human-readable [displayName] so the user can tell
 * devices apart in a dropdown when more than one is plugged in.
 *
 * [plainPartitions] is non-empty when a recognised plain filesystem (FAT32, exFAT) was
 * detected directly on one of the device's candidates — the device will be auto-mounted
 * without any VeraCrypt credentials and will not appear in the unlock form.
 */
data class UsbDeviceCandidate(
    val deviceName: String,
    val displayName: String,
    val blockDevice: RawBlockDevice,
    val candidates: List<VolumeCandidate>,
    val plainPartitions: List<PlainPartition> = emptyList(),
    // Partitions found on the drive but not offered in the unlock picker, so the
    // form can say the list is filtered instead of looking like the whole drive.
    val hiddenPartitions: Int = 0
)

data class PlainPartition(
    val label: String,
    val startBlock: Long,
    val blockCount: Long,
    val filesystemName: String
)

class MainActivity : AppCompatActivity() {
    // Provider for USB device handling (real implementation)
    private lateinit var usbDeviceProvider: app.fayaz.otgmaster.usb.UsbDeviceProvider
    // Keyed by UsbDevice.deviceName. Kept open (not closed) while listed here, since the
    // user may switch the dropdown selection before deciding which one to unlock.
    private val openedDevices = mutableMapOf<String, RawBlockDevice>()

    /**
     * Serialises volume unlocks across partitions.
     *
     * Every partition of a drive shares one USB mass-storage device with a
     * single command pipe.  Auto-mount fires attemptUnlock() per candidate and
     * each one launches on Dispatchers.IO, so a four-partition drive had four
     * unlocks reading the same device at once: on a Pixel 10 Pro XL the
     * transport collapsed with "Could not read from device, result == -1" and
     * "MAX_RECOVERY_ATTEMPTS Exceeded", two partitions never mounted, and the
     * retry storm ran long enough to starve the next unmount.  Reads are
     * already serialised inside LibaumsRawBlockDevice; this keeps whole unlock
     * sequences from interleaving, which is what the device could not take.
     */
    private val deviceMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * In-flight unmounts per USB device, so the shared connection is closed by
     * the last one rather than the first.
     *
     * unmountDrive() calls OtgMasterState.removeDrive() synchronously and does
     * the device work in a coroutine, so unmounting every partition at once
     * empties mountedDrives before any coroutine runs.  The first to reach the
     * "am I the last partition?" test then saw an empty list, closed the USB
     * connection, and the partitions still flushing metadata behind it failed
     * with "MAX_RECOVERY_ATTEMPTS Exceeded" — which is what stopped a
     * four-partition drive from ever surviving a remount.
     */
    private val pendingUnmounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
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
        const val EXTRA_DRIVE_ID = "app.fayaz.otgmaster.EXTRA_DRIVE_ID"
        private const val SHARE_TARGET_CATEGORY = "app.fayaz.otgmaster.category.SHARE_TARGET"
        private const val PREF_MANUALLY_UNMOUNTED = "manually_unmounted_devices"
        private const val PREF_LAST_BOOT_TIME = "last_boot_time_ms"
    }

    private lateinit var sharedPreferences: SharedPreferences
    private val _themeMode = mutableStateOf(ThemeMode.SYSTEM)

    private lateinit var credentialStore: app.fayaz.otgmaster.security.CredentialStore
    private val autoMountEnabled = mutableStateOf(false)
    private val mountFormResetKey = mutableStateOf(0)
    private val autoMountAttempted = mutableSetOf<String>()
    // Devices the user has explicitly unmounted while still connected. Auto-mount is
    // suppressed for these until the device is physically detached and re-attached.
    private val manuallyUnmountedDevices = mutableSetOf<String>()
    private var isAutoMountPromptShowing = false

    /**
     * Candidates withheld from the unlock form while an auto-mount prompt is pending.
     *
     * The deferral only exists to stop the form flashing behind the biometric prompt,
     * but it made the form's appearance depend on that prompt always resolving. When
     * it did not — a stuck [isAutoMountPromptShowing], or the prompt dismissed by the
     * screen locking without a callback — the candidates were dropped and the form
     * never appeared. Scanning again did not help, because the device was already in
     * [openedDevices] and so got filtered out of the next probe, leaving no way back
     * short of restarting the app. Holding them here means every exit path, plus
     * onResume, can put them back.
     */
    private val deferredCandidates = LinkedHashMap<String, UsbDeviceCandidate>()
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

    // Pending URIs from incoming share intents, held until the picker result returns.
    private var pendingShareUris: List<Uri> = emptyList()

    // Single launcher for all share-to-drive flows (single or multiple files).
    private val openDocumentTreeLauncher = registerForActivityResult(
        object : androidx.activity.result.contract.ActivityResultContract<Uri?, Uri?>() {
            override fun createIntent(context: Context, input: Uri?): Intent =
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    // content://authority/root/rootId passes DocumentsUI's isRootUri() check
                    // and opens the picker directly at that root. /tree/ and /document/ URIs
                    // fail this check and fall back to Downloads.
                    input?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
                }
            override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
                if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
        }
    ) { treeUri ->
        treeUri ?: return@registerForActivityResult
        val sources = pendingShareUris.toList()
        pendingShareUris = emptyList()
        copyFilesToTree(sources, treeUri)
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
                        // At detach time, device.serialNumber often throws (device is gone), so
                        // stableKey falls back to the bus path. Keys stored at mount time likely
                        // used the serial. Match by vendorId:productId prefix as a reliable anchor.
                        val keyWithPerm = UsbDeviceDescriber.stableKey(detached, true)
                        val keyWithoutPerm = UsbDeviceDescriber.stableKey(detached, false)
                        val idPrefix = "${detached.vendorId}:${detached.productId}:"
                        val matchesDetached = { key: String ->
                            key == keyWithPerm || key == keyWithoutPerm || key.startsWith(idPrefix)
                        }
                        // Clear suppression flags so the next plug-in triggers auto-mount again.
                        manuallyUnmountedDevices.removeAll { matchesDetached(it) }
                        autoMountAttempted.removeAll { matchesDetached(it) }
                        sessionPlaintextCreds.keys.filter { matchesDetached(it) }.forEach { sessionPlaintextCreds.remove(it) }
                        saveManuallyUnmountedDevices()
                        // Unmount any mounted drives from this device.
                        OtgMasterState.mountedDrives
                            .filter { it.sourceDeviceName?.let(matchesDetached) == true }
                            .forEach { unmountDrive(it) }
                        // Close and discard any probed-but-not-yet-mounted block handles.
                        val keysToClose = openedDevices.keys.filter { matchesDetached(it) }
                        val toClose = keysToClose.mapNotNull { openedDevices.remove(it) }
                        _deviceCandidates.value = _deviceCandidates.value.filterNot { matchesDetached(it.deviceName) }
                        if (toClose.isNotEmpty()) {
                            lifecycleScope.launch(Dispatchers.IO) { toClose.forEach { runCatching { it.close() } } }
                        }
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
        FileSystemFactory.registerFileSystem(app.fayaz.otgmaster.ext4.Ext4FileSystemCreator(), 2)
        
        val usbMgr = getSystemService(Context.USB_SERVICE) as UsbManager
        usbDeviceProvider = RealUsbDeviceProvider(usbMgr, permissionIntent)

        registerUsbReceiver()

        // Let components without an activity reference write to the log pane.
        OtgMasterState.logSink = { line -> runOnUiThread { appendLog(line) } }

        OtgMasterState.unmountAllRequest = {
            runOnUiThread {
                OtgMasterState.mountedDrives.toList().forEach { unmountDrive(it) }
            }
        }

        OtgMasterState.mountRequest = OtgMasterState.MountRequest { pw, pim, cipherName, hashName ->
            // Probe first if nothing has been scanned yet. Without this the request
            // depended on the UI having already scanned, which does not happen while
            // the screen is off or locked — and needing the screen unlocked defeats
            // the point of being able to mount programmatically.
            lifecycleScope.launch {
                if (_deviceCandidates.value.isEmpty()) {
                    android.util.Log.i("OTGMaster", "Mount request: no candidates, probing")
                    openAndProbeUsb()
                    withTimeoutOrNull(25_000) {
                        while (_deviceCandidates.value.isEmpty()) delay(300)
                    }
                }
                mountProbedCandidates(pw, pim, cipherName, hashName)
            }
        }
        
        sharedPreferences = getSharedPreferences("otgmaster_prefs", Context.MODE_PRIVATE)
        val savedTheme = sharedPreferences.getString("theme_mode", ThemeMode.SYSTEM.name)
        _themeMode.value = ThemeMode.valueOf(savedTheme ?: ThemeMode.SYSTEM.name)
        credentialStore = app.fayaz.otgmaster.security.CredentialStore(this)
        autoMountEnabled.value = sharedPreferences.getBoolean("auto_mount", false)
        excludedDeviceKeys.value = credentialStore.loadExcludedKeys()
        loadManuallyUnmountedDevices(usbMgr)

        setContent {
            val themeMode = _themeMode.value
            val isDarkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            // Hold the display on while a benchmark runs. The bench screen does this
            // for itself, but the mount pre-flight can bring this activity forward, and
            // the flag belongs to whichever window is actually in front.
            val benchRunning = OtgMasterState.benchmarkRunning.value
            androidx.compose.runtime.LaunchedEffect(benchRunning) {
                if (benchRunning) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
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
                        hasCachedCreds = { deviceKey, startBlock ->
                            if (startBlock == null) credentialStore.hasAny(deviceKey)
                            else credentialStore.load(deviceKey, startBlock) != null
                        },
                        isExcluded = { deviceKey -> deviceKey in excludedDeviceKeys.value },
                        onRefreshDevices = { refreshDevices() },
                        onUnlock = { deviceName, candidate, pwd, pim, keyfiles, cipher, hash, onComplete ->
                            attemptUnlock(deviceName, candidate, pwd, pim, keyfiles, cipher, hash, onComplete = onComplete)
                        },
                        onUnmount = { drive -> unmountDrive(drive, isManual = true) },
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
                            credentialStore.deleteAll(deviceKey)
                            sessionPlaintextCreds.remove(deviceKey)
                            appendLog(getString(R.string.log_credentials_cleared_for, deviceKey))
                        },
                        onSetExcluded = { deviceKey, excluded ->
                            credentialStore.setExcluded(deviceKey, excluded)
                            excludedDeviceKeys.value = credentialStore.loadExcludedKeys()
                        },
                        onQuickUnlock = { deviceName, candidate, onComplete ->
                            showQuickUnlockPrompt(deviceName, candidate, onComplete)
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
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            refreshDevices()
        }
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Backstop for a prompt that never delivered a callback — most often because the
        // screen locked while it was up, which dismisses it silently. Once we are resumed
        // there is nothing left to hide the form behind, so clear the flag and put the
        // candidates back. A spurious restore only costs the cosmetic flash the deferral
        // was avoiding; not restoring leaves the form permanently missing.
        isAutoMountPromptShowing = false
        restoreDeferredCandidates()
    }

    override fun onDestroy() {
        OtgMasterState.logSink = null
        OtgMasterState.mountRequest = null
        OtgMasterState.unmountAllRequest = null
        closeOpenedDevices()
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    /**
     * Closes USB connections this activity opened, except any a mounted drive is
     * still using.
     *
     * refreshDevices has carried this guard since 4458c6d — "never touch a device a
     * mounted drive is using" — but onDestroy did not, and closed everything. That
     * kills a live mount whenever the activity is destroyed: a configuration change,
     * or memory pressure while another screen is in front. Observed on a Huawei P20
     * Lite (Android 9), where opening the on-device benchmark backgrounded this
     * activity, Android destroyed it, and the running benchmark then failed every
     * read with "USB block device is closed".
     *
     * Leaving a connection open when the activity dies is the lesser problem: the
     * drive stays usable, MountedDrive still holds it, and the OS reclaims it when the
     * process ends. Closing it takes the volume down under whoever is reading.
     */
    private fun closeOpenedDevices() {
        val inUse = OtgMasterState.mountedDrives.mapNotNull { it.sourceDeviceName }.toSet()
        val releasable = openedDevices.filterKeys { it !in inUse }
        releasable.forEach { (key, device) ->
            device.close()
            openedDevices.remove(key)
        }
        if (openedDevices.isNotEmpty()) {
            Log.i(TAG, "kept ${openedDevices.size} USB connection(s) open for mounted drives")
        }
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
        //
        // Two guards, because getting this wrong closes a live connection:
        //
        // 1. Never touch a device a mounted drive is using. Closing it left the
        //    MountedDrive holding a dead handle, and reads then failed silently —
        //    ExFatFile.read swallows the error — so the volume appeared empty
        //    rather than erroring.
        //
        // 2. Match on vendor:product rather than the full key. stableKey embeds the
        //    serial number only when permission is held, and falls back to the
        //    device path otherwise, so the SAME device yields two different keys
        //    depending on permission state. openedDevices stores the serial form;
        //    recomputing here with permission momentarily unavailable produced the
        //    path form, no match, and the device was closed as "stale".
        val attachedKeys = devices.map { UsbDeviceDescriber.stableKey(it, usbDeviceProvider.hasPermission(it)) }.toSet()
        val attachedIds = devices.map { "${it.vendorId}:${it.productId}" }.toSet()
        fun vendorProductOf(key: String): String = key.split(":").take(2).joinToString(":")
        val staleKeys = openedDevices.keys.filter { key ->
            key != QEMU_DEVICE_KEY &&
                key !in mountedDeviceKeys &&
                key !in attachedKeys &&
                vendorProductOf(key) !in attachedIds
        }
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
                val allCandidates = app.fayaz.otgmaster.veracrypt.VeraCryptUnlocker().probeCandidates(device)
                val plainPartitions = detectPlainPartitions(device, allCandidates)
                // Only show candidates the picker can actually act on. UNENCRYPTED has
                // nothing to unlock — it is either auto-mounted as a plain partition or
                // its filesystem is unsupported — and UNKNOWN means the probe could not
                // identify it at all.
                val candidates = allCandidates.filter {
                    it.containerType != app.fayaz.otgmaster.veracrypt.ContainerType.UNKNOWN &&
                    it.containerType != app.fayaz.otgmaster.veracrypt.ContainerType.UNENCRYPTED
                }
                withContext(Dispatchers.Main) {
                    isQemuProbing = false
                    val qemuCandidate = UsbDeviceCandidate(QEMU_DEVICE_KEY, getString(R.string.qemu_test_disk_label), device, candidates, plainPartitions, allCandidates.size - candidates.size)
                    openedDevices[QEMU_DEVICE_KEY] = device
                    if (plainPartitions.isNotEmpty()) {
                        mountPlainDevice(qemuCandidate, plainPartitions.first())
                    }
                    // Strip plain-partition offsets so the form only shows encrypted candidates
                    // (mirrors the USB mixed-device path). If there are no plain partitions this
                    // is a no-op and all candidates are passed through as before.
                    val plainStarts = plainPartitions.map { it.startBlock }.toSet()
                    val encryptedCandidates = candidates.filter { it.startBlock !in plainStarts }
                    if (encryptedCandidates.isNotEmpty()) {
                        val encryptedQemuCandidate = qemuCandidate.copy(candidates = encryptedCandidates)
                        appendLog(getString(R.string.log_found_candidates_qemu, encryptedCandidates.size))
                        val toAutoMountNames = filterAutoMountCandidates(listOf(encryptedQemuCandidate))
                            .map { it.deviceName }.toSet()
                        if (encryptedQemuCandidate.deviceName !in toAutoMountNames) {
                            _deviceCandidates.value = listOf(encryptedQemuCandidate)
                        }
                        triggerAutoMount(listOf(encryptedQemuCandidate))
                    }
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
                    withContext(Dispatchers.Main) {
                        // Every attached device was filtered out as already open. If any of
                        // them are sitting in deferredCandidates, an earlier auto-mount
                        // prompt never resolved — surface them rather than reporting
                        // failure, so Scan works as a recovery action.
                        if (deferredCandidates.isNotEmpty()) {
                            restoreDeferredCandidates()
                        } else {
                            appendLog(getString(R.string.log_could_not_open_block_device))
                        }
                    }
                    return@launch
                }

                val baseIndex = _deviceCandidates.value.size
                val newCandidates = openedList.mapIndexed { index, opened ->
                    val displayName = UsbDeviceDescriber.friendlyName(opened.usbDevice, baseIndex + index)
                    val allCandidates = try {
                        VeraCryptUnlocker().probeCandidates(opened.blockDevice)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        emptyList()
                    }
                    // Detect plain filesystems on IO thread (readBlocks is blocking).
                    val plainPartitions = detectPlainPartitions(opened.blockDevice, allCandidates)
                    // Only show candidates the picker can actually act on. UNENCRYPTED has
                    // nothing to unlock — it is either auto-mounted as a plain partition or
                    // its filesystem is unsupported — and UNKNOWN means the probe could not
                    // identify it at all.
                    val candidates = allCandidates.filter {
                        it.containerType != app.fayaz.otgmaster.veracrypt.ContainerType.UNKNOWN &&
                        it.containerType != app.fayaz.otgmaster.veracrypt.ContainerType.UNENCRYPTED
                    }
                    UsbDeviceCandidate(opened.deviceKey, displayName, opened.blockDevice, candidates, plainPartitions, allCandidates.size - candidates.size)
                }

                withContext(Dispatchers.Main) {
                    val existingKeys = _deviceCandidates.value.map { it.deviceName }.toSet()
                    val (uniqueNew, duplicates) = newCandidates.partition { it.deviceName !in existingKeys }
                    // Close rather than leak the redundant connections opened for devices
                    // that turned out to already be in the dropdown by the time we got here.
                    duplicates.forEach { it.blockDevice.close() }

                    // Classify each device:
                    //  - purePlain:  every candidate is a recognised plain FS → auto-mount all
                    //  - mixed:      has both plain and encrypted candidates → auto-mount plain
                    //                partitions AND show VeraCrypt form for the encrypted ones
                    //  - pureEncrypted: no plain candidates → VeraCrypt form only
                    val (hasPlain, pureEncrypted) = uniqueNew.partition { it.plainPartitions.isNotEmpty() }
                    val (purePlain, mixed) = hasPlain.partition { device ->
                        val plainStarts = device.plainPartitions.map { it.startBlock }.toSet()
                        device.candidates.all { it.startBlock in plainStarts }
                    }

                    // Auto-mount ALL plain partitions on pure-plain and mixed devices
                    (purePlain + mixed).forEach { candidate ->
                        openedDevices[candidate.deviceName] = candidate.blockDevice
                        candidate.plainPartitions.forEach { plain ->
                            mountPlainDevice(candidate, plain)
                        }
                    }

                    // For mixed devices, strip out the already-mounted plain candidates so
                    // the VeraCrypt form only shows the encrypted partitions still to unlock.
                    val mixedEncrypted = mixed.map { device ->
                        val plainStarts = device.plainPartitions.map { it.startBlock }.toSet()
                        val kept = device.candidates.filter { it.startBlock !in plainStarts }
                        device.copy(
                            candidates = kept,
                            hiddenPartitions = device.hiddenPartitions + (device.candidates.size - kept.size),
                        )
                    }

                    val encryptedNew = pureEncrypted + mixedEncrypted
                    encryptedNew.forEach { openedDevices[it.deviceName] = it.blockDevice }
                    // Defer auto-mount candidates from _deviceCandidates so the form doesn't
                    // flash while waiting for the biometric prompt. They'll be added back in
                    // showAutoMountPrompt once biometric resolves (success or cancel).
                    val toAutoMountNames = filterAutoMountCandidates(encryptedNew).map { it.deviceName }.toSet()
                    val manualOnly = encryptedNew.filter { it.deviceName !in toAutoMountNames }
                    deferCandidates(encryptedNew.filter { it.deviceName in toAutoMountNames })
                    _deviceCandidates.value = _deviceCandidates.value + manualOnly
                    if (encryptedNew.isNotEmpty()) {
                        appendLog(getString(R.string.log_probed_devices, encryptedNew.joinToString(", ") { it.displayName }))
                        triggerAutoMount(encryptedNew)
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
            deviceMutex.withLock {
            try {
                // An UNENCRYPTED candidate has a readable filesystem at its first
                // sector, so there is no header to derive a key from. Falling
                // through to the VeraCrypt branch ran 16,000 PBKDF2 iterations
                // against an NTFS boot sector and then reported "failed to
                // unlock", which reads as a wrong password rather than the truth.
                if (candidate.containerType ==
                        app.fayaz.otgmaster.veracrypt.ContainerType.UNENCRYPTED) {
                    val available = device.blockCount - candidate.startBlock
                    val plain = if (available <= 0) DetectedFilesystem.Unknown else
                        FilesystemDetector.detectFromBytes(
                            device.readBlocks(candidate.startBlock, minOf(4L, available).toInt()))
                    withContext(Dispatchers.Main) {
                        onComplete()
                        appendLog(getString(R.string.log_volume_not_encrypted,
                            candidate.label, plain.displayName))
                    }
                    return@withLock
                }
                val decryptedDevice = when (candidate.containerType) {
                    app.fayaz.otgmaster.veracrypt.ContainerType.LUKS1,
                    app.fayaz.otgmaster.veracrypt.ContainerType.LUKS2 -> {
                        val passwordBytes = password.toByteArray(Charsets.UTF_8)
                        try {
                            app.fayaz.otgmaster.luks.LuksUnlocker().unlock(
                                device, candidate.startBlock, candidate.blockCount, passwordBytes
                            )
                        } finally {
                            passwordBytes.fill(0)
                        }
                    }
                    else -> VeraCryptUnlocker().unlock(
                        device, candidate, password.toCharArray(), pim, keyfiles, contentResolver,
                        cipher, hash
                    )
                }
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
                // Read cache with readahead between the filesystem driver and the
                // decrypted device. libexfat reads directory and FAT entries
                // unbuffered — measured at 100,844 preads averaging 24 bytes to list
                // a 10,000-entry directory (docs/IO_PERFORMANCE.md §5.5) — and each
                // one was a USB round trip plus a sector decryption. Caching
                // plaintext means a hit skips both.
                // Readahead helps or hurts depending on the filesystem's metadata
                // layout — see CachedBlockDevice.NO_READAHEAD_BYTES.
                val cachedDevice = wrapWithCache(decryptedDevice, detected.displayName)
                val byteDevice = me.jahnen.libaums.core.driver.ByteBlockDevice(
                    cachedDevice as me.jahnen.libaums.core.driver.BlockDeviceDriver
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
                    blockDevice = cachedDevice,
                    sourceDeviceName = deviceName,
                    sourceDeviceDisplayName = deviceDisplayName,
                    rawBlockDevice = device,
                    sourceVolumeCandidate = candidate,
                    partitionLabel = candidate.label,
                    filesystemName = detected.displayName
                )

                OtgMasterState.addDrive(mountedDrive)
                contentResolver.notifyChange(
                    android.provider.DocumentsContract.buildRootsUri("app.fayaz.otgmaster.documents"), null
                )

                withContext(Dispatchers.Main) {
                    onComplete()
                    mountFormResetKey.value++
                    if (autoMountEnabled.value) {
                        credentialStore.save(deviceName, candidate.startBlock, password, pim?.toString() ?: "", keyfiles, cipher.name, hash.name, candidate.containerType)
                        sessionPlaintextCreds[deviceName] = app.fayaz.otgmaster.security.CredentialStore.Credentials(
                            password, pim?.toString() ?: "", keyfiles, cipher.name, hash.name, candidate.startBlock, candidate.containerType
                        )
                        appendLog(getString(R.string.log_credentials_saved, deviceDisplayName))
                    }
                    newlyMountedDriveIds.value = newlyMountedDriveIds.value + driveId
                    pendingToastMountNames.add(deviceDisplayName)
                    toastMountHandler.removeCallbacks(toastMountRunnable)
                    toastMountHandler.postDelayed(toastMountRunnable, 300L)
                    updateMountedDrives()
                    pushDriveShortcut(mountedDrive)
                    appendLog(getString(R.string.log_mounted_successfully, deviceDisplayName, fileSystem.capacity / (1024 * 1024)))
                    // Remove only this candidate; keep others for the same device (multi-partition).
                    val updatedCandidates = _deviceCandidates.value.map { udc ->
                        if (udc.deviceName == deviceName)
                            udc.copy(candidates = udc.candidates.filter { it.startBlock != candidate.startBlock })
                        else udc
                    }.filter { it.candidates.isNotEmpty() }
                    _deviceCandidates.value = updatedCandidates
                    // Clear in-session pre-fill so the form can use the stored creds for the
                    // next remaining candidate (hasCachedCreds keeps the quick-unlock button visible).
                    if (updatedCandidates.any { it.deviceName == deviceName }) {
                        sessionPlaintextCreds.remove(deviceName)
                    }
                    if (updatedCandidates.none { it.deviceName == deviceName }) {
                        openedDevices.remove(deviceName)
                        // All partitions mounted — no more candidates remain, so session creds
                        // are no longer needed and would block auto-mount on next re-plug.
                        sessionPlaintextCreds.remove(deviceName)
                    }
                }
                // Pick up any newly attached device since the last probe (and prompt for
                // permission if needed) now that this one is out of the dropdown.
                withContext(Dispatchers.Main) { refreshDevices() }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onComplete()
                    // An I/O error says nothing about whether the password is
                    // right — the USB transport drops under load and the read
                    // fails long before any key is tested — so it must not throw
                    // away the user's saved credentials.  Discarding them on any
                    // exception meant a transport blip silently un-saved a
                    // partition the user had asked to be remembered.
                    if (fromCache && e !is java.io.IOException) {
                        credentialStore.deletePartition(deviceName, candidate.startBlock)
                        sessionPlaintextCreds.remove(deviceName)
                        appendLog(getString(R.string.log_cached_credentials_invalid, deviceDisplayName))
                    }
                    toastState.value = Pair("Mount failed: ${e.message ?: "Unknown error"}", false)
                    appendLog(getString(R.string.log_failed_to_unlock, deviceDisplayName, e.message))
                }
            }
            }
        }
    }

    private fun loadManuallyUnmountedDevices(usbMgr: UsbManager) {
        // Detect reboot: boot epoch = wall clock minus uptime. If it differs from the saved
        // value by more than 60 s, the device was rebooted — clear the suppression set so
        // auto-mount fires again on first open after boot.
        val bootEpoch = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
        val savedBootEpoch = sharedPreferences.getLong(PREF_LAST_BOOT_TIME, 0L)
        val rebooted = Math.abs(bootEpoch - savedBootEpoch) > 60_000L
        if (rebooted) {
            sharedPreferences.edit()
                .putStringSet(PREF_MANUALLY_UNMOUNTED, emptySet())
                .putLong(PREF_LAST_BOOT_TIME, bootEpoch)
                .apply()
            return
        }

        val persisted = sharedPreferences.getStringSet(PREF_MANUALLY_UNMOUNTED, emptySet()) ?: emptySet()
        // Drop keys whose device is no longer physically attached — it was unplugged while
        // the app was dead, so the next plug-in should be treated as a fresh connection.
        val currentKeys = usbMgr.deviceList.values.map {
            UsbDeviceDescriber.stableKey(it, usbMgr.hasPermission(it))
        }.toSet()
        val stillConnected = persisted.filter { it in currentKeys }.toSet()
        manuallyUnmountedDevices.clear()
        manuallyUnmountedDevices.addAll(stillConnected)
        if (stillConnected != persisted) saveManuallyUnmountedDevices()
    }

    private fun saveManuallyUnmountedDevices() {
        sharedPreferences.edit().putStringSet(PREF_MANUALLY_UNMOUNTED, manuallyUnmountedDevices.toSet()).apply()
    }

    private fun unmountDrive(drive: MountedDrive, isManual: Boolean = false) {
        if (isManual) {
            drive.sourceDeviceName?.let {
                manuallyUnmountedDevices.add(it)
                saveManuallyUnmountedDevices()
            }
        }
        drive.sourceDeviceName?.let { key ->
            pendingUnmounts.merge(key, 1) { a, b -> a + b }
        }
        OtgMasterState.removeDrive(drive.id)
        contentResolver.notifyChange(
            android.provider.DocumentsContract.buildRootsUri("app.fayaz.otgmaster.documents"), null
        )
        updateMountedDrives()
        removeDriveShortcut(drive.id)
        lifecycleScope.launch(Dispatchers.IO) {
            // Wait for any in-flight ProxyFileDescriptor onRelease() callbacks to finish
            // before unmounting — prevents a use-after-free if the OS is still flushing a
            // file write when exfat_unmount frees the ef pointer.
            app.fayaz.otgmaster.provider.VeraCryptDocumentProvider.drainCallbacks()
            // Unmounting flushes filesystem metadata, so it writes to the same shared
            // USB device as every other partition.  Remounting empties the whole mount
            // list at once, and four concurrent single-block writes killed the
            // transport with "MAX_RECOVERY_ATTEMPTS Exceeded", which then failed the
            // unlocks that followed.  Only the device work is serialised —
            // drainCallbacks() above waits on in-flight file callbacks that can need
            // the device themselves, so holding the lock across it deadlocks every
            // later mount and unmount.
            deviceMutex.withLock {
                if (drive.fileSystem is app.fayaz.otgmaster.exfat.ExFatFileSystem) {
                    drive.fileSystem.unmount()
                }
                // drive.blockDevice is either NativeDecryptedBlockDevice (close zeros the key but
                // does NOT close the underlying USB connection) or RawBlockDeviceAdapter (noop close).
                // The raw USB connection is managed below — closed only when no other partitions remain.
                drive.blockDevice?.close()
            }
            withContext(Dispatchers.Main) {
                toastState.value = Pair("Unmounted: ${drive.name}", true)
                appendLog(getString(R.string.log_drive_unmounted, drive.name))

                val sourceKey = drive.sourceDeviceName
                val rawDevice = drive.rawBlockDevice
                val volCandidate = drive.sourceVolumeCandidate

                if (sourceKey != null && rawDevice != null) {
                    val stillUnmounting = (pendingUnmounts.merge(sourceKey, -1) { a, b -> a + b } ?: 0) > 0
                    val otherMounted = OtgMasterState.mountedDrives.any { it.sourceDeviceName == sourceKey }
                    val candidatesInForm = _deviceCandidates.value.any { it.deviceName == sourceKey }

                    if (!stillUnmounting && !otherMounted && !candidatesInForm) {
                        // Last partition from this USB — safe to release the USB connection.
                        // Only close if we actually removed it; detach handler may have beaten us.
                        val removed = openedDevices.remove(sourceKey)
                        if (removed != null) rawDevice.close()
                    } else if (!drive.isPlain && volCandidate != null) {
                        // Other partitions still alive; restore this candidate so the user can remount.
                        openedDevices[sourceKey] = rawDevice
                        val existing = _deviceCandidates.value.find { it.deviceName == sourceKey }
                        if (existing != null) {
                            _deviceCandidates.value = _deviceCandidates.value.map {
                                if (it.deviceName == sourceKey)
                                    it.copy(candidates = (it.candidates + volCandidate).sortedBy { c -> c.startBlock })
                                else it
                            }
                        } else {
                            _deviceCandidates.value = _deviceCandidates.value + UsbDeviceCandidate(
                                deviceName = sourceKey,
                                displayName = drive.sourceDeviceDisplayName ?: sourceKey,
                                blockDevice = rawDevice,
                                candidates = listOf(volCandidate),
                                plainPartitions = emptyList()
                            )
                        }
                    }
                }

                refreshDevices()
            }
        }
    }

    private fun detectPlainPartitions(device: RawBlockDevice, candidates: List<VolumeCandidate>): List<PlainPartition> {
        return candidates.mapNotNull { candidate ->
            val startBlock = candidate.startBlock
            val available = device.blockCount - startBlock
            if (available <= 0) return@mapNotNull null
            val data = try {
                device.readBlocks(startBlock, minOf(4, available).toInt())
            } catch (_: Exception) { return@mapNotNull null }
            val fs = FilesystemDetector.detectFromBytes(data)
            if (fs is DetectedFilesystem.Supported) {
                PlainPartition(
                    label = candidate.label,
                    startBlock = startBlock,
                    blockCount = candidate.blockCount ?: available,
                    filesystemName = fs.displayName
                )
            } else null
        }
    }

    private fun mountPlainDevice(candidate: UsbDeviceCandidate, plain: PlainPartition) {
        val rawDevice = candidate.blockDevice
        val sliced: RawBlockDevice = if (plain.startBlock == 0L) rawDevice
            else app.fayaz.otgmaster.block.SlicedBlockDevice(rawDevice, plain.startBlock, plain.blockCount)
        val adapter = app.fayaz.otgmaster.block.RawBlockDeviceAdapter(sliced)
        val deviceDisplayName = candidate.displayName
        appendLog("Mounting ${plain.filesystemName} on $deviceDisplayName…")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dummyEntry = PartitionTableEntry(0, 0, 0)
                // Same cache for plain volumes: libaums rebuilds directory state that
                // libexfat keeps parsed, so FAT32 re-reads blocks instead (its warm
                // listing is 556 ms against exFAT's 30 ms).
                val cachedDevice = wrapWithCache(adapter, plain.filesystemName)
                val byteDevice = me.jahnen.libaums.core.driver.ByteBlockDevice(
                    cachedDevice as me.jahnen.libaums.core.driver.BlockDeviceDriver
                )
                val fileSystem = FileSystemFactory.createFileSystem(dummyEntry, byteDevice)
                val driveId = UUID.randomUUID().toString().substring(0, 8)
                val mountedDrive = MountedDrive(
                    id = driveId,
                    name = getString(R.string.mounted_drive_name_plain, deviceDisplayName, plain.filesystemName, driveId),
                    fileSystem = fileSystem,
                    blockDevice = cachedDevice,
                    sourceDeviceName = candidate.deviceName,
                    sourceDeviceDisplayName = deviceDisplayName,
                    isPlain = true,
                    rawBlockDevice = rawDevice,
                    partitionLabel = plain.label,
                    filesystemName = plain.filesystemName
                )
                OtgMasterState.addDrive(mountedDrive)
                contentResolver.notifyChange(
                    android.provider.DocumentsContract.buildRootsUri("app.fayaz.otgmaster.documents"), null
                )
                withContext(Dispatchers.Main) {
                    newlyMountedDriveIds.value = newlyMountedDriveIds.value + driveId
                    pendingToastMountNames.add(deviceDisplayName)
                    toastMountHandler.removeCallbacks(toastMountRunnable)
                    toastMountHandler.postDelayed(toastMountRunnable, 300L)
                    updateMountedDrives()
                    pushDriveShortcut(mountedDrive)
                    appendLog(getString(R.string.log_mounted_successfully, deviceDisplayName, fileSystem.capacity / (1024 * 1024)))
                    // Don't remove from openedDevices here — for mixed devices (plain + encrypted
                    // partitions), the encrypted partitions still need the USB connection.
                    // Cleanup is handled by attemptUnlock when the last encrypted partition mounts,
                    // or by the detach handler when the device is unplugged.
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    toastState.value = Pair("Mount failed: ${e.message ?: "Unknown error"}", false)
                    appendLog("Failed to mount ${candidate.displayName}: ${e.message}")
                }
            }
        }
    }

    /**
     * Cache line size for a filesystem.
     *
     * exFAT re-reads a small metadata region thousands of times, so a 64 KiB line
     * is nearly free and pays back 90x on a cold directory listing. FAT32 at small
     * cluster sizes walks a large FAT sparsely, where the same readahead was pure
     * amplification and made cold listings 2-4x slower. Measured in
     * docs/IO_PERFORMANCE.md.
     */
    /**
     * Wraps [device] in the block cache unless configuration disables it.
     *
     * Returning the bare device when disabled lets the cache be A/B'd in one
     * session; [device] already implements BlockDeviceDriver in both mount paths.
     */
    private fun wrapWithCache(
        device: app.fayaz.otgmaster.block.RawBlockDevice,
        filesystemName: String,
    ): app.fayaz.otgmaster.block.RawBlockDevice {
        val cfg = OtgMasterState.cacheConfig
        if (cfg?.enabled == false) {
            appendLog("Block cache: DISABLED for this mount")
            android.util.Log.i("OTGMaster", "block cache disabled for this mount")
            return device
        }
        val readAhead = cfg?.readAheadBytes ?: readAheadFor(filesystemName)
        android.util.Log.i("OTGMaster", "block cache enabled, readahead $readAhead bytes")
        return app.fayaz.otgmaster.block.CachedBlockDevice(device, readAheadBytes = readAhead)
    }

    private fun readAheadFor(filesystemName: String): Int = when {
        filesystemName.contains("exFAT", ignoreCase = true) ->
            app.fayaz.otgmaster.block.CachedBlockDevice.DEFAULT_READAHEAD_BYTES
        filesystemName.startsWith("ext", ignoreCase = true) ->
            app.fayaz.otgmaster.block.CachedBlockDevice.DEFAULT_READAHEAD_BYTES
        else ->
            app.fayaz.otgmaster.block.CachedBlockDevice.FAT_READAHEAD_BYTES
    }

    /**
     * Unlocks and mounts every probed candidate with one set of credentials.
     *
     * Backs [OtgMasterState.MountRequest]. Goes through the same attemptUnlock the
     * form uses, so it exercises the real path rather than a parallel one.
     */
    private fun mountProbedCandidates(
        password: String,
        pim: Int?,
        cipherName: String,
        hashName: String,
    ) {
        val cipher = app.fayaz.otgmaster.veracrypt.VeraCryptCipher.entries
            .find { it.name == cipherName || it.displayName == cipherName }
            ?: app.fayaz.otgmaster.veracrypt.VeraCryptCipher.DEFAULT
        val hash = app.fayaz.otgmaster.veracrypt.VeraCryptHash.entries
            .find { it.name == hashName || it.displayName == hashName }
            ?: app.fayaz.otgmaster.veracrypt.VeraCryptHash.DEFAULT

        val devices = _deviceCandidates.value
        if (devices.isEmpty()) {
            val msg = "Mount request: no probed candidates — is a drive attached and permitted?"
            appendLog(msg)
            android.util.Log.w("OTGMaster", msg)
            return
        }
        val total = devices.sumOf { it.candidates.size }
        val msg = "Mount request: $total candidate(s), ${cipher.displayName}/${hash.displayName}, PIM ${pim ?: "default"}"
        appendLog(msg)
        android.util.Log.i("OTGMaster", msg)
        devices.forEach { device ->
            device.candidates.forEach { candidate ->
                attemptUnlock(
                    device.deviceName, candidate, password, pim,
                    emptyList(), cipher, hash,
                ) {}
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



    /** Returns which of [candidates] would actually trigger auto-mount right now. */
    private fun deferCandidates(devices: List<UsbDeviceCandidate>) {
        devices.forEach { deferredCandidates[it.deviceName] = it }
    }

    /** Puts back anything withheld for an auto-mount prompt. Safe to call repeatedly. */
    private fun restoreDeferredCandidates() {
        if (deferredCandidates.isEmpty()) return
        val shown = _deviceCandidates.value.map { it.deviceName }.toSet()
        // Already-mounted *partitions*, not devices. deviceName identifies the
        // physical drive, so testing it against mountedDrives threw away every
        // still-locked partition on a drive that had any partition mounted. On
        // drive D — plain ext4 on p2, VeraCrypt on p1 — auto-mounting p2 made p1
        // unreachable: cancelling the biometric prompt left the form saying "No
        // USB drives available to mount" with the drive plugged in.
        val mountedPartitions = OtgMasterState.mountedDrives.mapNotNull { drive ->
            val start = drive.sourceVolumeCandidate?.startBlock ?: return@mapNotNull null
            drive.sourceDeviceName?.let { it to start }
        }.toSet()
        val toRestore = deferredCandidates.values.mapNotNull { device ->
            if (device.deviceName in shown) return@mapNotNull null
            val remaining = device.candidates.filter {
                (device.deviceName to it.startBlock) !in mountedPartitions
            }
            if (remaining.isEmpty()) null else device.copy(candidates = remaining)
        }
        deferredCandidates.clear()
        if (toRestore.isNotEmpty()) {
            _deviceCandidates.value = _deviceCandidates.value + toRestore
        }
    }

    private fun filterAutoMountCandidates(candidates: List<UsbDeviceCandidate>): List<UsbDeviceCandidate> {
        if (!autoMountEnabled.value) return emptyList()
        val excluded = excludedDeviceKeys.value
        return candidates.filter {
            it.deviceName !in autoMountAttempted &&
            it.deviceName !in manuallyUnmountedDevices &&
            it.deviceName !in sessionPlaintextCreds &&
            it.candidates.any { c -> credentialStore.load(it.deviceName, c.startBlock) != null } &&
            it.deviceName !in excluded
        }
    }

    private fun triggerAutoMount(newCandidates: List<UsbDeviceCandidate>) {
        val toMount = filterAutoMountCandidates(newCandidates)
        if (toMount.isEmpty()) return
        toMount.forEach { autoMountAttempted.add(it.deviceName) }
        // Debounce: accumulate hub devices that arrive in rapid succession, then
        // fire one combined biometric prompt for all of them after a short delay.
        pendingAutoMountDevices.addAll(toMount)
        autoMountDebounceHandler.removeCallbacks(autoMountDebounceRunnable)
        autoMountDebounceHandler.postDelayed(autoMountDebounceRunnable, 400L)
    }

    private fun showAutoMountPrompt(devices: List<UsbDeviceCandidate>) {
        if (isAutoMountPromptShowing) {
            // A prompt is already up for another device; do not strand these.
            restoreDeferredCandidates()
            return
        }
        isAutoMountPromptShowing = true
        val executor = androidx.core.content.ContextCompat.getMainExecutor(this)
        val callback = object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                isAutoMountPromptShowing = false
                // Restore so attemptUnlock can look up displayName, and the form shows any
                // remaining candidates while each partition mounts in the background.
                restoreDeferredCandidates()
                devices.forEach { device ->
                    val allCreds = credentialStore.loadAll(device.deviceName)
                    if (allCreds.isEmpty()) return@forEach
                    device.candidates.forEach { candidate ->
                        val creds = allCreds.find { it.candidateStartBlock == candidate.startBlock }
                            ?: return@forEach
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
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                isAutoMountPromptShowing = false
                // Restore so the user can fall back to manual unlock after dismissing or
                // cancelling the prompt.
                restoreDeferredCandidates()
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

    private fun showQuickUnlockPrompt(deviceName: String, candidate: VolumeCandidate, onComplete: () -> Unit) {
        val creds = sessionPlaintextCreds[deviceName]?.takeIf { it.candidateStartBlock == candidate.startBlock }
            ?: credentialStore.load(deviceName, candidate.startBlock)
            ?: run {
            appendLog("No credentials found for $deviceName partition ${candidate.startBlock}")
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
        val displayName = _deviceCandidates.value.find { it.deviceName == deviceName }?.displayName ?: deviceName
        val builder = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.auto_mount_prompt_title))
            .setSubtitle(displayName)
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
        OtgMasterState.clearLogHistory()
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
        // Mirrored into process-wide state so FeedbackActivity can show the same lines.
        OtgMasterState.recordLog(line)
    }

    private fun handleShareIntent(intent: Intent) {
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                } ?: emptyList()
            }
            else -> return
        }
        if (uris.isEmpty()) return

        if (OtgMasterState.mountedDrives.isEmpty()) {
            toastState.value = Pair("No drives mounted. Mount a drive first.", false)
            return
        }

        pendingShareUris = uris
        // Use the same /root/ URI format that openFilesApp uses successfully — this passes
        // DocumentsUI's isRootUri() check and opens the picker at our drive root directly.
        // If a specific drive was tapped via a sharing shortcut use that drive's root;
        // otherwise use the first mounted drive (single drive = no ambiguity).
        val driveId = intent.getStringExtra(EXTRA_DRIVE_ID)
            ?: OtgMasterState.mountedDrives.firstOrNull()?.id
        val initialUri = driveId?.let { id ->
            val rootId = app.fayaz.otgmaster.provider.VeraCryptDocumentProvider.rootIdForDrive(id)
            Uri.parse("content://${app.fayaz.otgmaster.provider.VeraCryptDocumentProvider.AUTHORITY}/root/$rootId")
        }
        openDocumentTreeLauncher.launch(initialUri)
    }



    private fun copyFilesToTree(sourceUris: List<Uri>, treeUri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            var successCount = 0
            var errorMsg: String? = null
            try {
                val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
                for (sourceUri in sourceUris) {
                    val fileName = getFileNameFromUri(sourceUri) ?: "file_${System.currentTimeMillis()}"
                    val mimeType = contentResolver.getType(sourceUri) ?: "*/*"
                    val destDoc = DocumentsContract.createDocument(contentResolver, parentDocUri, mimeType, fileName)
                    if (destDoc != null) {
                        contentResolver.openInputStream(sourceUri)?.use { input ->
                            contentResolver.openOutputStream(destDoc)?.use { output ->
                                input.copyTo(output)
                            }
                        }
                        successCount++
                    }
                }
            } catch (e: Exception) {
                errorMsg = e.message
            }
            withContext(Dispatchers.Main) {
                toastState.value = if (errorMsg != null) {
                    Pair(getString(R.string.share_save_failed, errorMsg), false)
                } else if (successCount == 1) {
                    Pair(getString(R.string.share_saved, getFileNameFromUri(sourceUris[0]) ?: "file"), true)
                } else {
                    Pair(getString(R.string.share_saved_multiple, successCount), true)
                }
            }
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx != -1) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment
    }

    private fun pushDriveShortcut(drive: MountedDrive) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val shortcutManager = getSystemService(android.content.pm.ShortcutManager::class.java) ?: return
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_DEFAULT
            putExtra(EXTRA_DRIVE_ID, drive.id)
        }
        val shortcut = android.content.pm.ShortcutInfo.Builder(this, "drive_share_${drive.id}")
            .setShortLabel(drive.name)
            .setLongLabel(drive.name)
            .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(intent)
            .setCategories(setOf(SHARE_TARGET_CATEGORY))
            .setLongLived(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            shortcutManager.pushDynamicShortcut(shortcut)
        } else {
            shortcutManager.addDynamicShortcuts(listOf(shortcut))
        }
    }

    private fun removeDriveShortcut(driveId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val shortcutManager = getSystemService(android.content.pm.ShortcutManager::class.java) ?: return
        shortcutManager.removeDynamicShortcuts(listOf("drive_share_$driveId"))
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

/** Tag text and colour for a container type, shared by the drive cards and the volume picker. */
fun encryptionTag(type: app.fayaz.otgmaster.veracrypt.ContainerType?): Pair<String, Color> =
    when (type) {
        app.fayaz.otgmaster.veracrypt.ContainerType.VERACRYPT -> Pair("VERACRYPT", Color(0xFF3949AB))
        app.fayaz.otgmaster.veracrypt.ContainerType.LUKS1     -> Pair("LUKS1",     Color(0xFFE65100))
        app.fayaz.otgmaster.veracrypt.ContainerType.LUKS2     -> Pair("LUKS2",     Color(0xFF6A1B9A))
        // "could not tell" is not the same claim as "it is not encrypted", and the
        // old catch-all else asserted the latter for both.
        app.fayaz.otgmaster.veracrypt.ContainerType.UNKNOWN   -> Pair("UNKNOWN",   Color(0xFF757575))
        // null is a plain-mounted drive card, which has no candidate behind it.
        app.fayaz.otgmaster.veracrypt.ContainerType.UNENCRYPTED, null ->
            Pair("UNENCRYPTED", Color(0xFF546E7A))
    }

@Composable
fun DriveTag(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color,
        contentColor = Color.White,
    ) {
        androidx.compose.foundation.layout.Box(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelSmall)
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
    hasCachedCreds: (String, Long?) -> Boolean,
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
    onQuickUnlock: (String, VolumeCandidate, () -> Unit) -> Unit = { _, _, cb -> cb() },
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
        
        Button(
            onClick = onRefreshDevices,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "scan_button" }
        ) {
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

                        val deviceTitle = buildString {
                            append(drive.sourceDeviceDisplayName ?: drive.name)
                            if (drive.partitionLabel.isNotEmpty()) {
                                append(" · ")
                                append(drive.partitionLabel)
                            }
                        }
                        Text(text = deviceTitle, style = MaterialTheme.typography.titleMedium)

                        Spacer(modifier = Modifier.height(6.dp))

                        // Encryption + filesystem tags
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val encTag = encryptionTag(drive.sourceVolumeCandidate?.containerType)
                            DriveTag(label = encTag.first, color = encTag.second)

                            if (drive.filesystemName.isNotEmpty()) {
                                val fsColor = when {
                                    drive.filesystemName.startsWith("ext", ignoreCase = true) -> Color(0xFF1565C0)
                                    drive.filesystemName.equals("exFAT", ignoreCase = true)   -> Color(0xFF00796B)
                                    drive.filesystemName.equals("FAT32", ignoreCase = true)   -> Color(0xFF2E7D32)
                                    else -> Color(0xFF37474F)
                                }
                                DriveTag(label = drive.filesystemName.uppercase(), color = fsColor)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
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
                        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onOpenFilesApp(drive) }) {
                                Text(stringResource(R.string.open_files_app))
                            }
                            if (!drive.isPlain) {
                                Button(
                                    onClick = { onUnmount(drive) },
                                    modifier = Modifier.semantics { contentDescription = "unmount_button" }
                                ) {
                                    Text(stringResource(R.string.unmount))
                                }
                            }
                            val deviceKey = drive.sourceDeviceName
                            if (deviceKey != null && hasCachedCreds(deviceKey, null)) {
                                Button(onClick = { onClearDeviceCreds(deviceKey) }) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                TextButton(onClick = {
                    ctx.startActivity(
                        Intent(ctx, app.fayaz.otgmaster.feedback.FeedbackActivity::class.java)
                    )
                }) {
                    Text(stringResource(R.string.feedback_report_issue))
                }
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
    hasCachedCreds: (String, Long?) -> Boolean = { _, _ -> false },
    isExcluded: (String) -> Boolean = { false },
    onSetExcluded: (String, Boolean) -> Unit = { _, _ -> },
    onQuickUnlock: ((String, VolumeCandidate, () -> Unit) -> Unit)? = null
) {
    var isUnlocking by remember { mutableStateOf(false) }
    var selectedDevice by remember(deviceCandidates) { mutableStateOf(deviceCandidates.firstOrNull()) }
    var deviceExpanded by remember { mutableStateOf(false) }
    val candidates = selectedDevice?.candidates.orEmpty()
    val hiddenPartitions = selectedDevice?.hiddenPartitions ?: 0

    val sessionCreds = sessionCredentials[selectedDevice?.deviceName]
    val isPreFilled = sessionCreds != null

    var selectedCandidate by remember(selectedDevice) { mutableStateOf(
        sessionCreds?.let { sc -> selectedDevice?.candidates?.find { it.startBlock == sc.candidateStartBlock } }
            ?: selectedDevice?.candidates?.firstOrNull()
    ) }
    val isLuks = selectedCandidate?.containerType == app.fayaz.otgmaster.veracrypt.ContainerType.LUKS1 ||
                 selectedCandidate?.containerType == app.fayaz.otgmaster.veracrypt.ContainerType.LUKS2
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
        focusManager.clearFocus()
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
            // Check only the selected candidate — if it has cached creds offer biometric,
            // otherwise show the password form so the user can enter creds manually.
            val showQuickUnlock = hasCachedCreds(currentDeviceName, selectedCandidate?.startBlock) &&
                onQuickUnlock != null

            if (candidates.isEmpty()) {
                Text(stringResource(R.string.no_candidates_found), color = MaterialTheme.colorScheme.error)
            } else {
            // Partition picker: always visible when there are multiple candidates so the
            // user can choose which one to unlock, regardless of whether quick-unlock applies.
            if (candidates.size > 1) {
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
                        val tag = encryptionTag(candidate.containerType)
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(candidate.label, modifier = Modifier.weight(1f))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    DriveTag(label = tag.first, color = tag.second)
                                }
                            },
                            onClick = {
                                selectedCandidate = candidate
                                expanded = false
                            }
                        )
                    }
                }
            }
            }
            // The picker only lists partitions it can act on. Without this note a
            // drive whose other partitions were filtered out looks like it has
            // fewer partitions than it does — which is how drive D's NTFS
            // partition went missing with no explanation anywhere in the UI.
            if (hiddenPartitions > 0) {
                Text(
                    text = stringResource(R.string.volume_picker_filtered_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
                )
            }

            if (showQuickUnlock) {
                Button(
                    onClick = {
                        isUnlocking = true
                        val device = selectedDevice
                        val candidate = selectedCandidate
                        if (device != null && candidate != null) {
                            onQuickUnlock!!(device.deviceName, candidate) { isUnlocking = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "mount_button" },
                    enabled = selectedDevice != null && selectedCandidate != null && !isUnlocking
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
            
            OutlinedTextField(
                value = password,
                onValueChange = { if (!isPreFilled) password = it },
                label = { Text(stringResource(if (isLuks) R.string.label_password else R.string.label_veracrypt_password)) },
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

            if (!isLuks) {
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
            } // end if (!isLuks) for PIM + keyfiles

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

            if (!isLuks) {
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
            } // end if (!isLuks) for cipher + hash

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
                    (password.isNotEmpty() || (!isLuks && keyfiles.isNotEmpty())) && !isUnlocking
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

                    // Scrolls independently of the header, which stays reachable. The
                    // drawer is a fixed 300.dp column and an expanded row can exceed the
                    // screen on its own.
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
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
                    }
                }
            }
        }
    }
}

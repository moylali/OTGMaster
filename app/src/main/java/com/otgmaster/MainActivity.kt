package com.otgmaster

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.otgmaster.block.RawBlockDevice
import com.otgmaster.usb.LibaumsRawBlockDeviceOpener
import com.otgmaster.usb.UsbDeviceDescriber
import com.otgmaster.veracrypt.VeraCryptUnlocker
import com.otgmaster.veracrypt.VolumeCandidate
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystemCreator
import me.jahnen.libaums.core.partition.PartitionTableEntry
import android.widget.EditText

class MainActivity : Activity() {
    private lateinit var usbManager: UsbManager
    private lateinit var statusView: TextView
    private lateinit var passwordInput: EditText
    private lateinit var pimInput: EditText
    private lateinit var keyfileButton: Button
    private lateinit var unlockButton: Button
    private lateinit var unmountButton: Button
    private var openedBlockDevice: RawBlockDevice? = null
    private var decryptedBlockDevice: RawBlockDevice? = null
    private var currentCandidates: List<VolumeCandidate> = emptyList()
    private var selectedKeyfileUris = mutableListOf<android.net.Uri>()

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
                        appendStatus("USB permission granted for ${device.deviceName}.")
                        openAndProbeUsb()
                    } else {
                        appendStatus("USB permission denied.")
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    appendStatus("USB device attached.")
                    refreshDevices()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    appendStatus("USB device detached.")
                    refreshDevices()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        registerUsbReceiver()
        setContentView(buildContentView())
        refreshDevices()
    }

    override fun onDestroy() {
        openedBlockDevice?.close()
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    private fun buildContentView(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 40)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            gravity = Gravity.START
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "Userspace USB + VeraCrypt unlock prototype"
            textSize = 15f
            setPadding(0, 8, 0, 24)
        }
        root.addView(subtitle)

        val refresh = Button(this).apply {
            text = "Scan USB devices"
            setOnClickListener { refreshDevices() }
        }
        root.addView(refresh)

        passwordInput = EditText(this).apply {
            hint = "VeraCrypt Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            visibility = android.view.View.GONE
        }
        root.addView(passwordInput)

        pimInput = EditText(this).apply {
            hint = "PIM (leave blank for default)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            visibility = android.view.View.GONE
        }
        root.addView(pimInput)

        keyfileButton = Button(this).apply {
            text = "Select Keyfiles (0)"
            visibility = android.view.View.GONE
            setOnClickListener { selectKeyfile() }
        }
        root.addView(keyfileButton)

        unlockButton = Button(this).apply {
            text = "Unlock & Read FAT32"
            visibility = android.view.View.GONE
            setOnClickListener { attemptUnlock() }
        }
        root.addView(unlockButton)

        unmountButton = Button(this).apply {
            text = "Unmount Drive"
            visibility = android.view.View.GONE
            setOnClickListener { unmountDrive() }
        }
        root.addView(unmountButton)

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 28, 0, 0)
        }
        root.addView(statusView)

        return ScrollView(this).apply { addView(root) }
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
        statusView.text = ""

        if (devices.isEmpty()) {
            appendStatus("No USB devices found. Attach a USB mass-storage device with an OTG adapter.")
            return
        }

        appendStatus("Found ${devices.size} USB device(s):")
        devices.forEach { device ->
            appendStatus("")
            appendStatus(UsbDeviceDescriber.describe(device))
            if (usbManager.hasPermission(device)) {
                appendStatus("Permission already granted.")
                openAndProbeUsb()
            } else {
                appendStatus("Requesting permission...")
                usbManager.requestPermission(device, permissionIntent)
            }
        }
    }

    private fun openAndProbeUsb() {
        appendStatus("Opening raw USB block device...")
        Thread {
            val result = runCatching {
                Log.i(TAG, "Opening raw USB block device")
                openedBlockDevice?.close()
                val opened = LibaumsRawBlockDeviceOpener(this).openFirstAvailable()
                openedBlockDevice = opened.blockDevice

                val candidates = VeraCryptUnlocker().probeCandidates(opened.blockDevice)
                runOnUiThread {
                    currentCandidates = candidates
                    if (candidates.isNotEmpty()) {
                        showPasswordInput()
                    }
                }
                buildString {
                    appendLine("Opened ${opened.usbDevice.deviceName} interface ${opened.usbInterface.id}.")
                    appendLine("Block size: ${opened.blockDevice.blockSize} bytes")
                    appendLine("Blocks: ${opened.blockDevice.blockCount}")
                    appendLine("Capacity: ${opened.blockDevice.blockSize * opened.blockDevice.blockCount / (1024 * 1024)} MiB")
                    appendLine("Volume candidates:")
                    candidates.forEach { candidate ->
                        appendLine("  ${candidate.label}: start block ${candidate.startBlock}, blocks ${candidate.blockCount ?: "unknown"}")
                    }
                }
            }.getOrElse { error ->
                Log.e(TAG, "USB block open failed", error)
                "USB block open failed: ${error.message ?: error.javaClass.simpleName}"
            }

            Log.i(TAG, result.trimEnd())
            runOnUiThread { appendStatus(result.trimEnd()) }
        }.start()
    }

    private fun showPasswordInput() {
        passwordInput.visibility = android.view.View.VISIBLE
        pimInput.visibility = android.view.View.VISIBLE
        keyfileButton.visibility = android.view.View.VISIBLE
        unlockButton.visibility = android.view.View.VISIBLE
        unmountButton.visibility = android.view.View.GONE
    }

    private fun attemptUnlock() {
        val device = openedBlockDevice
        val candidate = currentCandidates.firstOrNull()
        if (device == null || candidate == null) {
            appendStatus("No device or candidate to unlock.")
            return
        }

        val pwd = passwordInput.text.toString().toCharArray()
        val pimStr = pimInput.text.toString()
        val pim = if (pimStr.isNotBlank()) pimStr.toIntOrNull() else null

        if (pwd.isEmpty() && selectedKeyfileUris.isEmpty()) {
            appendStatus("Please enter a password or select keyfiles.")
            return
        }

        appendStatus("Attempting unlock of ${candidate.label}...")
        passwordInput.text.clear()
        pimInput.text.clear()

        Thread {
            try {
                val decryptedDevice = VeraCryptUnlocker().unlock(device, candidate, pwd, pim, selectedKeyfileUris, contentResolver)
                this.decryptedBlockDevice = decryptedDevice
                runOnUiThread { 
                    appendStatus("Unlock successful! Reading FAT32...")
                    passwordInput.visibility = android.view.View.GONE
                    pimInput.visibility = android.view.View.GONE
                    keyfileButton.visibility = android.view.View.GONE
                    unlockButton.visibility = android.view.View.GONE
                    unmountButton.visibility = android.view.View.VISIBLE
                }
                
                val dummyEntry = PartitionTableEntry(0, 0, 0)
                val byteDevice = me.jahnen.libaums.core.driver.ByteBlockDevice(decryptedDevice as me.jahnen.libaums.core.driver.BlockDeviceDriver)
                val fileSystem = Fat32FileSystemCreator().read(dummyEntry, byteDevice)
                if (fileSystem == null) {
                    runOnUiThread { appendStatus("Failed to mount FAT32 file system") }
                    return@Thread
                }
                
                OtgMasterState.currentFileSystem = fileSystem
                contentResolver.notifyChange(android.provider.DocumentsContract.buildRootsUri("com.otgmaster.documents"), null)
                
                runOnUiThread {
                    appendStatus("Mounted FAT32. Root capacity: ${fileSystem.capacity / (1024 * 1024)} MB")
                    val files = fileSystem.rootDirectory.listFiles()
                    appendStatus("Root directory files:")
                    files.forEach { appendStatus("  ${if(it.isDirectory) "[DIR]" else "[FILE]"} ${it.name}") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unlock/Read failed", e)
                runOnUiThread { appendStatus("Failed: ${e.message}") }
            }
        }.start()
    }

    private fun appendStatus(line: String) {
        statusView.append(line)
        statusView.append("\n")
    }

    private inline fun <reified T> Intent.getParcelableExtraCompat(name: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }
    }

    private fun selectKeyfile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, 1001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            if (data?.clipData != null) {
                val count = data.clipData!!.itemCount
                for (i in 0 until count) {
                    selectedKeyfileUris.add(data.clipData!!.getItemAt(i).uri)
                }
            } else if (data?.data != null) {
                selectedKeyfileUris.add(data.data!!)
            }
            keyfileButton.text = "Select Keyfiles (${selectedKeyfileUris.size})"
        }
    }

    private fun unmountDrive() {
        OtgMasterState.currentFileSystem = null
        contentResolver.notifyChange(android.provider.DocumentsContract.buildRootsUri("com.otgmaster.documents"), null)
        decryptedBlockDevice?.close()
        decryptedBlockDevice = null
        
        selectedKeyfileUris.clear()
        keyfileButton.text = "Select Keyfiles (0)"
        
        showPasswordInput()
        appendStatus("Drive unmounted securely.")
    }

    private companion object {
        const val TAG = "OTGMaster"
        const val ACTION_USB_PERMISSION = "com.otgmaster.USB_PERMISSION"
    }
}

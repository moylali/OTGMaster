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
import com.otgmaster.fs.Fat32Reader
import android.widget.EditText

class MainActivity : Activity() {
    private lateinit var usbManager: UsbManager
    private lateinit var statusView: TextView
    private lateinit var passwordInput: EditText
    private lateinit var unlockButton: Button
    private var openedBlockDevice: RawBlockDevice? = null
    private var currentCandidates: List<VolumeCandidate> = emptyList()

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

        unlockButton = Button(this).apply {
            text = "Unlock & Read FAT32"
            visibility = android.view.View.GONE
            setOnClickListener { attemptUnlock() }
        }
        root.addView(unlockButton)

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
                        passwordInput.visibility = android.view.View.VISIBLE
                        unlockButton.visibility = android.view.View.VISIBLE
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

    private fun attemptUnlock() {
        val pwd = passwordInput.text.toString().toCharArray()
        if (pwd.isEmpty()) return
        val device = openedBlockDevice ?: return
        val candidate = currentCandidates.firstOrNull() ?: return

        appendStatus("Attempting unlock of ${candidate.label}...")
        passwordInput.text.clear()

        Thread {
            try {
                val decryptedDevice = VeraCryptUnlocker().unlock(device, candidate, pwd)
                runOnUiThread { appendStatus("Unlock successful! Reading FAT32...") }
                
                val files = Fat32Reader(decryptedDevice).listRootDirectory()
                runOnUiThread {
                    appendStatus("Root directory files:")
                    files.forEach { appendStatus("  $it") }
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

    private companion object {
        const val TAG = "OTGMaster"
        const val ACTION_USB_PERMISSION = "com.otgmaster.USB_PERMISSION"
    }
}

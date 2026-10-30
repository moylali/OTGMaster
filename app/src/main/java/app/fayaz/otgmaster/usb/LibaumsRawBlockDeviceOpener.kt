package app.fayaz.otgmaster.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.driver.BlockDeviceDriverFactory
import me.jahnen.libaums.core.driver.scsi.commands.sense.MediaNotInserted
import me.jahnen.libaums.core.usb.UsbCommunicationFactory
import java.io.IOException

class LibaumsRawBlockDeviceOpener(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val quirks = UsbTransferQuirks(context)

    init {
        // libaums' own retries, Reset Recoveries and port resets, one line each, into
        // the app's log so "Report issue" carries them (libaums V22). Capped per open,
        // like LibaumsRawBlockDevice's own lines, so a drive failing in a loop cannot
        // push the rest of the history out of the report's budget.
        me.jahnen.libaums.core.usb.UsbDiagnostics.sink = { line ->
            if (libaumsLines.incrementAndGet() <= MAX_LIBAUMS_LINES) app.fayaz.otgmaster.OtgMasterState.log(line)
        }
    }

    /**
     * Opens every attached mass-storage device not already in [excludeDeviceKeys] (e.g.
     * already-mounted devices, keyed by [UsbDeviceDescriber.stableKey]), so the UI can let
     * the user pick which one to unlock instead of only ever surfacing a single "next" device.
     */
    fun openAllAvailable(excludeDeviceKeys: Set<String> = emptySet()): List<OpenedUsbBlockDevice> {
        val opened = mutableListOf<OpenedUsbBlockDevice>()
        for (device in usbManager.deviceList.values) {
            val supportedInterfaces = device.supportedMassStorageInterfaces()
            if (supportedInterfaces.isEmpty()) continue
            val hasPermission = usbManager.hasPermission(device)
            if (UsbDeviceDescriber.stableKey(device, hasPermission) in excludeDeviceKeys) continue
            if (!hasPermission) {
                Log.w(TAG, "${device.deviceName}: missing USB permission")
                continue
            }

            for (candidate in supportedInterfaces) {
                try {
                    Log.i(TAG, "Opening ${device.deviceName} interface ${candidate.usbInterface.id}")
                    val blockDevice = open(device, candidate)
                    opened += OpenedUsbBlockDevice(device, candidate.usbInterface, blockDevice)
                    break
                } catch (error: Exception) {
                    Log.e(TAG, "Failed opening ${device.deviceName}", error)
                }
            }
        }
        return opened
    }

    private fun open(device: UsbDevice, candidate: InterfaceCandidate): RawBlockDevice {
        val communication = UsbCommunicationFactory.createUsbCommunication(
            usbManager,
            device,
            candidate.usbInterface,
            candidate.outEndpoint,
            candidate.inEndpoint,
        )

        try {
            val maxLun = ByteArray(1)
            communication.controlTransfer(0xA1, 0xFE, 0, candidate.usbInterface.id, maxLun, 1)
            val lunCount = maxLun[0].toInt().coerceAtLeast(0)

            for (lun in 0..lunCount) {
                val driver = BlockDeviceDriverFactory.createBlockDevice(communication, lun.toByte())
                try {
                    Log.i(TAG, "Initializing LUN $lun")
                    driver.init()
                    Log.i(TAG, "Initialized LUN $lun: blockSize=${driver.blockSize}, blocks=${driver.blocks}")
                    // In the app's own log, so a feedback report carries it. The USB
                    // failures in #29, #71 and #88 could not be compared against each
                    // other for want of this: the drive's identity, the endpoint sizes
                    // and the phone's kernel were all missing. No serial number — see
                    // UsbTransferQuirks and FeedbackPayload on why.
                    libaumsLines.set(0)
                    app.fayaz.otgmaster.OtgMasterState.log(
                        "USB: %04x:%04x %s %s, LUN %d of %d, %d blocks of %d B; bulk in/out %d/%d B; %s %s, Android %s, kernel %s"
                            .format(
                                device.vendorId, device.productId,
                                device.manufacturerName ?: "?", device.productName ?: "?",
                                lun, lunCount + 1, driver.blocks, driver.blockSize,
                                candidate.inEndpoint.maxPacketSize, candidate.outEndpoint.maxPacketSize,
                                android.os.Build.MANUFACTURER, android.os.Build.MODEL,
                                android.os.Build.VERSION.RELEASE, System.getProperty("os.version"),
                            )
                    )
                    val blockDevice = LibaumsRawBlockDevice(
                        driver,
                        communication,
                        rememberedTransferBytes = quirks.transferBytes(device.vendorId, device.productId),
                        onTransferSizeLowered = { quirks.remember(device.vendorId, device.productId, it) },
                    )
                    // Before the device is handed out, so the size is settled with
                    // nothing mounted on top of it. See probeTransferSize.
                    blockDevice.probeTransferSize()
                    return blockDevice
                } catch (_: MediaNotInserted) {
                    Log.i(TAG, "No media inserted in LUN $lun")
                    continue
                }
            }

            throw IOException("no media inserted in any logical unit")
        } catch (error: Exception) {
            communication.close()
            throw error
        }
    }

    private fun UsbDevice.supportedMassStorageInterfaces(): List<InterfaceCandidate> {
        return (0 until interfaceCount).mapNotNull { index ->
            val usbInterface = getInterface(index)
            if (usbInterface.interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE ||
                usbInterface.interfaceSubclass != INTERFACE_SUBCLASS_SCSI ||
                usbInterface.interfaceProtocol != INTERFACE_PROTOCOL_BULK_ONLY
            ) {
                return@mapNotNull null
            }

            var inEndpoint: UsbEndpoint? = null
            var outEndpoint: UsbEndpoint? = null
            for (endpointIndex in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                    inEndpoint = endpoint
                } else if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                    outEndpoint = endpoint
                }
            }

            val input = inEndpoint ?: return@mapNotNull null
            val output = outEndpoint ?: return@mapNotNull null
            InterfaceCandidate(usbInterface, input, output)
        }
    }

    private data class InterfaceCandidate(
        val usbInterface: UsbInterface,
        val inEndpoint: UsbEndpoint,
        val outEndpoint: UsbEndpoint,
    )

    data class OpenedUsbBlockDevice(
        val usbDevice: UsbDevice,
        val usbInterface: UsbInterface,
        val blockDevice: RawBlockDevice,
    ) {
        // Permission was already confirmed before this was constructed (see openAllAvailable).
        val deviceKey: String get() = UsbDeviceDescriber.stableKey(usbDevice, hasPermission = true)
    }

    private companion object {
        const val TAG = "OTGMaster"
        /** See the init block. About one failing request's worth, as in LibaumsRawBlockDevice. */
        const val MAX_LIBAUMS_LINES = 12
        val libaumsLines = java.util.concurrent.atomic.AtomicInteger(0)
        const val INTERFACE_SUBCLASS_SCSI = 6
        const val INTERFACE_PROTOCOL_BULK_ONLY = 80
    }
}

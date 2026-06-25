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
                    return LibaumsRawBlockDevice(driver, communication)
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
        const val INTERFACE_SUBCLASS_SCSI = 6
        const val INTERFACE_PROTOCOL_BULK_ONLY = 80
    }
}

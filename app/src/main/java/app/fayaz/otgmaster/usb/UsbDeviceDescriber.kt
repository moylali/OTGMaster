package app.fayaz.otgmaster.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice

object UsbDeviceDescriber {
    /**
     * A device identity that survives USB re-enumeration. [UsbDevice.deviceName] is just the
     * current bus path (e.g. "/dev/bus/usb/001/003") and gets reassigned to a new value if the
     * device detaches/reattaches — which can happen mid-mount on OTG-powered drives from a
     * momentary power glitch — so keying "already mounted" checks on it alone can mistake a
     * still-mounted drive for a new one. Falls back to deviceName only when no serial is
     * available (common on cheap/no-name drives), which remains a known edge case.
     */
    fun stableKey(device: UsbDevice, hasPermission: Boolean): String {
        val idPart = "${device.vendorId}:${device.productId}"
        val serial = if (hasPermission) {
            try { device.serialNumber } catch (_: SecurityException) { null }
        } else null
        return if (!serial.isNullOrBlank()) "$idPart:$serial" else "$idPart:${device.deviceName}"
    }

    /**
     * Whether [device] exposes a SCSI bulk-only mass-storage interface at all — used to
     * filter out unrelated attached USB devices (hubs, keyboards, the OTG adapter chip
     * itself, etc.) before bothering to request permission for or try to open them.
     */
    fun isMassStorageDevice(device: UsbDevice): Boolean {
        return (0 until device.interfaceCount).any { index ->
            val usbInterface = device.getInterface(index)
            usbInterface.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                usbInterface.interfaceSubclass == 6 &&
                usbInterface.interfaceProtocol == 80
        }
    }

    /**
     * A short, human-readable name for picking a device out of a dropdown — e.g.
     * "Kingston DataTraveler 3.0", falling back to "USB Drive N" when the device doesn't
     * report a manufacturer/product name (common on cheap/no-name flash drives).
     */
    fun friendlyName(device: UsbDevice, fallbackIndex: Int): String {
        val manufacturer = device.manufacturerName?.trim()?.takeIf { it.isNotEmpty() }
        val product = device.productName?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            manufacturer != null && product != null && product.startsWith(manufacturer, ignoreCase = true) -> product
            manufacturer != null && product != null -> "$manufacturer $product"
            product != null -> product
            manufacturer != null -> manufacturer
            else -> "USB Drive ${fallbackIndex + 1}"
        }
    }

    fun describe(device: UsbDevice): String {
        val interfaces = (0 until device.interfaceCount).joinToString(separator = "\n") { index ->
            val usbInterface = device.getInterface(index)
            val className = when (usbInterface.interfaceClass) {
                UsbConstants.USB_CLASS_MASS_STORAGE -> "mass storage"
                UsbConstants.USB_CLASS_HID -> "HID"
                UsbConstants.USB_CLASS_COMM -> "communications"
                else -> "class ${usbInterface.interfaceClass}"
            }
            "  interface $index: $className, subclass ${usbInterface.interfaceSubclass}, protocol ${usbInterface.interfaceProtocol}"
        }

        return buildString {
            appendLine("${device.deviceName} vendor=${device.vendorId} product=${device.productId}")
            append(interfaces.ifBlank { "  no interfaces reported" })
        }
    }
}

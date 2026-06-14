package app.fayaz.otgmaster.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice

object UsbDeviceDescriber {
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

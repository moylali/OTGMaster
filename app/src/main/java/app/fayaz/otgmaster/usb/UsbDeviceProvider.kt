package app.fayaz.otgmaster.usb

import android.hardware.usb.UsbDevice
import android.app.PendingIntent

/**
 * Abstraction for obtaining USB devices and handling permission.
 * Allows swapping the real Android UsbManager with a mock implementation for tests.
 */
interface UsbDeviceProvider {
    /** Return the list of USB devices currently visible to the system */
    fun getDevices(): List<UsbDevice>

    /** Whether the app already has permission for the given device */
    fun hasPermission(device: UsbDevice): Boolean

    /** Request permission for the given device */
    fun requestPermission(device: UsbDevice, pendingIntent: PendingIntent)
}

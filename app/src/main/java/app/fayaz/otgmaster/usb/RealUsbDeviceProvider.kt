package app.fayaz.otgmaster.usb

import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * Production implementation of [UsbDeviceProvider] that delegates to the real Android
 * [UsbManager]. This is used when the app runs on a real device or emulator without a mock.
 */
class RealUsbDeviceProvider(
    private val usbManager: UsbManager,
    private val permissionIntent: PendingIntent
) : UsbDeviceProvider {

    override fun getDevices(): List<UsbDevice> = usbManager.deviceList.values.toList()

    override fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    override fun requestPermission(device: UsbDevice, pendingIntent: PendingIntent) {
        // The PendingIntent is usually the same one we keep in MainActivity, but we accept it as a param
        usbManager.requestPermission(device, pendingIntent)
    }
}

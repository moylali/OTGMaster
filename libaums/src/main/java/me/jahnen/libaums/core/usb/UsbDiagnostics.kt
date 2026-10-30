package me.jahnen.libaums.core.usb

/**
 * LOCAL PATCH (docs/VENDOR_FIXES.md V22): a way out for what libaums only told logcat.
 *
 * libaums logs each failed attempt, each Reset Recovery and the port-reset ioctl to
 * logcat, which a user's bug report never carries. The app installs [sink] to put
 * these in its own log. Callers send one summary line per event, not one per retry,
 * so a drive failing in a loop cannot crowd everything else out of a report.
 */
object UsbDiagnostics {
    @JvmStatic @Volatile var sink: ((String) -> Unit)? = null

    fun report(line: String) {
        sink?.invoke(line)
    }
}

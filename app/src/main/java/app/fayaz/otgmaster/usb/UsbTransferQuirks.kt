package app.fayaz.otgmaster.usb

import android.content.Context

/**
 * The largest USB bulk transfer each drive model is known to accept on this phone.
 *
 * The size a drive will take is a property of the drive, the phone's USB stack and
 * the cable between them, and nothing reports it — it can only be found by trying.
 * Finding it costs a failed transfer, and a failed transfer is not free: on the
 * drives in issues #71 and #88 the first 64 KiB read left the drive answering
 * nothing until it was physically unplugged.
 *
 * So the answer has to outlive the [LibaumsRawBlockDevice] that found it. It did
 * not before, and that is what turned a one-off failure into a permanent one:
 * every attach built a new device starting at 120 KiB, which stuck the drive again
 * before anything could be mounted. #88's log shows the same failure after each of
 * three replugs, because each replug threw the answer away.
 *
 * Keyed on vendor and product id, which is the granularity the quirk belongs to —
 * one drive model behaves like another of the same model. Deliberately not the
 * serial number, which FeedbackPayload also refuses to collect because it
 * identifies the physical drive rather than its make.
 *
 * Only ever revised downwards. A size is recorded when a transfer of it failed, and
 * nothing here raises one again, so a drive that fails once on a bad cable keeps
 * the smaller size on this phone even after the cable is replaced. That costs
 * throughput and never correctness (§5.3 of docs/IO_PERFORMANCE.md measures the
 * curve), and clearing the app's storage resets it.
 */
class UsbTransferQuirks(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The remembered size for this drive model, or null if it has never failed. */
    fun transferBytes(vendorId: Int, productId: Int): Int? =
        prefs.getInt(key(vendorId, productId), 0).takeIf { it > 0 }

    /** Records [bytes] for this drive model, if it is smaller than what is known. */
    fun remember(vendorId: Int, productId: Int, bytes: Int) {
        if (bytes <= 0) return
        val key = key(vendorId, productId)
        if (bytes >= prefs.getInt(key, Int.MAX_VALUE)) return
        prefs.edit().putInt(key, bytes).apply()
    }

    private fun key(vendorId: Int, productId: Int) =
        "max_transfer_%04x_%04x".format(vendorId, productId)

    private companion object {
        /** Its own file, not otgmaster_prefs: this is learned device behaviour, not a setting. */
        const val PREFS = "otg_usb_quirks"
    }
}

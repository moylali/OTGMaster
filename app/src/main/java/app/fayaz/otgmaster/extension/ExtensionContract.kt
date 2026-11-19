package app.fayaz.otgmaster.extension

import app.fayaz.otgmaster.BuildConfig
import app.fayaz.otgmaster.veracrypt.VeraCryptVolumeType
import java.security.MessageDigest

/**
 * The provider authority and the drive ids inside its document ids.
 *
 * Other apps reach a mounted volume only the ordinary way: the user grants them a
 * folder through ACTION_OPEN_DOCUMENT_TREE, and they see the standard
 * DocumentsContract columns of what is under it. Nothing else is exposed.
 *
 * Until 0.5.4 this was also a companion-app API (for an external Backup Master):
 * mount and unmount broadcasts to any package with a receiver for them, and extra
 * `otgmaster_*` columns (USB vendor, product and serial, partition start, container
 * and more) on every document. Together they let an app with a grant on a VeraCrypt
 * outer volume compute the hidden volume's id from those columns and recognise it in
 * a broadcast, which told it a hidden volume existed (#165, reported privately). No
 * companion app exists, so both were removed rather than repaired; add an API back
 * when there is a use for one.
 *
 * Drive ids stay derived from the USB identity and the partition's start, so a tree
 * URI granted today still names the same partition after an unplug, a reboot or an
 * app update. With the random ids used before, every persisted grant went stale at
 * the next mount, which makes scheduled work against a drive impossible.
 */
object ExtensionContract {
    // "app.fayaz.otgmaster.documents" in the store build. Derived rather than written
    // out so the benchmark build (".bench" suffix) can be installed beside it: two
    // packages declaring one authority cannot both be installed.
    const val AUTHORITY = BuildConfig.APPLICATION_ID + ".documents"

    /**
     * The drive id for a partition: 10 hex digits of SHA-256 over the device's
     * stable key (UsbDeviceDescriber.stableKey — "vid:pid:serial") and the
     * partition's first block.
     *
     * Devices that report no serial fall back to their bus path in that key, so
     * their id still changes between plugs; nothing on such a device is stable
     * enough to do better without reading the volume.
     */
    fun stableDriveId(deviceKey: String?, startBlock: Long, taken: Collection<String>): String =
        driveIdFrom("${deviceKey ?: "unknown"}@$startBlock", taken)

    /**
     * The drive id for an unlocked VeraCrypt volume: [stableDriveId], except that a hidden
     * volume gets an id of its own.
     *
     * The outer and the hidden volume of one partition start at the same block, so they
     * shared an id, and with it every document id. A folder grant another app was given
     * on the outer volume, which Android keeps across unmounts, then opened the hidden
     * volume's files as soon as it was unlocked, with no new permission asked, and to
     * that app the two looked like the same drive. Review finding 1, 2026-11-09. The
     * outer and normal volumes keep the id they had, so existing grants on them still
     * work; a grant made on a hidden volume before this change has to be given again.
     */
    fun veraCryptDriveId(deviceKey: String?, startBlock: Long, volumeType: VeraCryptVolumeType?,
                         taken: Collection<String>, apfsVolume: Int = 0): String =
        when {
            volumeType == VeraCryptVolumeType.HIDDEN ->
                driveIdFrom("${deviceKey ?: "unknown"}@$startBlock#hidden", taken)
            // A later volume of an APFS container shares its partition's start block; the
            // first keeps the id it always had.
            apfsVolume > 0 -> driveIdFrom("${deviceKey ?: "unknown"}@$startBlock#apfs$apfsVolume", taken)
            else -> stableDriveId(deviceKey, startBlock, taken)
        }

    private fun driveIdFrom(key: String, taken: Collection<String>): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val base = digest.joinToString("") { "%02x".format(it) }.take(10)
        // The same partition is never mounted twice, but two serial-less devices
        // could in principle share a bus path across a replug race; never alias.
        var id = base
        var n = 2
        while (id in taken) id = "$base-${n++}"
        return id
    }

    /** "vid:pid:serial" (or "vid:pid:/dev/bus/usb/…") split into its parts. */
    data class DeviceKey(val vendorId: Int?, val productId: Int?, val serial: String?)

    fun parseDeviceKey(key: String?): DeviceKey {
        val parts = key?.split(":", limit = 3) ?: return DeviceKey(null, null, null)
        val serial = parts.getOrNull(2)?.takeUnless { it.startsWith("/") }
        return DeviceKey(parts.getOrNull(0)?.toIntOrNull(), parts.getOrNull(1)?.toIntOrNull(), serial)
    }
}

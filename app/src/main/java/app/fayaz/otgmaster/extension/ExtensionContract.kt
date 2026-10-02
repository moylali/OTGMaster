package app.fayaz.otgmaster.extension

import android.content.Context
import android.content.Intent
import app.fayaz.otgmaster.MountedDrive
import java.security.MessageDigest

/**
 * What OTG Master promises to companion apps (Backup Master today).
 *
 * A companion reaches a mounted volume the ordinary way — the user grants it a
 * folder through ACTION_OPEN_DOCUMENT_TREE — and everything below is layered on
 * that grant rather than beside it, so an app learns nothing about a drive the
 * user has not handed it:
 *
 * 1. **Stable drive ids.** The id inside every document id is derived from the
 *    USB identity and the partition's start, so a tree URI granted today still
 *    names the same partition after an unplug, a reboot or an app update. With
 *    the random ids used before, every persisted grant went stale at the next
 *    mount, which makes scheduled work against a drive impossible.
 * 2. **Volume columns.** Any document of a mounted drive answers the `COLUMN_*`
 *    names below when they are in the query's projection. Through a tree grant
 *    that is the only way an app can see them.
 * 3. **Mount events.** [ACTION_VOLUME_MOUNTED] / [ACTION_VOLUME_UNMOUNTED] are
 *    sent as explicit broadcasts to every package with a receiver for them. They
 *    carry only the drive id and its root document id.
 *
 * Bump [API_VERSION] when any of this changes shape.
 */
object ExtensionContract {
    const val API_VERSION = 1

    const val AUTHORITY = "app.fayaz.otgmaster.documents"

    const val ACTION_VOLUME_MOUNTED = "app.fayaz.otgmaster.action.VOLUME_MOUNTED"
    const val ACTION_VOLUME_UNMOUNTED = "app.fayaz.otgmaster.action.VOLUME_UNMOUNTED"
    const val EXTRA_DRIVE_ID = "app.fayaz.otgmaster.extra.DRIVE_ID"
    const val EXTRA_ROOT_DOCUMENT_ID = "app.fayaz.otgmaster.extra.ROOT_DOCUMENT_ID"
    const val EXTRA_API_VERSION = "app.fayaz.otgmaster.extra.API_VERSION"

    const val COLUMN_API_VERSION = "otgmaster_api_version"
    const val COLUMN_DRIVE_ID = "otgmaster_drive_id"
    const val COLUMN_DRIVE_NAME = "otgmaster_drive_name"
    const val COLUMN_DEVICE_NAME = "otgmaster_device_name"
    const val COLUMN_VENDOR_ID = "otgmaster_vendor_id"
    const val COLUMN_PRODUCT_ID = "otgmaster_product_id"
    const val COLUMN_SERIAL = "otgmaster_serial"
    const val COLUMN_PARTITION_LABEL = "otgmaster_partition_label"
    const val COLUMN_PARTITION_START = "otgmaster_partition_start"
    const val COLUMN_PARTITION_BLOCKS = "otgmaster_partition_blocks"
    const val COLUMN_CONTAINER = "otgmaster_container"
    const val COLUMN_FILESYSTEM = "otgmaster_filesystem"
    const val COLUMN_VOLUME_LABEL = "otgmaster_volume_label"
    const val COLUMN_READ_ONLY = "otgmaster_read_only"
    const val COLUMN_CAPACITY_BYTES = "otgmaster_capacity_bytes"
    /** Costs a full allocation-bitmap scan on exFAT; only computed when asked for. */
    const val COLUMN_FREE_BYTES = "otgmaster_free_bytes"

    val VOLUME_COLUMNS = arrayOf(
        COLUMN_API_VERSION, COLUMN_DRIVE_ID, COLUMN_DRIVE_NAME, COLUMN_DEVICE_NAME,
        COLUMN_VENDOR_ID, COLUMN_PRODUCT_ID, COLUMN_SERIAL, COLUMN_PARTITION_LABEL,
        COLUMN_PARTITION_START, COLUMN_PARTITION_BLOCKS, COLUMN_CONTAINER, COLUMN_FILESYSTEM,
        COLUMN_VOLUME_LABEL, COLUMN_READ_ONLY, COLUMN_CAPACITY_BYTES, COLUMN_FREE_BYTES,
    )

    /**
     * The drive id for a partition: 10 hex digits of SHA-256 over the device's
     * stable key (UsbDeviceDescriber.stableKey — "vid:pid:serial") and the
     * partition's first block.
     *
     * Devices that report no serial fall back to their bus path in that key, so
     * their id still changes between plugs; nothing on such a device is stable
     * enough to do better without reading the volume.
     */
    fun stableDriveId(deviceKey: String?, startBlock: Long, taken: Collection<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${deviceKey ?: "unknown"}@$startBlock".toByteArray())
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

    /** Values for [VOLUME_COLUMNS]; free space only when [wantFree]. */
    fun volumeValues(drive: MountedDrive, wantFree: Boolean): Map<String, Any?> {
        val key = parseDeviceKey(drive.sourceDeviceName)
        val fs = drive.fileSystem
        return mapOf(
            COLUMN_API_VERSION to API_VERSION,
            COLUMN_DRIVE_ID to drive.id,
            COLUMN_DRIVE_NAME to drive.name,
            COLUMN_DEVICE_NAME to drive.sourceDeviceDisplayName,
            COLUMN_VENDOR_ID to key.vendorId,
            COLUMN_PRODUCT_ID to key.productId,
            COLUMN_SERIAL to key.serial,
            COLUMN_PARTITION_LABEL to drive.partitionLabel,
            COLUMN_PARTITION_START to drive.partitionStartBlock,
            COLUMN_PARTITION_BLOCKS to drive.partitionBlockCount,
            COLUMN_CONTAINER to (if (drive.isPlain) "UNENCRYPTED"
                else drive.sourceVolumeCandidate?.containerType?.name),
            COLUMN_FILESYSTEM to drive.filesystemName,
            COLUMN_VOLUME_LABEL to runCatching { fs.volumeLabel }.getOrNull(),
            COLUMN_READ_ONLY to if (drive.isReadOnly) 1 else 0,
            COLUMN_CAPACITY_BYTES to runCatching { fs.capacity }.getOrNull(),
            COLUMN_FREE_BYTES to if (wantFree) runCatching { fs.freeSpace }.getOrNull() else null,
        )
    }

    /**
     * Tells every installed companion that [drive] came or went.
     *
     * Explicit per package: manifest receivers have not received implicit
     * broadcasts since Android 8. Package visibility for the lookup comes from
     * the <queries> entry in the manifest.
     */
    fun notifyCompanions(context: Context, action: String, drive: MountedDrive) {
        val probe = Intent(action)
        val packages = runCatching {
            context.packageManager.queryBroadcastReceivers(probe, 0)
                .map { it.activityInfo.packageName }.distinct()
        }.getOrDefault(emptyList())
        for (pkg in packages) {
            if (pkg == context.packageName) continue
            context.sendBroadcast(Intent(action).setPackage(pkg)
                .putExtra(EXTRA_DRIVE_ID, drive.id)
                .putExtra(EXTRA_ROOT_DOCUMENT_ID, "${drive.id}:/")
                .putExtra(EXTRA_API_VERSION, API_VERSION))
        }
    }
}

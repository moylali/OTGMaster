package app.fayaz.otgmaster.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import app.fayaz.otgmaster.OtgMasterState
import app.fayaz.otgmaster.extension.ExtensionContract
import app.fayaz.otgmaster.provider.VeraCryptDocumentProvider
import java.io.File

/**
 * Who and what is at each end of a backup: the phone, the storage volume, and
 * for drives mounted by OTG Master the USB device, partition, container and
 * filesystem.
 *
 * Recorded when a job is configured and again on every run, into the job and
 * into each snapshot, so a backup can always be traced back to the physical
 * drive and partition it lives on — and so a reminder can say which drive to
 * plug in.
 */
object VolumeInfo {
    const val OTG_AUTHORITY = app.fayaz.otgmaster.extension.ExtensionContract.AUTHORITY
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    // Keys used in the recorded maps. The OTG keys are the names the provider's
    // former otgmaster_* columns had, so maps recorded before 0.5.4 read the same.
    const val KIND = "kind"
    const val KIND_INTERNAL = "internal"
    const val KIND_OTG = "otg"
    const val KIND_OTHER = "other"
    const val URI = "uri"
    const val PATH = "path"
    const val PHONE = "phone"
    const val ANDROID = "android"
    const val CAPTURED_AT = "captured_at"
    const val FREE = "free_bytes"
    const val CAPACITY = "capacity_bytes"
    const val VOLUME = "volume"
    const val VOLUME_UUID = "volume_uuid"
    const val REMOVABLE = "removable"

    const val OTG_DRIVE_ID = "drive_id"
    const val OTG_DRIVE_NAME = "drive_name"
    const val OTG_DEVICE = "device_name"
    const val OTG_VENDOR = "vendor_id"
    const val OTG_PRODUCT = "product_id"
    const val OTG_SERIAL = "serial"
    const val OTG_PARTITION = "partition_label"
    const val OTG_PARTITION_START = "partition_start"
    const val OTG_PARTITION_BLOCKS = "partition_blocks"
    const val OTG_CONTAINER = "container"
    const val OTG_FILESYSTEM = "filesystem"
    const val OTG_VOLUME_LABEL = "volume_label"
    const val OTG_READ_ONLY = "read_only"

    fun kindOf(uri: Uri): String = when (uri.authority) {
        OTG_AUTHORITY -> KIND_OTG
        EXTERNAL_STORAGE_AUTHORITY -> KIND_INTERNAL
        else -> KIND_OTHER
    }

    /** A tree's folder inside its volume, "/" for the top: "primary:DCIM/Camera" and "id:/DCIM/Camera" give "/DCIM/Camera". */
    fun folderInside(treeDocumentId: String): String = "/" + treeDocumentId.substringAfter(':', "").trim('/')

    /** [folderInside] of the tree at [uri], followed by [subfolder]. Null if [uri] is not a tree. */
    fun folderInside(uri: Uri, subfolder: String = ""): String? =
        runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()?.let { joinFolder(folderInside(it), subfolder) }

    fun joinFolder(folder: String, subfolder: String): String =
        if (subfolder.isEmpty()) folder else folder.trimEnd('/') + "/" + subfolder.trim('/')

    /** "Internal storage/DCIM/Camera" or "drive-id:/Backups" style path for display. */
    fun displayPath(context: Context, uri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return uri.toString()
        return when (uri.authority) {
            EXTERNAL_STORAGE_AUTHORITY -> {
                val volume = docId.substringBefore(':')
                val path = docId.substringAfter(':', "")
                val name = if (volume == "primary") "Internal storage" else "SD card $volume"
                if (path.isEmpty()) name else "$name/$path"
            }
            OTG_AUTHORITY -> "/" + docId.substringAfter(':').trim('/')
            else -> docId
        }
    }

    /** OTG Master's drive id for a tree on one of its drives. */
    fun otgDriveId(uri: Uri): String? =
        if (uri.authority != OTG_AUTHORITY) null
        else runCatching { DocumentsContract.getTreeDocumentId(uri).substringBefore(':') }.getOrNull()

    /** True if the folder behind [uri] can be reached right now (drive mounted, grant valid). */
    fun isAvailable(context: Context, uri: Uri): Boolean = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)

    /** Captures everything known about the volume behind [uri] right now. */
    fun capture(context: Context, uri: Uri, withFreeSpace: Boolean = true): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out[KIND] = kindOf(uri)
        out[URI] = uri.toString()
        out[PATH] = displayPath(context, uri)
        out[PHONE] = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        out[ANDROID] = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        out[CAPTURED_AT] = System.currentTimeMillis().toString()
        when (uri.authority) {
            OTG_AUTHORITY -> captureOtg(context, uri, withFreeSpace, out)
            EXTERNAL_STORAGE_AUTHORITY -> captureExternal(context, uri, out)
        }
        return out
    }

    /**
     * Read in-process from the mounted drive, not through the provider: the provider
     * answers only the standard DocumentsContract columns, so other apps with a grant
     * on a drive cannot read its USB serial or partition layout (#165). Under the
     * provider's drive lock, as the provider query was, because free space scans the
     * allocation bitmap on exFAT.
     */
    private fun captureOtg(context: Context, uri: Uri, withFreeSpace: Boolean, out: MutableMap<String, String>) {
        val driveId = otgDriveId(uri) ?: return
        runCatching {
            VeraCryptDocumentProvider.onDrive("$driveId:/", "volume-info") {
                val drive = OtgMasterState.getDrive(driveId) ?: return@onDrive
                val key = ExtensionContract.parseDeviceKey(drive.sourceDeviceName)
                val fs = drive.fileSystem
                fun put(k: String, v: Any?) { if (v != null) out[k] = v.toString() }
                put(OTG_DRIVE_ID, drive.id)
                put(OTG_DRIVE_NAME, drive.name)
                put(OTG_DEVICE, drive.sourceDeviceDisplayName)
                put(OTG_VENDOR, key.vendorId)
                put(OTG_PRODUCT, key.productId)
                put(OTG_SERIAL, key.serial)
                put(OTG_PARTITION, drive.partitionLabel)
                put(OTG_PARTITION_START, drive.partitionStartBlock)
                put(OTG_PARTITION_BLOCKS, drive.partitionBlockCount)
                put(OTG_CONTAINER, if (drive.isPlain) "UNENCRYPTED" else drive.sourceVolumeCandidate?.containerType?.name)
                put(OTG_FILESYSTEM, drive.filesystemName)
                put(OTG_VOLUME_LABEL, runCatching { fs.volumeLabel }.getOrNull())
                put(OTG_READ_ONLY, if (drive.isReadOnly) 1 else 0)
                put(CAPACITY, runCatching { fs.capacity }.getOrNull())
                if (withFreeSpace) put(FREE, runCatching { fs.freeSpace }.getOrNull())
            }
        }
        out[VOLUME] = listOfNotNull(out[OTG_DEVICE], out[OTG_PARTITION]?.takeIf { it.isNotBlank() }).joinToString(" · ")
    }

    private fun captureExternal(context: Context, uri: Uri, out: MutableMap<String, String>) {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return
        val volumeId = docId.substringBefore(':')
        val sm = context.getSystemService(StorageManager::class.java)
        val volume = sm.storageVolumes.firstOrNull { v ->
            if (volumeId == "primary") v.isPrimary else v.uuid.equals(volumeId, ignoreCase = true)
        }
        if (volume != null) {
            out[VOLUME] = volume.getDescription(context)
            volume.uuid?.let { out[VOLUME_UUID] = it }
            out[REMOVABLE] = volume.isRemovable.toString()
        }
        val dir: File? = when {
            Build.VERSION.SDK_INT >= 30 && volume?.directory != null -> volume.directory
            volumeId == "primary" -> Environment.getExternalStorageDirectory()
            else -> File("/storage/$volumeId")
        }
        runCatching {
            val st = StatFs(dir!!.path)
            out[FREE] = st.availableBytes.toString()
            out[CAPACITY] = st.totalBytes.toString()
        }
    }

    /** A one-line description of the volume for lists and notifications. */
    fun summary(info: Map<String, String>): String = when (info[KIND]) {
        KIND_OTG -> buildString {
            append(info[OTG_DEVICE] ?: "USB drive")
            info[OTG_PARTITION]?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            val fs = listOfNotNull(info[OTG_CONTAINER]?.let(::containerName), info[OTG_FILESYSTEM]).joinToString(" ")
            if (fs.isNotBlank()) append(" ($fs)")
        }
        KIND_INTERNAL -> info[VOLUME] ?: "Internal storage"
        else -> info[PATH] ?: "Folder"
    }

    /** What the person calls the drive: the device, without its partition or filesystem. */
    fun driveName(info: Map<String, String>): String =
        if (info[KIND] == KIND_OTG) info[OTG_DEVICE] ?: "USB drive" else summary(info)

    fun containerName(c: String): String = when (c) {
        "VERACRYPT" -> "VeraCrypt"
        "LUKS1" -> "LUKS1"
        "LUKS2" -> "LUKS2"
        "BITLOCKER" -> "BitLocker"
        "UNENCRYPTED" -> "Plain"
        else -> c
    }

    /** Pretty labels for every recorded key, in display order. */
    val LABELS = linkedMapOf(
        KIND to "Type", PATH to "Folder", VOLUME to "Volume", OTG_DEVICE to "USB device",
        OTG_VENDOR to "Vendor ID", OTG_PRODUCT to "Product ID", OTG_SERIAL to "Serial number",
        OTG_PARTITION to "Partition", OTG_PARTITION_START to "Partition start (block)",
        OTG_PARTITION_BLOCKS to "Partition length (blocks)", OTG_CONTAINER to "Encryption",
        OTG_FILESYSTEM to "Filesystem", OTG_VOLUME_LABEL to "Volume label", OTG_READ_ONLY to "Read-only",
        OTG_DRIVE_ID to "OTG Master drive id", VOLUME_UUID to "Volume UUID", REMOVABLE to "Removable",
        CAPACITY to "Capacity", FREE to "Free", PHONE to "Phone", ANDROID to "Android", CAPTURED_AT to "Recorded",
    )
}

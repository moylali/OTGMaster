package app.fayaz.otgmaster.ntfs

/**
 * One directory entry as libntfs-3g reported it.
 *
 * [mref] is the MFT reference — record number in the low 48 bits, sequence number
 * in the high 16 — and is all the native side needs to find the file again. The
 * sequence number makes a stale reference fail rather than reach a reused record.
 */
class NtfsNode(
    val mref: Long,
    var name: String,
    val isDirectory: Boolean,
    var size: Long,
    val createdAt: Long,
    var lastModified: Long,
    val lastAccessed: Long,
)

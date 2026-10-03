package app.fayaz.otgmaster.apfs

/**
 * One directory entry as libfsapfs reported it.
 *
 * [id] is the APFS inode number (the root directory is 2), and is all the native
 * side needs to find the file again. Times are milliseconds since the epoch.
 */
class ApfsNode(
    val id: Long,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val createdAt: Long,
    val lastModified: Long,
    val lastAccessed: Long,
)

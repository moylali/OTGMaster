package app.fayaz.otgmaster.exfat

class ExFatNode(
    val nodePtr: Long,
    var name: String,
    val isDirectory: Boolean,
    var size: Long,
    val createdAt: Long,
    val lastModified: Long
)

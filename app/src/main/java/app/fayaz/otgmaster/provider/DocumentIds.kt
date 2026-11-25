package app.fayaz.otgmaster.provider

/**
 * The provider's document IDs, `<driveId>:<path>`, and the ancestry rules between them.
 *
 * Document IDs reach the provider from other apps, so they are untrusted. Before this,
 * the parser kept whatever path it was given and ancestry was a string-prefix test:
 * `<drive>:/Photos/../secret` counted as inside a grant on `<drive>:/Photos`. No driver
 * lists `.` or `..` entries, so path resolution then failed and nothing escaped, but the
 * provider answered `isChildDocument` wrongly for it, and its safety rested on every
 * filesystem driver hiding dot entries. A path is now canonical or refused, and ancestry
 * compares whole components (architecture proposal, F17 and Containment).
 *
 * The ID format itself is unchanged, so persisted grants keep resolving.
 */
object DocumentIds {

    /** A parsed, canonical document ID. [components] is empty for the root. */
    data class Id(val driveId: String, val components: List<String>) {
        val path: String get() = if (components.isEmpty()) "/" else components.joinToString("/", prefix = "/")
        val isRoot: Boolean get() = components.isEmpty()
        override fun toString(): String = "$driveId:$path"
    }

    /**
     * Parses [docId], or returns null if it is not a canonical ID: no drive, or a path
     * with an empty, `.` or `..` component, or a NUL. A trailing slash is tolerated, as
     * the old parser did, and an empty path means the root.
     */
    fun parse(docId: String?): Id? {
        if (docId == null) return null
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val driveId = docId.substring(0, colon)
        if (driveId.isBlank() || '/' in driveId || '\u0000' in driveId) return null
        var path = docId.substring(colon + 1)
        if ('\u0000' in path) return null
        if (path.isEmpty() || path == "/") return Id(driveId, emptyList())
        if (!path.startsWith("/")) path = "/$path"
        if (path.endsWith("/")) path = path.dropLast(1)
        val components = path.substring(1).split('/')
        if (components.any { it.isEmpty() || it == "." || it == ".." }) return null
        return Id(driveId, components)
    }

    /** The ID of the child named [name] under [parent]. [name] must already be a valid name. */
    fun child(parent: Id, name: String): Id = Id(parent.driveId, parent.components + name)

    /** True if [child] is [parent] or lies under it, on the same drive, by whole components. */
    fun isSameOrUnder(parent: Id, child: Id): Boolean =
        parent.driveId == child.driveId &&
            child.components.size >= parent.components.size &&
            child.components.subList(0, parent.components.size) == parent.components

    /** The IDs from the drive's root down to [child], both included. */
    fun chain(child: Id): List<Id> =
        (0..child.components.size).map { Id(child.driveId, child.components.subList(0, it)) }
}

/**
 * Display names a client may give in create and rename.
 *
 * Before this, both passed the name straight to the filesystem driver. A name holding a
 * `/` then made an entry no path can address (on ext4 a directory entry with a slash in
 * it, which e2fsck reports), and `.` or `..` collided with the directory's own entries.
 * The driver still applies its own rules (FAT's reserved characters, lengths); this is
 * the part every backend shares (architecture proposal, F6).
 */
object DocumentNames {
    /** Why [name] cannot be used, or null if it can. */
    fun problem(name: String?): String? = when {
        name == null -> "a name is required"
        name.isEmpty() -> "the name is empty"
        name == "." || name == ".." -> "\"$name\" is reserved"
        '/' in name -> "a name cannot contain \"/\""
        '\u0000' in name -> "a name cannot contain a NUL character"
        else -> null
    }

    /** [name], or an IllegalArgumentException saying why it cannot be used. */
    fun require(name: String?): String {
        problem(name)?.let { throw IllegalArgumentException("Invalid name: $it") }
        return name!!
    }
}

package app.fayaz.otgmaster.core

/**
 * A mounted tree as the document provider sees it (architecture proposal, "Contracts
 * between layers": `DocumentSource`).
 *
 * This package has no Android or libaums imports: it is what moves into the shared
 * `:mount-core` module in Milestone 2. A filesystem on a USB drive implements it through
 * [app.fayaz.otgmaster.disk.UsbFileDocumentSource]; an archive reader (ZIP, ISO) will
 * implement it over its own index, so the provider serves both the same way.
 *
 * Names here are single entries, already checked (see the provider's DocumentNames);
 * lookups match names exactly, as document IDs do.
 */
interface DocumentSource {

    /** Why the tree refuses changes, or null if it accepts them. */
    val readOnlyReason: String?

    /** The root folder. */
    fun root(): DocumentNode

    /** The entry called exactly [name] directly in [dir], or null if there is none. */
    fun child(dir: DocumentNode, name: String): DocumentNode?

    /** The entries directly in [dir]. */
    fun children(dir: DocumentNode): List<DocumentNode>

    fun createFile(dir: DocumentNode, name: String): DocumentNode
    fun createDirectory(dir: DocumentNode, name: String): DocumentNode
    fun rename(node: DocumentNode, name: String)
    fun delete(node: DocumentNode)
}

/** One entry of a [DocumentSource]. Implementations carry their own backend handle. */
interface DocumentNode {
    val name: String
    val isDirectory: Boolean
    val isRoot: Boolean
    /** Bytes for a file; 0 for a folder or when the size cannot be read. */
    val size: Long
    /** Milliseconds since the epoch; 0 when unknown. */
    val lastModified: Long
}

/** The node at [components] below the root, matched exactly at each level, or null. */
fun DocumentSource.resolve(components: List<String>): DocumentNode? {
    var node = root()
    for (name in components) {
        if (!node.isDirectory) return null
        node = child(node, name) ?: return null
    }
    return node
}

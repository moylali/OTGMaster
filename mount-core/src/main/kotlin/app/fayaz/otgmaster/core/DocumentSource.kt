package app.fayaz.otgmaster.core

/**
 * A mounted tree as the document provider sees it (architecture proposal, "Contracts
 * between layers": `DocumentSource`).
 *
 * It lives in `:mount-core`, which has no Android or libaums imports, so both apps can
 * share it. A filesystem on a USB drive implements it through
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

    /**
     * A handle on the file [node], for a client's reads and writes. Opening does no I/O of
     * its own; a source that cannot write still opens, and refuses in [DocumentHandle.write].
     */
    fun open(node: DocumentNode): DocumentHandle

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

/**
 * An open file of a [DocumentSource] (architecture proposal, "Open file handle"). Offsets are
 * bytes from the start of the file; calls are not thread-safe, and the provider serialises them.
 */
interface DocumentHandle : java.io.Closeable {
    /** The file's length in bytes now. */
    fun size(): Long

    /**
     * Reads at most [length] bytes at [offset] into [buffer] from index 0 and returns how many
     * it actually read: fewer near the end of the file, 0 at or past it. The count is the only
     * part of [buffer] that holds file content; the rest is left as it was.
     */
    fun read(offset: Long, buffer: ByteArray, length: Int): Int

    /** Writes the first [length] bytes of [buffer] at [offset], growing the file if needed. */
    fun write(offset: Long, buffer: ByteArray, length: Int)

    /** Cuts the file to 0 bytes. */
    fun truncate()

    /** Makes the writes so far durable on the volume. */
    fun flush()

    /** Closes the file, writing what the filesystem keeps for it (its size, its times). */
    override fun close()
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

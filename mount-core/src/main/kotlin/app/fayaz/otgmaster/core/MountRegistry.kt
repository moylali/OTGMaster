package app.fayaz.otgmaster.core

/**
 * A mounted tree as a document provider publishes it (architecture proposal, "MountSession
 * and registry"). Each app supplies its own [MountRegistry]; the provider asks it for every
 * call instead of reaching into the app's state.
 */
interface MountedTree {
    /** The public ID: the part of every document ID before the colon, and of the root ID. */
    val id: String

    /** The root's title. */
    val name: String

    /** Why the tree refuses changes, or null if it accepts them. */
    val readOnlyReason: String?

    val tree: DocumentSource

    /**
     * This mount's identity, compared with `===`. A new mount under the same [id] (after an
     * unmount, Format or Encrypt) has a different one, so a handle or a cached row from the
     * old mount is never taken for the new.
     */
    val session: Any

    /**
     * True if reading this tree reads other trees of the same provider (a backup snapshot
     * whose data lives in a repository on another drive), so its descriptors must not be
     * served on a thread those reads wait on.
     */
    val readsThroughOtherTrees: Boolean get() = false

    /** Free bytes on the volume; may read it, so never called under the tree's lock. */
    fun freeBytes(): Long
}

interface MountRegistry {
    /** The mounted trees, in the order their roots are listed. */
    fun mounted(): List<MountedTree>

    fun find(id: String): MountedTree? = mounted().firstOrNull { it.id == id }
}

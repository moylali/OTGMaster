package app.fayaz.otgmaster.provider

import app.fayaz.otgmaster.MountedDrive
import app.fayaz.otgmaster.OtgMasterState
import app.fayaz.otgmaster.core.DocumentSource
import app.fayaz.otgmaster.core.MountRegistry
import app.fayaz.otgmaster.core.MountedTree
import app.fayaz.otgmaster.disk.UsbFileDocumentSource

/**
 * OTG Master's mounted drives as the provider's [MountRegistry]: a view of
 * [OtgMasterState.mountedDrives], read afresh on every call. A drive's session is its
 * mounted filesystem object, which is what the provider compared before the registry
 * existed (`System.identityHashCode(drive.fileSystem)`, `===`), and what Format and Encrypt
 * pass to [VeraCryptDocumentProvider.retire].
 */
object OtgMounts : MountRegistry {

    private class Drive(val drive: MountedDrive) : MountedTree {
        override val id: String get() = drive.id
        override val name: String get() = drive.name
        override val readOnlyReason: String? get() = drive.readOnlyReason
        override val tree: DocumentSource get() = UsbFileDocumentSource(drive.fileSystem) { drive.readOnlyReason }
        override val session: Any get() = drive.fileSystem
        override val readsThroughOtherTrees: Boolean
            get() = drive.fileSystem is app.fayaz.otgmaster.backup.restic.ResticFileSystem
        override fun freeBytes(): Long = drive.fileSystem.freeSpace
    }

    override fun mounted(): List<MountedTree> = OtgMasterState.mountedDrives.map(::Drive)

    override fun find(id: String): MountedTree? = OtgMasterState.getDrive(id)?.let(::Drive)
}

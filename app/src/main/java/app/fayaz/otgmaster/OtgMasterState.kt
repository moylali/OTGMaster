package app.fayaz.otgmaster

import me.jahnen.libaums.core.fs.FileSystem
import app.fayaz.otgmaster.block.RawBlockDevice
import java.util.concurrent.CopyOnWriteArrayList

data class MountedDrive(
    val id: String,
    val name: String,
    val fileSystem: FileSystem,
    val blockDevice: RawBlockDevice?,
    /** Stable USB device identity (see UsbDeviceDescriber.stableKey) this drive was unlocked
     * from, used to avoid re-probing/re-mounting the same physical device while it's mounted. */
    val sourceDeviceName: String? = null,
    /** Human-readable USB device name (e.g. "Kingston DataTraveler") for display purposes. */
    val sourceDeviceDisplayName: String? = null
)

object OtgMasterState {
    val mountedDrives = CopyOnWriteArrayList<MountedDrive>()
    
    fun getDrive(id: String): MountedDrive? {
        return mountedDrives.find { it.id == id }
    }
    
    fun addDrive(drive: MountedDrive) {
        mountedDrives.add(drive)
    }
    
    fun removeDrive(id: String) {
        mountedDrives.removeIf { it.id == id }
    }
}

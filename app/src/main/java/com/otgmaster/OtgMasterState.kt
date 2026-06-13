package com.otgmaster

import me.jahnen.libaums.core.fs.FileSystem
import com.otgmaster.block.RawBlockDevice
import java.util.concurrent.CopyOnWriteArrayList

data class MountedDrive(
    val id: String,
    val name: String,
    val fileSystem: FileSystem,
    val blockDevice: RawBlockDevice?
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

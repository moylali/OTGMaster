package app.fayaz.otgmaster.disk

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.FileSystemFactory
import me.jahnen.libaums.core.partition.PartitionTableEntry

/**
 * Mounts the filesystem on a byte device with the engine's drivers (architecture proposal, F3).
 *
 * The first step is mechanical: the same libaums [FileSystemFactory] dispatch as before, in
 * the same order (libaums' FAT32 and exFAT at priority 1, then ext2/3/4, NTFS and APFS at 2),
 * but registered here, once per process, instead of by MainActivity. That had two costs:
 * nothing could mount without OTG Master's activity (Container Master has none of it), and
 * libaums keeps every registration, so each MainActivity.onCreate (a rotation, a theme
 * change, the activity coming back after Android destroyed it) added four more creators. A
 * volume no driver accepted was then probed again by every duplicate, and a driver that
 * failed logged its failure once per copy.
 *
 * APFS with a password does not come through here: it needs the volume's password, and
 * MainActivity calls ApfsFileSystemCreator.mount directly, as before.
 */
object DiskMounter {

    private var registered = false

    /** The creators this object has registered with libaums: 4, however often it is asked. */
    @Volatile
    internal var registrations = 0
        private set

    /** Registers the engine's drivers with libaums, the first time only. */
    @Synchronized
    fun ensureRegistered() {
        if (registered) return
        FileSystemFactory.registerFileSystem(app.fayaz.otgmaster.exfat.ExFatFileSystemCreator(), 1)
        FileSystemFactory.registerFileSystem(app.fayaz.otgmaster.ext4.Ext4FileSystemCreator(), 2)
        FileSystemFactory.registerFileSystem(app.fayaz.otgmaster.ntfs.NtfsFileSystemCreator(), 2)
        FileSystemFactory.registerFileSystem(app.fayaz.otgmaster.apfs.ApfsFileSystemCreator(), 2)
        registrations += 4
        registered = true
    }

    /**
     * The filesystem on [device], from the first driver that accepts it.
     *
     * @throws FileSystemFactory.UnsupportedFileSystemException if none does
     */
    fun mount(device: BlockDeviceDriver): FileSystem {
        ensureRegistered()
        return FileSystemFactory.createFileSystem(PartitionTableEntry(0, 0, 0), device)
    }
}

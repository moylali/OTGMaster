package app.fayaz.otgmaster.block

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.IOException
import java.nio.ByteBuffer

/**
 * The device a partition mounted read-only is handed to its filesystem through.
 *
 * Two jobs. It refuses every write, so nothing reaches the drive whatever the
 * filesystem code does — FAT32 (libaums) has no read-only mode of its own, so for
 * it this is the whole guarantee. And it marks the mount as read-only for the
 * drivers that do have a mode: FileSystemFactory passes no options, so the exFAT
 * and ext4 creators check for this type and mount read-only themselves, which
 * stops them attempting the housekeeping writes (exFAT's volume-dirty flag) that
 * would otherwise fail the mount.
 */
class ReadOnlyBlockDeviceDriver(private val inner: BlockDeviceDriver) : BlockDeviceDriver {
    override val blockSize: Int get() = inner.blockSize
    override val blocks: Long get() = inner.blocks

    override fun init() = inner.init()

    override fun read(deviceOffset: Long, buffer: ByteBuffer) = inner.read(deviceOffset, buffer)

    override fun write(deviceOffset: Long, buffer: ByteBuffer) {
        throw IOException("volume is mounted read-only")
    }
}

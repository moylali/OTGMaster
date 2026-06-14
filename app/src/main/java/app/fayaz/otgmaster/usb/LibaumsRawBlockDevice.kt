package app.fayaz.otgmaster.usb

import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.usb.UsbCommunication
import java.nio.ByteBuffer

class LibaumsRawBlockDevice(
    private val driver: BlockDeviceDriver,
    private val communication: UsbCommunication,
) : RawBlockDevice {
    override val blockSize: Int = driver.blockSize
    override val blockCount: Long = driver.blocks

    override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
        require(blockCount >= 0) { "blockCount must be non-negative" }
        val buffer = ByteBuffer.allocate(blockCount * blockSize)
        driver.read(startBlock, buffer)
        return buffer.array()
    }

    override fun writeBlocks(startBlock: Long, data: ByteArray) {
        require(data.size % blockSize == 0) { "Write length must be block aligned" }
        driver.write(startBlock, ByteBuffer.wrap(data))
    }

    override fun close() {
        communication.close()
    }
}

package app.fayaz.otgmaster.block

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.nio.ByteBuffer

/**
 * Bridges [RawBlockDevice] to libaums [BlockDeviceDriver] so that
 * [me.jahnen.libaums.core.driver.ByteBlockDevice] can wrap it for [me.jahnen.libaums.core.fs.FileSystemFactory].
 * Used to mount unencrypted partitions directly without going through VeraCrypt decryption.
 */
class RawBlockDeviceAdapter(private val delegate: RawBlockDevice) : RawBlockDevice, BlockDeviceDriver {
    override val blockSize: Int get() = delegate.blockSize
    override val blockCount: Long get() = delegate.blockCount
    override val blocks: Long get() = delegate.blockCount

    override fun init() {}

    override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray =
        delegate.readBlocks(startBlock, blockCount)

    override fun writeBlocks(startBlock: Long, data: ByteArray) =
        delegate.writeBlocks(startBlock, data)

    override fun read(deviceOffset: Long, buffer: ByteBuffer) {
        val bytesToRead = buffer.remaining()
        require(bytesToRead % blockSize == 0) { "buffer size must be block-aligned" }
        val data = delegate.readBlocks(deviceOffset, bytesToRead / blockSize)
        buffer.put(data)
    }

    override fun write(deviceOffset: Long, buffer: ByteBuffer) {
        val bytesToWrite = buffer.remaining()
        require(bytesToWrite % blockSize == 0) { "buffer size must be block-aligned" }
        val data = ByteArray(bytesToWrite)
        buffer.get(data)
        delegate.writeBlocks(deviceOffset, data)
    }

    override fun close() = delegate.close()
}

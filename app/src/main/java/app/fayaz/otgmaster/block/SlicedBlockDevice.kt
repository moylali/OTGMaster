package app.fayaz.otgmaster.block

class SlicedBlockDevice(
    private val delegate: RawBlockDevice,
    private val firstBlock: Long,
    override val blockCount: Long,
) : RawBlockDevice {
    override val blockSize: Int = delegate.blockSize

    override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
        require(startBlock >= 0) { "startBlock must be non-negative" }
        require(blockCount >= 0) { "blockCount must be non-negative" }
        require(startBlock + blockCount <= this.blockCount) { "Read exceeds slice bounds" }
        return delegate.readBlocks(firstBlock + startBlock, blockCount)
    }

    override fun writeBlocks(startBlock: Long, data: ByteArray) {
        require(startBlock >= 0) { "startBlock must be non-negative" }
        require(startBlock + (data.size / blockSize) <= this.blockCount) { "Write exceeds slice bounds" }
        delegate.writeBlocks(firstBlock + startBlock, data)
    }

    override fun close() = delegate.close()
}

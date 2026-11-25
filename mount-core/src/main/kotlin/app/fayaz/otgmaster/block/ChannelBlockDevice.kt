package app.fayaz.otgmaster.block

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * A read-only [RawBlockDevice] over a seekable file: the container a user picked in Container
 * Master, read through its descriptor (architecture proposal, F7). Only whole [blockSize]
 * blocks are exposed, so a file whose length is not a multiple of it loses its tail, which no
 * supported format uses.
 *
 * Reads are positional ([FileChannel.read] with a position), so callers on several threads do
 * not share a file offset, and each loops over short transfers, which a descriptor from another
 * provider can return. Reaching the end of the file inside a block is an [EOFException], never
 * a short block: the file shrank under the reader, or its length was wrong.
 */
class ChannelBlockDevice(
    private val channel: FileChannel,
    /** The file's length in bytes, as the opener measured it; null to ask the channel. */
    length: Long? = null,
    override val blockSize: Int = 512,
) : RawBlockDevice {

    override val blockCount: Long = (length ?: channel.size()).also {
        require(it >= 0) { "negative length $it" }
    } / blockSize

    override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
        requireInRange(startBlock, blockCount.toLong(), "read")
        val out = ByteArray(Math.multiplyExact(blockCount, blockSize))
        val buffer = ByteBuffer.wrap(out)
        var position = Math.multiplyExact(startBlock, blockSize.toLong())
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, position)
            if (n < 0) throw EOFException(
                "the file ended at byte $position, inside the ${blockCount}-block read at block $startBlock")
            position += n
        }
        return out
    }

    override fun writeBlocks(startBlock: Long, data: ByteArray) {
        throw IOException("This container is open read-only")
    }

    override fun close() = channel.close()
}

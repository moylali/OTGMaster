package app.fayaz.otgmaster.block

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * A [RawBlockDevice] over a seekable file: the container a user picked in Container Master,
 * read through its descriptor (architecture proposal, F7), and written through [writeChannel]
 * when it was opened for writing, read-only otherwise. Only whole [blockSize]
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
    /**
     * The same file open for writing ("rw"), or null for a read-only device. A separate channel
     * because a descriptor's FileInputStream channel cannot write and its FileOutputStream
     * channel cannot read; both are positional, so neither moves the other's offset.
     */
    private val writeChannel: FileChannel? = null,
) : RawBlockDevice {

    val writable: Boolean get() = writeChannel != null

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
        val out = writeChannel ?: throw IOException("This container is open read-only")
        require(data.size % blockSize == 0) { "a write of ${data.size} bytes is not whole $blockSize-byte blocks" }
        requireInRange(startBlock, (data.size / blockSize).toLong(), "write")
        val buffer = ByteBuffer.wrap(data)
        var position = Math.multiplyExact(startBlock, blockSize.toLong())
        // Positional, and looped: a descriptor from another provider can take part of a buffer.
        while (buffer.hasRemaining()) position += out.write(buffer, position)
    }

    /** Puts what was written on the storage under the file, so a lock leaves nothing in memory. */
    fun force() { writeChannel?.force(true) }

    override fun close() {
        try {
            force()
        } finally {
            try { writeChannel?.close() } finally { channel.close() }
        }
    }
}

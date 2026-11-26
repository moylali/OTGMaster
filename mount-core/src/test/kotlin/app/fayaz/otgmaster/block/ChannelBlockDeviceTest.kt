package app.fayaz.otgmaster.block

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel

class ChannelBlockDeviceTest {

    private fun file(bytes: Int): Pair<File, ByteArray> {
        val data = ByteArray(bytes) { (it * 7 + it / 512).toByte() }
        val f = File.createTempFile("chan", ".img").apply { deleteOnExit(); writeBytes(data) }
        return f to data
    }

    @Test
    fun `blocks read back by position, the last whole block included, and the tail is not exposed`() {
        val (f, data) = file(512 * 9 + 100)
        RandomAccessFile(f, "r").use { raf ->
            val dev = ChannelBlockDevice(raf.channel)
            assertEquals(9L, dev.blockCount)
            assertArrayEquals(data.copyOfRange(512 * 3, 512 * 5), dev.readBlocks(3, 2))
            assertArrayEquals(data.copyOfRange(512 * 8, 512 * 9), dev.readBlocks(8, 1))
            assertThrows(IllegalArgumentException::class.java) { dev.readBlocks(8, 2) }
            assertThrows(IllegalArgumentException::class.java) { dev.readBlocks(9, 1) }
            assertThrows(IOException::class.java) { dev.writeBlocks(0, ByteArray(512)) }
            assertArrayEquals("a refused write changed the file", data, f.readBytes())
        }
    }

    /** A channel that hands back at most 100 bytes per call, as a pipe-backed descriptor can. */
    private class Trickle(private val inner: FileChannel) : FileChannel() {
        override fun read(dst: ByteBuffer, position: Long): Int {
            val n = minOf(100, dst.remaining())
            val slice = dst.slice().limit(n) as ByteBuffer
            val got = inner.read(slice, position)
            if (got > 0) dst.position(dst.position() + got)
            return got
        }
        override fun size() = inner.size()
        override fun implCloseChannel() = inner.close()
        override fun read(dst: ByteBuffer) = throw UnsupportedOperationException()
        override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int) = throw UnsupportedOperationException()
        override fun write(src: ByteBuffer) = throw UnsupportedOperationException()
        override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int) = throw UnsupportedOperationException()
        override fun write(src: ByteBuffer, position: Long): Int {
            val n = minOf(100, src.remaining())
            val slice = src.slice().limit(n) as ByteBuffer
            val put = inner.write(slice, position)
            src.position(src.position() + put)
            return put
        }
        override fun position() = throw UnsupportedOperationException()
        override fun position(newPosition: Long) = throw UnsupportedOperationException()
        override fun truncate(size: Long) = throw UnsupportedOperationException()
        var forced = 0
        override fun force(metaData: Boolean) { forced++; inner.force(metaData) }
        override fun transferTo(position: Long, count: Long, target: WritableByteChannel) = throw UnsupportedOperationException()
        override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long) = throw UnsupportedOperationException()
        override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = throw UnsupportedOperationException()
        override fun lock(position: Long, size: Long, shared: Boolean): FileLock = throw UnsupportedOperationException()
        override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock = throw UnsupportedOperationException()
    }

    @Test
    fun `short transfers are looped over`() {
        val (f, data) = file(512 * 4)
        RandomAccessFile(f, "r").use { raf ->
            val dev = ChannelBlockDevice(Trickle(raf.channel))
            assertArrayEquals(data, dev.readBlocks(0, 4))
        }
    }

    @Test
    fun `a file that ends inside a block is an EOFException, not a short block`() {
        val (f, _) = file(512 * 4)
        RandomAccessFile(f, "r").use { raf ->
            // The opener measured 4 blocks; the file then lost half of its last one.
            val dev = ChannelBlockDevice(raf.channel, length = 512L * 4)
            RandomAccessFile(f, "rw").use { it.setLength(512L * 3 + 256) }
            assertThrows(EOFException::class.java) { dev.readBlocks(3, 1) }
        }
    }

    @Test
    fun `a writable device writes whole blocks in place, and the file keeps its length`() {
        val (f, data) = file(512 * 8)
        RandomAccessFile(f, "r").use { r -> RandomAccessFile(f, "rw").use { w ->
            val dev = ChannelBlockDevice(r.channel, writeChannel = w.channel)
            val two = ByteArray(1024) { (0xA0 + it % 7).toByte() }
            dev.writeBlocks(5, two)
            assertArrayEquals(two, dev.readBlocks(5, 2))
            val want = data.copyOf().also { System.arraycopy(two, 0, it, 512 * 5, 1024) }
            assertArrayEquals(want, f.readBytes())
            assertThrows(IllegalArgumentException::class.java) { dev.writeBlocks(7, ByteArray(1024)) }
            assertThrows(IllegalArgumentException::class.java) { dev.writeBlocks(0, ByteArray(100)) }
            assertArrayEquals("a refused write changed the file", want, f.readBytes())
            assertEquals(512L * 8, f.length())
        } }
    }

    @Test
    fun `short writes are looped over, and close forces the writes to storage`() {
        val (f, data) = file(512 * 4)
        RandomAccessFile(f, "r").use { r -> RandomAccessFile(f, "rw").use { w ->
            val out = Trickle(w.channel)
            val dev = ChannelBlockDevice(r.channel, writeChannel = out)
            val block = ByteArray(512) { 0x5A }
            dev.writeBlocks(2, block)
            val want = data.copyOf().also { System.arraycopy(block, 0, it, 1024, 512) }
            assertArrayEquals(want, f.readBytes())
            dev.close()
            assertEquals(1, out.forced)
        } }
    }
}

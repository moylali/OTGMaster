package app.fayaz.otgmaster.usb

import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import me.jahnen.libaums.core.driver.scsi.ScsiBlockDevice
import me.jahnen.libaums.core.usb.UsbCommunication
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A transfer that fails part-way must be retried from the start of the caller's
 * buffer, not from wherever the failed attempt left the position.
 *
 * Observed on a Samsung M30 (VeraCrypt + FAT32, a03da36): a read returned -1
 * after a partial transfer, the retry got "wrong csw tag!", and the next retry
 * threw IllegalArgumentException from ByteBuffer.limit() inside
 * transferOneCommand, which takes `inBuffer.position()` as each attempt's start.
 * That throw is the lucky outcome. LibaumsRawBlockDevice passes each chunk as
 * `ByteBuffer.wrap(array, offset, length)`, whose capacity is the whole array, so
 * for any chunk but the last the bad limit usually fits and the retry proceeds
 * from the wrong offset — reads land shifted, writes send the wrong bytes to the
 * device, and nothing reports an error.
 *
 * The fake reader below delivers half of one data phase, then fails the transfer
 * the way JellyBeanMr2Communication does on result == -1: position already
 * advanced by the partial count, then an IOException.
 */
class ScsiRetryPositionTest {

    private class FlakyReader(blocks: Int) : UsbCommunication {
        val store = ByteArray(blocks * BLOCK) { (it * 31 + it / BLOCK).toByte() }
        /** Data phases to cut short, one each, before behaving. */
        var faults = 0

        private val pending = ArrayDeque<ByteArray>()
        private var writeLba = -1
        private var writeRemaining = 0
        private var writeTag = 0
        private var failNextTransfer = false

        override val inEndpoint: UsbEndpoint get() = throw UnsupportedOperationException()
        override val outEndpoint: UsbEndpoint get() = throw UnsupportedOperationException()
        override val usbInterface: UsbInterface get() = throw UnsupportedOperationException()

        private fun failAndReset(): Nothing {
            // What a real reset leaves behind: no half-delivered data, no pending CSW.
            failNextTransfer = false
            pending.clear()
            writeRemaining = 0
            throw IOException("Could not read from device, result == -1 errno 0 null")
        }

        override fun bulkOutTransfer(src: ByteBuffer): Int {
            if (failNextTransfer) failAndReset()
            if (writeRemaining > 0) {
                // Data phase of a WRITE(10).
                val n = if (faults > 0) {
                    faults--; failNextTransfer = true
                    minOf(src.remaining(), writeRemaining) / 2
                } else minOf(src.remaining(), writeRemaining)
                val offset = writeLba * BLOCK + (writeLengthTotal - writeRemaining)
                src.get(store, offset, n)
                writeRemaining -= n
                if (writeRemaining == 0) pending += csw(writeTag, 0)
                return n
            }
            val n = src.remaining()
            val cbw = ByteArray(n).also { src.get(it) }
            val tag = ByteBuffer.wrap(cbw, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val length = ByteBuffer.wrap(cbw, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val lba = ByteBuffer.wrap(cbw, 17, 4).order(ByteOrder.BIG_ENDIAN).int
            when (cbw[15].toInt() and 0xFF) {
                0x12 -> { pending += ByteArray(36); pending += csw(tag, 0) }   // INQUIRY
                0x00 -> pending += csw(tag, 0)                                  // TEST UNIT READY
                0x25 -> {                                                       // READ CAPACITY(10)
                    val cap = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                    cap.putInt(store.size / BLOCK - 1); cap.putInt(BLOCK)
                    pending += cap.array(); pending += csw(tag, 0)
                }
                0x28 -> {                                                       // READ(10)
                    val data = store.copyOfRange(lba * BLOCK, lba * BLOCK + length)
                    if (faults > 0) {
                        faults--
                        pending += data.copyOfRange(0, length / 2)
                        pending += FAIL
                    } else {
                        pending += data; pending += csw(tag, 0)
                    }
                }
                0x2A -> {                                                       // WRITE(10)
                    writeLba = lba; writeRemaining = length; writeLengthTotal = length; writeTag = tag
                }
                else -> pending += csw(tag, 1)
            }
            return n
        }

        private var writeLengthTotal = 0

        override fun bulkInTransfer(dest: ByteBuffer): Int {
            if (failNextTransfer) failAndReset()
            val chunk = pending.removeFirstOrNull() ?: return 0
            if (chunk === FAIL) failAndReset()
            val n = minOf(dest.remaining(), chunk.size)
            dest.put(chunk, 0, n)
            if (n < chunk.size) pending.addFirst(chunk.copyOfRange(n, chunk.size))
            return n
        }

        private fun csw(tag: Int, status: Int): ByteArray =
            ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x53425355).putInt(tag).putInt(0).put(status.toByte()).array()

        override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int,
                                     buffer: ByteArray, length: Int): Int = 0
        override fun resetDevice() {}
        override fun clearFeatureHalt(endpoint: UsbEndpoint) {}
        override fun close() {}

        companion object {
            private val FAIL = ByteArray(0)
        }
    }

    private fun device(reader: FlakyReader) = ScsiBlockDevice(reader, 0).also { it.init() }

    @Test
    fun aReadCutShortIsRetriedIntoTheRightPlace_exactBuffer() {
        // The shape that failed on the Samsung: the buffer is exactly the transfer.
        val reader = FlakyReader(blocks = 64)
        val dev = device(reader)
        reader.faults = 1
        val out = ByteBuffer.allocate(8 * BLOCK)
        dev.read(4, out)
        assertArrayEquals(reader.store.copyOfRange(4 * BLOCK, 12 * BLOCK), out.array())
    }

    @Test
    fun aReadCutShortIsRetriedIntoTheRightPlace_chunkOfALargerArray() {
        // The shape LibaumsRawBlockDevice uses for every chunk but the last: capacity
        // is the whole array, so a wrong limit does not throw.
        val reader = FlakyReader(blocks = 64)
        val dev = device(reader)
        reader.faults = 1
        val array = ByteArray(16 * BLOCK)
        dev.read(4, ByteBuffer.wrap(array, 0, 8 * BLOCK))
        assertArrayEquals(reader.store.copyOfRange(4 * BLOCK, 12 * BLOCK),
            array.copyOfRange(0, 8 * BLOCK))
    }

    @Test
    fun aWriteCutShortIsResentFromTheStart_chunkOfALargerArray() {
        val reader = FlakyReader(blocks = 64)
        val dev = device(reader)
        val before = reader.store.copyOf()
        // Two chunks' worth, written as the first chunk only. Must not repeat within
        // the chunk: a pattern with a 256-byte period made the bytes a misplaced
        // retry sends identical to the right ones, and upstream passed.
        val data = ByteArray(16 * BLOCK).also { java.util.Random(42).nextBytes(it) }
        reader.faults = 1
        dev.write(20, ByteBuffer.wrap(data, 0, 8 * BLOCK))
        assertArrayEquals("the chunk written is the chunk given",
            data.copyOfRange(0, 8 * BLOCK), reader.store.copyOfRange(20 * BLOCK, 28 * BLOCK))
        assertArrayEquals("nothing outside the chunk changed",
            before.copyOfRange(28 * BLOCK, before.size), reader.store.copyOfRange(28 * BLOCK, before.size))
        assertArrayEquals(before.copyOfRange(0, 20 * BLOCK), reader.store.copyOfRange(0, 20 * BLOCK))
    }

    @Test
    fun aTransferWithNoFaultIsUnchanged() {
        val reader = FlakyReader(blocks = 64)
        val dev = device(reader)
        val out = ByteBuffer.allocate(8 * BLOCK)
        dev.read(0, out)
        assertEquals(out.limit(), out.position())
        assertArrayEquals(reader.store.copyOfRange(0, 8 * BLOCK), out.array())
    }

    private companion object {
        const val BLOCK = 512
    }
}

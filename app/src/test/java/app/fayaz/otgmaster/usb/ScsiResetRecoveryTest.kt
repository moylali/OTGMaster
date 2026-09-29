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
 * After a transfer fails part-way, the device must be resynchronised before the
 * command is retried.
 *
 * Observed on a Samsung M30 (VeraCrypt + exFAT, a192b12): a read returned -1, and
 * every retry after it failed with "wrong csw tag!" until MAX_RECOVERY_ATTEMPTS —
 * then the next command did the same, for the rest of the run. The device still
 * held the remainder of the failed command's data and its CSW; each retry read
 * the stale status, and nothing ever sent Reset Recovery, which BOT 1.0 §5.3.4
 * requires after an invalid CSW. libaums only reset on a pipe or phase error.
 *
 * Unlike the fake in ScsiRetryPositionTest, this one keeps a failed command's
 * leftovers queued until it receives the class-specific Bulk-Only Mass Storage
 * Reset (request 0xFF), which is how a real device behaves.
 */
class ScsiResetRecoveryTest {

    private class StickyReader(blocks: Int) : UsbCommunication {
        val store = ByteArray(blocks * BLOCK) { (it * 31 + it / BLOCK).toByte() }
        var faults = 0
        var resets = 0

        private val pending = ArrayDeque<ByteArray>()

        // Reset Recovery reads usbInterface.id and clears a halt on both endpoints, so
        // this fake needs real objects rather than throwing getters. Their public
        // constructors are hidden; the mockable android.jar that unit tests run
        // against lets the package-private ones be called, and every getter
        // returns its default (id 0).
        override val inEndpoint: UsbEndpoint = stub()
        override val outEndpoint: UsbEndpoint = stub()
        override val usbInterface: UsbInterface = stub()

        override fun bulkOutTransfer(src: ByteBuffer): Int {
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
                        // Half arrives, the transfer fails, and the rest stays queued.
                        pending += data.copyOfRange(0, length / 2)
                        pending += FAIL
                        pending += data.copyOfRange(length / 2, length)
                    } else {
                        pending += data
                    }
                    pending += csw(tag, 0)
                }
                else -> pending += csw(tag, 1)
            }
            return n
        }

        override fun bulkInTransfer(dest: ByteBuffer): Int {
            val chunk = pending.removeFirstOrNull() ?: return 0
            if (chunk === FAIL) throw IOException("Could not read from device, result == -1 errno 0 null")
            val n = minOf(dest.remaining(), chunk.size)
            dest.put(chunk, 0, n)
            if (n < chunk.size) pending.addFirst(chunk.copyOfRange(n, chunk.size))
            return n
        }

        private fun csw(tag: Int, status: Int): ByteArray =
            ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x53425355).putInt(tag).putInt(0).put(status.toByte()).array()

        override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int,
                                     buffer: ByteArray, length: Int): Int {
            if (requestType == 0x21 && request == 0xFF) {                       // Bulk-Only Mass Storage Reset
                resets++
                pending.clear()
            }
            return 0
        }
        override fun resetDevice() {}
        override fun clearFeatureHalt(endpoint: UsbEndpoint) {}
        override fun close() {}

        companion object {
            private val FAIL = ByteArray(0)

            private inline fun <reified T> stub(): T =
                T::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        }
    }

    private fun device(reader: StickyReader) = ScsiBlockDevice(reader, 0).also { it.init() }

    @Test
    fun aReadThatFailsPartWayRecoversAndReturnsTheRightData() {
        val reader = StickyReader(blocks = 64)
        val dev = device(reader)
        reader.faults = 1
        val out = ByteBuffer.allocate(8 * BLOCK)
        dev.read(4, out)
        assertArrayEquals(reader.store.copyOfRange(4 * BLOCK, 12 * BLOCK), out.array())
        assertEquals("one Reset Recovery, for the one failure", 1, reader.resets)
    }

    @Test
    fun theCommandAfterARecoveredFailureIsInStep() {
        // Upstream's worse symptom: after giving up, every later command was also
        // one status behind. Here the failure recovers and the next read is clean.
        val reader = StickyReader(blocks = 64)
        val dev = device(reader)
        reader.faults = 1
        dev.read(4, ByteBuffer.allocate(8 * BLOCK))
        val next = ByteBuffer.allocate(4 * BLOCK)
        dev.read(40, next)
        assertArrayEquals(reader.store.copyOfRange(40 * BLOCK, 44 * BLOCK), next.array())
    }

    @Test
    fun noFaultMeansNoReset() {
        val reader = StickyReader(blocks = 64)
        val dev = device(reader)
        dev.read(0, ByteBuffer.allocate(8 * BLOCK))
        assertEquals(0, reader.resets)
    }

    private companion object {
        const val BLOCK = 512
    }
}

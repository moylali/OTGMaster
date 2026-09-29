package app.fayaz.otgmaster.usb

import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import me.jahnen.libaums.core.driver.scsi.ScsiBlockDevice
import me.jahnen.libaums.core.driver.scsi.commands.sense.UnitAttention
import me.jahnen.libaums.core.usb.UsbCommunication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A USB card reader reports a unit attention on the first command after a card
 * goes in, and libaums gave up on it.
 *
 * Observed on a Huawei P20 Lite with an SD card in a Realtek reader: every first
 * plug-in failed with "Could not open RawBlockDevice via libaums" and
 * `UnitAttention (ASC: 40, ASCQ: 0)` — ASC 40 decimal is 0x28, "not ready to ready
 * change, medium may have changed". SCSI reports it once and then clears it, so a
 * rescan got through; the first open never did. A USB stick has no removable
 * medium and never raises it, which is why eleven drives of benchmarking never
 * hit this.
 *
 * The fake below speaks bulk-only transport well enough for ScsiBlockDevice.init:
 * INQUIRY, TEST UNIT READY, READ CAPACITY, and REQUEST SENSE when a command fails.
 */
class CardReaderUnitAttentionTest {

    /**
     * @param unitAttentions how many TEST UNIT READY commands fail before the
     *   condition clears
     * @param asc the additional sense code reported with each failure
     */
    private class FakeReader(private var unitAttentions: Int, private val asc: Int) : UsbCommunication {
        private val pending = ArrayDeque<ByteArray>()
        private var senseKey = 0
        private var senseAsc = 0
        var testUnitReadyCount = 0

        override val inEndpoint: UsbEndpoint get() = throw UnsupportedOperationException()
        override val outEndpoint: UsbEndpoint get() = throw UnsupportedOperationException()
        override val usbInterface: UsbInterface get() = throw UnsupportedOperationException()

        override fun bulkOutTransfer(src: ByteBuffer): Int {
            val n = src.remaining()
            val cbw = ByteArray(n).also { src.get(it) }
            val tag = ByteBuffer.wrap(cbw, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            when (cbw[15].toInt() and 0xFF) {
                0x12 -> { pending += ByteArray(36); pending += csw(tag, 0) }          // INQUIRY: direct-access device
                0x00 -> {                                                              // TEST UNIT READY
                    testUnitReadyCount++
                    if (unitAttentions > 0) {
                        unitAttentions--
                        senseKey = 0x06; senseAsc = asc
                        pending += csw(tag, 1)                                          // CHECK CONDITION
                    } else {
                        senseKey = 0; senseAsc = 0
                        pending += csw(tag, 0)
                    }
                }
                0x03 -> {                                                              // REQUEST SENSE
                    val sense = ByteArray(18)
                    sense[0] = 0x70; sense[2] = senseKey.toByte(); sense[7] = 10
                    sense[12] = senseAsc.toByte()
                    pending += sense; pending += csw(tag, 0)
                    senseKey = 0; senseAsc = 0                                          // reported once, then clear
                }
                0x25 -> {                                                              // READ CAPACITY(10)
                    val cap = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                    cap.putInt(2047); cap.putInt(512)
                    pending += cap.array(); pending += csw(tag, 0)
                }
                else -> pending += csw(tag, 1)
            }
            return n
        }

        override fun bulkInTransfer(dest: ByteBuffer): Int {
            val chunk = pending.removeFirstOrNull() ?: return 0
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
    }

    @Test
    fun aCardReaderReportingMediumChangedOnceStillOpens() {
        val reader = FakeReader(unitAttentions = 1, asc = 0x28)
        val dev = ScsiBlockDevice(reader, 0)
        dev.init()
        assertEquals("block size from READ CAPACITY", 512, dev.blockSize)
        assertEquals("the second TEST UNIT READY, after the unit attention cleared, succeeded",
            2, reader.testUnitReadyCount)
    }

    @Test
    fun anUnrelatedUnitAttentionStillFailsInit() {
        // 0x3F, "target operating conditions have changed", is not a card going in.
        // The retry is deliberately narrow; anything else must still surface.
        val dev = ScsiBlockDevice(FakeReader(unitAttentions = 1, asc = 0x3F), 0)
        try {
            dev.init()
            fail("init should have thrown for an unrelated unit attention")
        } catch (e: UnitAttention) {
            assertEquals(0x3F, (e.additionalSenseCode as Number).toInt() and 0xFF)
        }
    }

    @Test
    fun aReaderThatNeverClearsGivesUpRatherThanLooping() {
        val dev = ScsiBlockDevice(FakeReader(unitAttentions = Int.MAX_VALUE, asc = 0x28), 0)
        try {
            dev.init()
            fail("init should give up on a medium-changed condition that never clears")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("MAX_RECOVERY_ATTEMPTS"))
        }
    }
}

package app.fayaz.otgmaster.usb

import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import me.jahnen.libaums.core.driver.scsi.ScsiBlockDevice
import me.jahnen.libaums.core.usb.UsbCommunication
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A drive over 2 TiB: libaums V23 (issue #87).
 *
 * The reporter's disk has 5,860,532,224 sectors, and its second partition runs from
 * sector 4,096,000,000 to the end, across 2^32. READ CAPACITY(10) cannot describe it
 * and READ(10)/WRITE(10) cannot address the far side: upstream threw "Device requires
 * SCSI READ CAPACITY(16)" at open, and its `deviceOffset.toInt()` would have sent a
 * transfer at 2^32 + n to block n. The fake drive below speaks the 10- and 16-byte
 * commands as SBC-3 defines them, with content derived from each block's address, so
 * a transfer sent to the wrong block reads back wrong.
 */
class LargeDiskScsiTest {

    /** [startingAnswers]: how many READ CAPACITY(10)s are answered 0xFFFFFFFF before the real size. */
    private class BigDrive(val blocks: Long, var startingAnswers: Int = 0) : UsbCommunication {
        /** Written blocks; unwritten ones read as [pattern]. */
        val written = HashMap<Long, ByteArray>()
        val opcodes = mutableListOf<Int>()
        private val pending = ArrayDeque<ByteArray>()
        private var writeLba = 0L
        private var writeRemaining = 0
        private var writeTag = 0

        override val inEndpoint: UsbEndpoint get() = throw UnsupportedOperationException()
        override val outEndpoint: UsbEndpoint get() = throw UnsupportedOperationException()
        override val usbInterface: UsbInterface get() = throw UnsupportedOperationException()

        fun block(lba: Long): ByteArray = written[lba] ?: pattern(lba)

        override fun bulkOutTransfer(src: ByteBuffer): Int {
            if (writeRemaining > 0) {
                val n = minOf(src.remaining(), writeRemaining)
                check(n % BLOCK == 0)
                repeat(n / BLOCK) { written[writeLba++] = ByteArray(BLOCK).also { src.get(it) } }
                writeRemaining -= n
                if (writeRemaining == 0) pending += csw(writeTag)
                return n
            }
            val n = src.remaining()
            val cbw = ByteArray(n).also { src.get(it) }
            val tag = ByteBuffer.wrap(cbw, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val length = ByteBuffer.wrap(cbw, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
            // Absolute indexes: the CDB starts at byte 15 of the CBW, its address at 2 of the CDB.
            val cdb = ByteBuffer.wrap(cbw).order(ByteOrder.BIG_ENDIAN)
            val opcode = cbw[15].toInt() and 0xFF
            opcodes += opcode
            when (opcode) {
                0x12 -> { pending += ByteArray(36); pending += csw(tag) }            // INQUIRY
                0x00 -> pending += csw(tag)                                           // TEST UNIT READY
                0x25 -> {                                                             // READ CAPACITY(10)
                    val last = if (blocks - 1 > 0xFFFF_FFFFL || startingAnswers-- > 0) -1 else (blocks - 1).toInt()
                    pending += ByteBuffer.allocate(8).putInt(last).putInt(BLOCK).array()
                    pending += csw(tag)
                }
                0x9E -> {                                                             // READ CAPACITY(16)
                    check(cbw[16].toInt() == 0x10) { "service action must be READ CAPACITY(16)" }
                    pending += ByteBuffer.allocate(32).putLong(blocks - 1).putInt(BLOCK).array()
                    pending += csw(tag)
                }
                0x28, 0x2A -> {                                                       // READ(10) / WRITE(10)
                    val lba = cdb.getInt(17).toLong() and 0xFFFF_FFFFL
                    transfer(opcode == 0x2A, lba, length, tag)
                }
                0x88, 0x8A -> transfer(opcode == 0x8A, cdb.getLong(17), length, tag)   // READ(16) / WRITE(16)
                else -> pending += csw(tag, 1)
            }
            return n
        }

        private fun transfer(write: Boolean, lba: Long, length: Int, tag: Int) {
            check(lba + length / BLOCK <= blocks) { "transfer past the end of the drive at $lba" }
            if (write) {
                writeLba = lba; writeRemaining = length; writeTag = tag
            } else {
                val out = ByteArray(length)
                for (i in 0 until length / BLOCK) block(lba + i).copyInto(out, i * BLOCK)
                pending += out; pending += csw(tag)
            }
        }

        override fun bulkInTransfer(dest: ByteBuffer): Int {
            val chunk = pending.removeFirstOrNull() ?: return 0
            val n = minOf(dest.remaining(), chunk.size)
            dest.put(chunk, 0, n)
            if (n < chunk.size) pending.addFirst(chunk.copyOfRange(n, chunk.size))
            return n
        }

        private fun csw(tag: Int, status: Int = 0): ByteArray =
            ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x53425355).putInt(tag).putInt(0).put(status.toByte()).array()

        override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int,
                                     buffer: ByteArray, length: Int): Int = 0
        override fun resetDevice() {}
        override fun clearFeatureHalt(endpoint: UsbEndpoint) {}
        override fun close() {}
    }

    @Test
    fun `a drive over 2 TiB opens with its real size`() {
        val drive = BigDrive(REPORTER_BLOCKS)
        val dev = ScsiBlockDevice(drive, 0).also { it.init() }
        assertEquals(REPORTER_BLOCKS, dev.blocks)
        assertEquals(BLOCK, dev.blockSize)
    }

    @Test
    fun `a read past 2^32 blocks reads that block, not one near the start`() {
        val drive = BigDrive(REPORTER_BLOCKS)
        val dev = ScsiBlockDevice(drive, 0).also { it.init() }
        drive.opcodes.clear()
        // Inside the reporter's second partition, which starts at 4,096,000,000.
        val lba = 5_000_000_000L
        val out = ByteBuffer.allocate(8 * BLOCK)
        dev.read(lba, out)
        assertArrayEquals(expected(drive, lba, 8), out.array())
        assertEquals("READ(16)", listOf(0x88), drive.opcodes)
    }

    @Test
    fun `a write across the 2^32 boundary lands where it was sent`() {
        val drive = BigDrive(REPORTER_BLOCKS)
        val dev = ScsiBlockDevice(drive, 0).also { it.init() }
        drive.opcodes.clear()
        val lba = 0x1_0000_0000L - 4
        val data = ByteArray(8 * BLOCK).also { java.util.Random(87).nextBytes(it) }
        dev.write(lba, ByteBuffer.wrap(data))
        assertEquals("WRITE(16)", listOf(0x8A), drive.opcodes)
        assertEquals(setOf(lba, lba + 1, lba + 2, lba + 3, lba + 4, lba + 5, lba + 6, lba + 7), drive.written.keys)
        assertArrayEquals(data, expected(drive, lba, 8))
        assertEquals("nothing written near the start of the drive", null, drive.written[3L])
    }

    @Test
    fun `transfers below 2^32 blocks keep the 10-byte commands`() {
        // Every drive up to 2 TiB, and the reporter's first partition, which ends at
        // 4,095,999,999: they must send exactly what they always have.
        val drive = BigDrive(REPORTER_BLOCKS)
        val dev = ScsiBlockDevice(drive, 0).also { it.init() }
        drive.opcodes.clear()
        dev.read(4_095_999_992L, ByteBuffer.allocate(8 * BLOCK))
        dev.write(2048, ByteBuffer.allocate(BLOCK))
        dev.read(0xFFFF_FFFFL, ByteBuffer.allocate(BLOCK))   // the last block READ(10) can name
        assertEquals(listOf(0x28, 0x2A, 0x28), drive.opcodes)
    }

    @Test
    fun `a 2 TB drive still opens with READ CAPACITY(10) alone`() {
        // The Samsung T7 Shield of LargeVolumeTest: 3,907,029,168 blocks, under 2^32.
        val drive = BigDrive(3_907_029_168L)
        val dev = ScsiBlockDevice(drive, 0).also { it.init() }
        assertEquals(3_907_029_168L, dev.blocks)
        assertEquals("no READ CAPACITY(16)", false, 0x9E in drive.opcodes)
    }

    @Test
    fun `a bridge that first says 0xFFFFFFFF for a 2 TB disk is asked again, and READ CAPACITY(16) is not sent`() {
        // Issue #87's RTL9210 enclosure with a 2 TB SSD: 0xFFFFFFFF on the first ask, then
        // no answer to READ CAPACITY(16) until replugged, then its real size on replug.
        val drive = BigDrive(3_907_029_168L, startingAnswers = 2)
        val dev = ScsiBlockDevice(drive, 0).also { it.init() }
        assertEquals(3_907_029_168L, dev.blocks)
        assertEquals("no READ CAPACITY(16)", false, 0x9E in drive.opcodes)
        assertEquals("READ CAPACITY(10) three times", 3, drive.opcodes.count { it == 0x25 })
    }

    @Test
    fun `a disk really over 2 TiB is asked READ CAPACITY(10) four times, then READ CAPACITY(16)`() {
        val drive = BigDrive(REPORTER_BLOCKS)
        val dev = ScsiBlockDevice(drive, 0).also { it.init() }
        assertEquals(REPORTER_BLOCKS, dev.blocks)
        assertEquals(4, drive.opcodes.count { it == 0x25 })
        assertEquals(1, drive.opcodes.count { it == 0x9E })
    }

    private fun expected(drive: BigDrive, lba: Long, count: Int): ByteArray {
        val out = ByteArray(count * BLOCK)
        for (i in 0 until count) drive.block(lba + i).copyInto(out, i * BLOCK)
        return out
    }

    private companion object {
        const val BLOCK = 512
        /** The reporter's disk: `Device Start End Sectors` ends at 5,860,532,223. */
        const val REPORTER_BLOCKS = 5_860_532_224L

        /** Each block's content is its own address, repeated: a misdirected transfer shows. */
        fun pattern(lba: Long): ByteArray =
            ByteBuffer.allocate(BLOCK).apply { while (hasRemaining()) putLong(lba) }.array()
    }
}

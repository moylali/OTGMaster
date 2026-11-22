package app.fayaz.otgmaster.usb

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.usb.UsbCommunication
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Random

/**
 * A reset can make a drive lose writes it had already acknowledged (local patch V25,
 * [LibaumsRawBlockDevice.recentWrites]).
 *
 * On a OnePlus 7 with device-matrix Drive 2 (2026-10-30), a 64 KiB write at block
 * 17,607,921 failed and libaums reset the drive six times. Afterwards block 17,607,920,
 * which the command before had written and the drive had confirmed, read back as
 * zeros. It begins the 4 KiB unit the failed write starts in. [LossyDrive] does the
 * same: when a write fails, or a reset happens inside a write that libaums recovers,
 * it zeroes the 8-block unit holding that write's first block.
 */
class WriteReplayTest {

    private class LossyDrive(override val blocks: Long = 100_000L) : BlockDeviceDriver {
        override val blockSize = 512
        val disk = ByteArray((blocks * blockSize).toInt())
        var resets = 0L
        var writeCount = 0
        /** The write (1-based) that fails after six resets, losing its unit. */
        var failWrite = -1
        /** More writes (1-based) that fail the same way, such as the first write of a replay. */
        var failWrites = emptySet<Int>()
        /** The write (1-based) that succeeds, but only after a reset that loses its unit. */
        var resetInsideWrite = -1

        override fun init() {}

        var resetOnNextRead = false
        var lostOnReadReset = -1L

        override fun read(deviceOffset: Long, buffer: ByteBuffer) {
            if (resetOnNextRead) {
                resetOnNextRead = false
                resets += 1
                if (lostOnReadReset >= 0) loseUnitOf(lostOnReadReset)
            }
            val n = buffer.remaining()
            buffer.put(disk, (deviceOffset * blockSize).toInt(), n)
        }

        private fun loseUnitOf(block: Long) {
            val unit = block - block % 8
            java.util.Arrays.fill(disk, (unit * blockSize).toInt(), ((unit + 8) * blockSize).toInt(), 0)
        }

        override fun write(deviceOffset: Long, buffer: ByteBuffer) {
            writeCount++
            if (writeCount == failWrite || writeCount in failWrites) {
                resets += 6
                loseUnitOf(deviceOffset)
                throw IOException("MAX_RECOVERY_ATTEMPTS Exceeded while trying to transfer command to device")
            }
            if (writeCount == resetInsideWrite) {
                resets += 1
                loseUnitOf(deviceOffset)
            }
            val n = buffer.remaining()
            buffer.get(disk, (deviceOffset * blockSize).toInt(), n)
        }
    }

    private class NoCommunication : UsbCommunication {
        override val inEndpoint get() = throw NotImplementedError()
        override val outEndpoint get() = throw NotImplementedError()
        override val usbInterface get() = throw NotImplementedError()
        override fun bulkOutTransfer(src: ByteBuffer) = throw NotImplementedError()
        override fun bulkInTransfer(dest: ByteBuffer) = throw NotImplementedError()
        override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, length: Int) =
            throw NotImplementedError()
        override fun resetDevice() = throw NotImplementedError()
        override fun clearFeatureHalt(endpoint: android.hardware.usb.UsbEndpoint) = throw NotImplementedError()
        override fun close() {}
    }

    private fun device(drive: LossyDrive) =
        LibaumsRawBlockDevice(drive, NoCommunication(), bulkResets = { drive.resets })

    /** The bigwrite's shape: consecutive 64 KiB writes starting at an odd block. */
    private fun writeSequence(dev: LibaumsRawBlockDevice, first: Long, count: Int, seed: Long): ByteArray {
        val all = ByteArray(count * 128 * 512).also { Random(seed).nextBytes(it) }
        for (i in 0 until count) {
            dev.writeBlocks(first + i * 128L, all.copyOfRange(i * 65536, (i + 1) * 65536))
        }
        return all
    }

    private fun onDisk(drive: LossyDrive, first: Long, bytes: Int) =
        drive.disk.copyOfRange((first * 512).toInt(), (first * 512).toInt() + bytes)

    @Test
    fun `a failed write's reset does not lose the block the write before it put down`() {
        val drive = LossyDrive().apply { failWrite = 5 }
        val dev = device(drive)
        val first = 1_001L               // write 5 starts at 1513, so its unit 1512..1519 includes write 4's last block
        val all = writeSequence(dev, first, 8, seed = 88)
        assertArrayEquals("every byte the app wrote is on the drive", all, onDisk(drive, first, all.size))
        assertEquals(1L, dev.replays)
    }

    @Test
    fun `a reset inside a write libaums recovered from on its own is replayed too`() {
        val drive = LossyDrive().apply { resetInsideWrite = 6 }
        val dev = device(drive)
        val first = 2_001L
        val all = writeSequence(dev, first, 8, seed = 71)
        assertArrayEquals(all, onDisk(drive, first, all.size))
        assertEquals(1L, dev.replays)
    }

    /**
     * #153 and the Galaxy Tab A9+ in the 0.5.3 matrix: a write gave up, the drive answered
     * again, and the replay of the last writes then failed too, on its first write. The
     * replay had no recovery of its own, so the whole Encrypt (or bigwrite) stopped with
     * "could not write the last 1024 KiB again after a failed write" and left the drive
     * half written. A replay write that fails is now recovered like any other write.
     */
    @Test
    fun `a replay write that fails is recovered, not the end of the operation`() {
        val drive = LossyDrive().apply { failWrite = 5; failWrites = setOf(6) }   // write 6 is the replay's first
        val dev = device(drive)
        val first = 5_001L
        val all = writeSequence(dev, first, 8, seed = 153)
        assertArrayEquals("every byte the app wrote is on the drive", all, onDisk(drive, first, all.size))
    }

    /** The replay fails twice at the same size: it is written again in smaller transfers. */
    @Test
    fun `a replay that keeps failing steps the transfer size down`() {
        val drive = LossyDrive().apply { failWrite = 5; failWrites = setOf(6, 7) }
        val dev = device(drive)
        val first = 6_001L
        val all = writeSequence(dev, first, 8, seed = 254)
        assertArrayEquals(all, onDisk(drive, first, all.size))
        // The 64 KiB writes failed twice at that size: halved, to 32 KiB.
        assertEquals("stepped down after the second failure at one size", 32 * 1024, dev.transferBytes)
    }

    @Test
    fun `without resets nothing is written twice`() {
        val drive = LossyDrive()
        val dev = device(drive)
        val all = writeSequence(dev, 3_001L, 20, seed = 87)
        assertArrayEquals(all, onDisk(drive, 3_001L, all.size))
        assertEquals(0L, dev.replays)
        assertEquals("one drive write per 64 KiB, no extras", 20, drive.writeCount)
    }

    @Test
    fun `a reset during a read replays the writes before it`() {
        // A read failing while the drive still has recent writes in hand: the reset
        // sent for the read can lose them just the same.
        val drive = LossyDrive()
        val dev = device(drive)
        val all = writeSequence(dev, 4_001L, 4, seed = 29)
        val lastBlock = 4_001L + 4 * 128 - 1
        drive.lostOnReadReset = lastBlock
        // A read that resets mid-way, as libaums does when it recovers a read itself.
        drive.resetOnNextRead = true
        dev.readBlocks(50_000L, 8)
        assertArrayEquals(all, onDisk(drive, 4_001L, all.size))
        assertEquals(1L, dev.replays)
    }

    /**
     * D2L2EXFAT, Galaxy Tab A9+, 2026-11-21: a read at block 77,717,945 needed a reset, and
     * the replay wrote the last 904 KiB again from block 77,716,137, the oldest write still
     * in the window. Block 77,716,136 then held the wrong bytes: it begins the 4 KiB unit
     * that oldest write starts in, and belonged to the write before, which had already left
     * the window, so nothing wrote it again. The drive had been rewriting that unit.
     */
    @Test
    fun `a reset does not lose the start of the unit the oldest replayed write begins in`() {
        val drive = LossyDrive()
        val dev = device(drive)
        val first = 1_001L
        // 20 writes of 64 KiB: the 1 MiB window holds the last 16, the oldest at block 1513.
        val all = writeSequence(dev, first, 20, seed = 2111)
        val oldest = first + 4 * 128
        drive.lostOnReadReset = oldest      // its unit, 1512..1519, includes write 4's last block
        drive.resetOnNextRead = true
        dev.readBlocks(90_000L, 8)
        assertEquals(1L, dev.replays)
        assertArrayEquals("every byte the app wrote is on the drive", all, onDisk(drive, first, all.size))
    }

    /** The same when the window holds the end of one large write, sent in 120 KiB chunks. */
    @Test
    fun `a reset after one large write does not lose the start of its oldest chunk's unit`() {
        val drive = LossyDrive()
        val dev = device(drive)
        val big = ByteArray(3 * LibaumsRawBlockDevice.REPLAY_BYTES / 2 + 512).also { Random(77).nextBytes(it) }
        dev.writeBlocks(20_001L, big)
        // The chunks the window still holds: the newest ones that fit in REPLAY_BYTES.
        val chunk = dev.transferBytes / 512
        val total = big.size / 512
        val starts = (0 until total step chunk).map { 20_001L + it }
        var kept = 0
        var oldest = starts.last()
        for (st in starts.reversed()) {
            val n = minOf(chunk, (20_001L + total - st).toInt())
            if ((kept + n) * 512 > LibaumsRawBlockDevice.REPLAY_BYTES) break
            kept += n; oldest = st
        }
        assertEquals("the oldest kept chunk starts inside a unit", 1L, oldest % 8)
        drive.lostOnReadReset = oldest
        drive.resetOnNextRead = true
        dev.readBlocks(90_000L, 8)
        assertArrayEquals(big, onDisk(drive, 20_001L, big.size))
    }

    @Test
    fun `a write larger than the window keeps its tail for replay`() {
        val drive = LossyDrive().apply { failWrite = 2 }
        val dev = device(drive)
        val big = ByteArray(3 * LibaumsRawBlockDevice.REPLAY_BYTES / 2).also { Random(5).nextBytes(it) }
        // One call, chunked at 120 KiB: write 2 fails, and its unit's first block was write 1's last.
        dev.writeBlocks(10_001L, big)
        assertArrayEquals(big, onDisk(drive, 10_001L, big.size))
    }
}

package app.fayaz.otgmaster.usb

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.usb.UsbCommunication
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * libaums' ScsiBlockDevice turns whatever span it is given into a single SCSI
 * command and a single bulkTransfer. Oversized requests are rejected by some USB
 * stacks — a Pixel 10 Pro XL (Android 17) fails a ~1.85 MB transfer outright,
 * while a OnePlus 7 (Android 16) accepts it. Mounting exFAT triggers exactly that,
 * because libexfat loads the whole allocation bitmap in one read.
 *
 * These tests pin the chunking that keeps every request within a safe bound, and
 * — just as importantly — that chunking reassembles the bytes in the right order.
 */
class LibaumsRawBlockDeviceTest {

    private class FakeDriver(
        override val blockSize: Int = 512,
        override val blocks: Long = 121_145_344L,
    ) : BlockDeviceDriver {
        /** (startBlock, blockCount) for every call, in order. */
        val reads = mutableListOf<Pair<Long, Int>>()
        val writes = mutableListOf<Pair<Long, Int>>()
        /** Captured payload of each write, concatenated. */
        val written = mutableListOf<Byte>()

        override fun init() {}

        /** Deterministic content so reassembly errors show up as wrong bytes. */
        private fun byteAt(absoluteOffset: Long): Byte = (absoluteOffset % 251).toByte()

        override fun read(deviceOffset: Long, buffer: ByteBuffer) {
            val count = buffer.remaining() / blockSize
            reads += deviceOffset to count
            val base = deviceOffset * blockSize
            val start = buffer.position()
            val len = buffer.remaining()
            // Absolute put: index is from the start of the buffer, not from position.
            for (i in 0 until len) {
                buffer.put(start + i, byteAt(base + i))
            }
            buffer.position(buffer.limit())
        }

        override fun write(deviceOffset: Long, buffer: ByteBuffer) {
            val count = buffer.remaining() / blockSize
            writes += deviceOffset to count
            while (buffer.hasRemaining()) written += buffer.get()
        }
    }

    private class FakeCommunication : UsbCommunication {
        var closed = false
        override val inEndpoint get() = throw NotImplementedError()
        override val outEndpoint get() = throw NotImplementedError()
        override val usbInterface get() = throw NotImplementedError()
        override fun bulkOutTransfer(src: ByteBuffer) = throw NotImplementedError()
        override fun bulkInTransfer(dest: ByteBuffer) = throw NotImplementedError()
        override fun controlTransfer(
            requestType: Int, request: Int, value: Int, index: Int,
            buffer: ByteArray, length: Int
        ) = throw NotImplementedError()
        override fun resetDevice() = throw NotImplementedError()
        override fun clearFeatureHalt(endpoint: android.hardware.usb.UsbEndpoint) =
            throw NotImplementedError()
        override fun close() { closed = true }
    }

    private fun device(driver: FakeDriver) =
        LibaumsRawBlockDevice(driver, FakeCommunication())

    private fun expectedBytes(startBlock: Long, blockCount: Int, blockSize: Int) =
        ByteArray(blockCount * blockSize) { i ->
            ((startBlock * blockSize + i) % 251).toByte()
        }

    @Test
    fun `read larger than the transfer limit is split into bounded chunks`() {
        val driver = FakeDriver()
        // The exFAT allocation bitmap for a 57 GiB volume with 4 KiB clusters:
        // 15,142,912 clusters / 8 = 1,892,864 bytes = 3697 blocks. This is the
        // single read that failed on the Pixel before chunking.
        val blocks = 3697
        val data = device(driver).readBlocks(0, blocks)

        val maxBlocks = LibaumsRawBlockDevice.MAX_TRANSFER_BYTES / driver.blockSize
        assertTrue(
            "every request must stay within the transfer limit",
            driver.reads.all { it.second <= maxBlocks }
        )
        assertEquals("all blocks accounted for", blocks, driver.reads.sumOf { it.second })
        assertEquals("split into ceil(3697/240) chunks", 16, driver.reads.size)
        assertArrayEquals("chunks must reassemble in order", expectedBytes(0, blocks, 512), data)
    }

    @Test
    fun `chunked read starts each request at the right block`() {
        val driver = FakeDriver()
        val startBlock = 2048L
        device(driver).readBlocks(startBlock, 1000)

        var expectedStart = startBlock
        for ((offset, count) in driver.reads) {
            assertEquals("chunk must continue from the previous one", expectedStart, offset)
            expectedStart += count
        }
    }

    @Test
    fun `read within the limit is issued as a single request`() {
        val driver = FakeDriver()
        val data = device(driver).readBlocks(100, 8)
        assertEquals(1, driver.reads.size)
        assertEquals(100L to 8, driver.reads[0])
        assertArrayEquals(expectedBytes(100, 8, 512), data)
    }

    @Test
    fun `write larger than the transfer limit is split and preserves content`() {
        val driver = FakeDriver()
        val blocks = 1000
        val payload = ByteArray(blocks * 512) { (it % 97).toByte() }
        device(driver).writeBlocks(64, payload)

        val maxBlocks = LibaumsRawBlockDevice.MAX_TRANSFER_BYTES / driver.blockSize
        assertTrue(driver.writes.all { it.second <= maxBlocks })
        assertEquals(blocks, driver.writes.sumOf { it.second })
        assertEquals(64L, driver.writes.first().first)
        assertArrayEquals("written bytes must match the source", payload, driver.written.toByteArray())
    }

    @Test
    fun `zero-block read issues no requests`() {
        val driver = FakeDriver()
        val data = device(driver).readBlocks(0, 0)
        assertEquals(0, data.size)
        assertTrue(driver.reads.isEmpty())
    }

    @Test
    fun `transfer limit is expressed in bytes so large-sector drives stay bounded`() {
        // A 4096-byte-sector drive must not get 240 blocks (960 KiB).
        val driver = FakeDriver(blockSize = 4096)
        device(driver).readBlocks(0, 100)
        assertTrue(
            "chunks must stay within the byte limit regardless of sector size",
            driver.reads.all { it.second * 4096 <= LibaumsRawBlockDevice.MAX_TRANSFER_BYTES }
        )
    }

    /**
     * SCSI transactions on one device must never overlap.
     *
     * A transfer is CBW, then data, then CSW on the same bulk endpoints, and
     * ScsiBlockDevice reuses instance-level command buffers between calls. Two
     * threads inside the driver at once corrupts both transfers.
     *
     * This became reachable when CachedBlockDevice stopped holding its lock across
     * physical I/O: that lock had been serialising the transport by accident.
     */
    @Test
    fun concurrentTransfersDoNotOverlapOnTheDevice() {
        val driver = OverlapDetectingDriver()
        val device = LibaumsRawBlockDevice(driver, FakeCommunication())

        // Spans several chunks each, so there is ample opportunity to interleave.
        val big = MAX_BLOCKS * 3
        val threads = (0 until 4).map { i ->
            Thread { device.readBlocks(i.toLong() * big, big) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(20_000) }

        assertEquals("driver must never see two transfers at once",
            1, driver.maxConcurrent)
        assertTrue("all chunks should have run", driver.calls > 4)
    }

    /** A read after close() must fail, not reach a closed UsbCommunication. */
    @Test
    fun readAfterCloseFails() {
        val device = LibaumsRawBlockDevice(FakeDriver(), FakeCommunication())
        device.close()
        val e = runCatching { device.readBlocks(0, 1) }.exceptionOrNull()
        assertTrue("expected an IOException, got $e", e is java.io.IOException)
    }

    @Test
    fun writeAfterCloseFails() {
        val device = LibaumsRawBlockDevice(FakeDriver(), FakeCommunication())
        device.close()
        val e = runCatching { device.writeBlocks(0, ByteArray(512)) }.exceptionOrNull()
        assertTrue("expected an IOException, got $e", e is java.io.IOException)
    }

    /**
     * A drive, or a phone's USB stack, that rejects any single transfer above
     * [limitBlocks], every time, as libaums reports it once its own retries and
     * Reset Recovery are exhausted. Issue #29: a realme RMX5110 (Android 16) failed
     * every 120 KiB read of a SanDisk 3.2Gen1 at block 2048, so the volume never
     * unlocked, while the partition table's small reads had worked.
     */
    private class LimitedDriver(
        private val limitBlocks: Int,
        override val blockSize: Int = 512,
        override val blocks: Long = 120_164_352L,
    ) : BlockDeviceDriver {
        var rejected = 0
        val accepted = mutableListOf<Int>()
        val written = java.io.ByteArrayOutputStream()
        override fun init() {}
        private fun check(buffer: ByteBuffer) {
            val count = buffer.remaining() / blockSize
            if (count > limitBlocks) {
                rejected++
                throw java.io.IOException("MAX_RECOVERY_ATTEMPTS Exceeded while trying to transfer command to device, please reattach device and try again")
            }
            accepted += count
        }
        override fun read(deviceOffset: Long, buffer: ByteBuffer) {
            check(buffer)
            val base = deviceOffset * blockSize
            val start = buffer.position()
            for (i in 0 until buffer.remaining()) buffer.put(start + i, ((base + i) % 251).toByte())
            buffer.position(buffer.limit())
        }
        override fun write(deviceOffset: Long, buffer: ByteBuffer) {
            check(buffer)
            while (buffer.hasRemaining()) written.write(buffer.get().toInt())
        }
    }

    @Test
    fun `a drive that rejects large transfers is read in smaller ones`() {
        val driver = LimitedDriver(limitBlocks = 64)
        val device = LibaumsRawBlockDevice(driver, FakeCommunication())
        // The read that failed in #29: a VeraCrypt header area at the partition start.
        val data = device.readBlocks(2048, 512)
        assertArrayEquals(expectedBytes(2048, 512, 512), data)
        val failuresToLearn = driver.rejected
        assertTrue("it must have had to step down", failuresToLearn > 0)
        assertTrue("every accepted transfer within the drive's limit", driver.accepted.all { it <= 64 })

        // The smaller size sticks: later reads do not fail their way down again.
        assertArrayEquals(expectedBytes(0, 3697, 512), device.readBlocks(0, 3697))
        assertEquals("no further rejections once the size is learned", failuresToLearn, driver.rejected)
    }

    @Test
    fun `a drive that rejects large transfers is written in smaller ones`() {
        val driver = LimitedDriver(limitBlocks = 64)
        val payload = ByteArray(1000 * 512) { (it % 97).toByte() }
        LibaumsRawBlockDevice(driver, FakeCommunication()).writeBlocks(64, payload)
        assertArrayEquals("every byte written once, in order", payload, driver.written.toByteArray())
        assertTrue(driver.accepted.all { it <= 64 })
    }

    @Test
    fun `stepping down stops at the floor and the error keeps its cause`() {
        // Rejects everything above 8 blocks (4 KiB): below the 16 KiB floor.
        val driver = LimitedDriver(limitBlocks = 8)
        val e = runCatching { LibaumsRawBlockDevice(driver, FakeCommunication()).readBlocks(2048, 512) }
            .exceptionOrNull()
        assertTrue("expected an IOException, got $e", e is java.io.IOException)
        val floor = LibaumsRawBlockDevice.MIN_TRANSFER_BYTES / 512
        assertTrue("the message names the floor: ${e!!.message}", e.message!!.contains("chunk limit $floor blocks"))
        assertTrue("the message carries the driver's reason: ${e.message}", e.message!!.contains("MAX_RECOVERY_ATTEMPTS"))
        assertTrue("never below the floor", driver.accepted.isEmpty())
    }

    /** Records the peak number of threads inside read/write simultaneously. */
    private class OverlapDetectingDriver(
        override val blockSize: Int = 512,
        override val blocks: Long = 121_145_344L,
    ) : BlockDeviceDriver {
        private val inside = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile var maxConcurrent = 0; private set
        @Volatile var calls = 0; private set

        override fun init() {}

        private fun enter() {
            val n = inside.incrementAndGet()
            synchronized(this) {
                if (n > maxConcurrent) maxConcurrent = n
                calls++
            }
            // Long enough that an unsynchronised caller would reliably overlap.
            Thread.sleep(2)
        }

        override fun read(deviceOffset: Long, buffer: ByteBuffer) {
            enter()
            try { buffer.position(buffer.limit()) } finally { inside.decrementAndGet() }
        }

        override fun write(deviceOffset: Long, buffer: ByteBuffer) {
            enter()
            try { buffer.position(buffer.limit()) } finally { inside.decrementAndGet() }
        }
    }

    private companion object {
        val MAX_BLOCKS = LibaumsRawBlockDevice.MAX_TRANSFER_BYTES / 512
    }
}

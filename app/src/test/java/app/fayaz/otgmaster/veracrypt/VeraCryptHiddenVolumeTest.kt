package app.fayaz.otgmaster.veracrypt

import app.fayaz.otgmaster.block.RawBlockDevice
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Hidden volumes, against volumes desktop VeraCrypt made, and the rules of its hidden volume
 * protection, taken from Volume::Open, Volume::CheckProtectedRange and Volume::WriteSectors.
 */
class VeraCryptHiddenVolumeTest {

    private class ImageDevice(file: File) : RawBlockDevice {
        private val raf = RandomAccessFile(file, "rw")
        override val blockSize = 512
        override val blockCount = file.length() / 512
        var writes = 0
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            val out = ByteArray(blockCount * 512)
            raf.seek(startBlock * 512); raf.readFully(out); return out
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) {
            writes++
            raf.seek(startBlock * 512); raf.write(data)
        }
        override fun close() = raf.close()
    }

    companion object {
        private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "scripts/build_host_native.sh").exists() }
        private const val OUTER = "outer-volume-password-2026"
        private const val HIDDEN = "hidden-volume-password-2026"
        private const val HIDDEN_START = 25034752L
        private const val HIDDEN_SIZE = 8257536L
        private var ready = false

        @BeforeClass @JvmStatic
        fun setUp() {
            if (ProcessBuilder("sh", "-c", "command -v gcc && command -v veracrypt").start().waitFor() != 0) return
            val lib = File(repo, "app/build/host-native/libotg-host.so")
            val p = ProcessBuilder(File(repo, "scripts/build_host_native.sh").path, lib.path)
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0) throw AssertionError("host native build failed:\n$out")
            System.setProperty("otg.native.hostlib", lib.absolutePath)
            ready = true
        }
    }

    private val images = mutableListOf<File>()
    @After fun tearDown() { images.forEach { it.delete() } }

    private fun create(img: String, type: String, size: String, password: String) {
        val p = ProcessBuilder("veracrypt", "-t", "-c", "--volume-type=$type", img, "--size=$size",
            "--password=$password", "--encryption=AES", "--hash=SHA-512", "--filesystem=none", "--pim=1",
            "--random-source=/dev/urandom", "--non-interactive").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "veracrypt failed: $out" }
    }

    /** A real outer volume with a real hidden volume in it, both with the fixture's sizes. */
    private fun pair(): File {
        assumeTrue("needs gcc and desktop VeraCrypt", ready)
        val f = File.createTempFile("vchidden", ".img").also { it.delete(); images += it }
        create(f.path, "normal", "32M", OUTER)
        create(f.path, "hidden", "8M", HIDDEN)
        return f
    }

    private fun candidate(dev: RawBlockDevice) = VolumeCandidate("image", 0, dev.blockCount, ContainerType.VERACRYPT)

    private fun unlock(dev: RawBlockDevice, password: String, protect: String? = null) =
        VeraCryptUnlocker().unlock(dev, candidate(dev), password.toCharArray(), pim = 1,
            protect = protect?.let {
                HiddenVolumeCredentials(it.toCharArray(), 1, emptyList(), VeraCryptCipher.DEFAULT, VeraCryptHash.DEFAULT)
            })

    @Test fun `the outer password opens the outer volume`() {
        val dev = ImageDevice(pair())
        val v = unlock(dev, OUTER)
        assertEquals(VeraCryptVolumeType.NORMAL, v.volumeType)
        assertEquals((33554432L - 262144L) / 512, v.blockCount)
    }

    @Test fun `the hidden password opens the hidden volume at the offset its header gives`() {
        val dev = ImageDevice(pair())
        val v = unlock(dev, HIDDEN)
        assertEquals(VeraCryptVolumeType.HIDDEN, v.volumeType)
        assertEquals(HIDDEN_SIZE / 512, v.blockCount)
        assertNull(v.hiddenVolumeProtection)

        // What goes in at hidden sector 0 must land at the hidden volume's data offset, encrypted
        // with data unit HIDDEN_START / 512: the same sector reads back through a fresh unlock.
        val pattern = ByteArray(512) { (it * 7).toByte() }
        v.writeBlocks(0, pattern)
        assertArrayEquals(pattern, unlock(dev, HIDDEN).readBlocks(0, 1))
        assertFalse("stored in the clear", dev.readBlocks(HIDDEN_START / 512, 1).contentEquals(pattern))
    }

    @Test fun `a password that opens neither volume is refused`() {
        val dev = ImageDevice(pair())
        assertThrows(IllegalArgumentException::class.java) { unlock(dev, "neither-volume-password-2026") }
    }

    @Test fun `protection takes its range from the hidden header`() {
        val dev = ImageDevice(pair())
        val v = unlock(dev, OUTER, protect = HIDDEN)
        assertEquals(VeraCryptVolumeType.OUTER, v.volumeType)
        val p = v.hiddenVolumeProtection
        assertNotNull(p)
        assertEquals(HIDDEN_START, p!!.rangeStart)
        assertEquals(HIDDEN_START + HIDDEN_SIZE, p.rangeEnd)
    }

    @Test fun `a wrong hidden password does not mount the outer volume`() {
        val dev = ImageDevice(pair())
        assertThrows(HiddenVolumeCredentialsException::class.java) { unlock(dev, OUTER, protect = "not-the-hidden-password") }
    }

    @Test fun `the hidden password as the main one is refused while protecting`() {
        val dev = ImageDevice(pair())
        assertThrows(IllegalArgumentException::class.java) { unlock(dev, HIDDEN, protect = HIDDEN) }
    }

    @Test fun `a write into the hidden range is refused and then every write is`() {
        val dev = ImageDevice(pair())
        val v = unlock(dev, OUTER, protect = HIDDEN)
        val sector = ByteArray(512)
        v.writeBlocks(0, sector)
        assertEquals(1, dev.writes)
        var fired = 0
        v.hiddenVolumeProtection!!.onTriggered = { fired++ }

        // Sector of the outer volume that is the first of the hidden volume.
        val firstHidden = (HIDDEN_START - 131072L) / 512
        val before = dev.readBlocks(HIDDEN_START / 512, 1)
        assertThrows(VolumeWriteProtectedException::class.java) { v.writeBlocks(firstHidden, sector) }
        assertArrayEquals("the hidden volume changed", before, dev.readBlocks(HIDDEN_START / 512, 1))
        assertEquals(1, dev.writes)
        assertTrue(v.hiddenVolumeProtection!!.triggered)
        assertEquals(1, fired)

        // Clear of the range, but the whole volume is write-protected now.
        assertThrows(VolumeWriteProtectedException::class.java) { v.writeBlocks(0, sector) }
        assertEquals(1, dev.writes)
        assertEquals(1, fired)
    }

    @Test fun `the range boundaries are VeraCrypt's`() {
        fun hit(offset: Long, length: Long): Boolean {
            val p = HiddenVolumeProtection(1000, 2000)
            return try { p.checkWrite(offset, length); false } catch (_: VolumeWriteProtectedException) { true }
        }
        assertFalse("ends just before", hit(488, 512))
        assertTrue("overlaps the start", hit(489, 512))
        assertTrue("first sector", hit(1000, 512))
        assertTrue("last byte", hit(1999, 1))
        assertFalse("starts just after", hit(2000, 512))
        assertTrue("spans it", hit(0, 4096))
        assertFalse("nothing written", hit(1000, 0))
    }

    private fun plainHeader(): ByteArray {
        val keys = ByteArray(256) { it.toByte() }
        val b = ByteBuffer.allocate(VeraCryptHeader.PLAIN_BYTES)
        b.put("VERA".toByteArray(Charsets.US_ASCII)).putShort(5).putShort(0x010B)
        b.putInt(CRC32().apply { update(keys) }.value.toInt())
        b.position(28)
        b.putLong(4096).putLong(4096).putLong(1048576).putLong(4096).putInt(0).putInt(512)
        val out = b.array()
        ByteBuffer.wrap(out).putInt(188, CRC32().apply { update(out, 0, 188) }.value.toInt())
        keys.copyInto(out, 192)
        return out
    }

    @Test fun `a valid header parses`() {
        val h = VeraCryptHeader.parse(plainHeader())
        assertNotNull(h)
        assertTrue(h!!.isHidden)
        assertEquals(1048576L, h.dataAreaOffset)
        assertEquals(4096L, h.volumeSize)
    }

    @Test fun `noise with the magic but a bad CRC is not a header`() {
        for (flip in listOf(28, 188, 192)) {
            val bad = plainHeader()
            bad[flip] = (bad[flip].toInt() xor 1).toByte()
            assertNull("byte $flip", VeraCryptHeader.parse(bad))
        }
    }
}

package app.fayaz.otgmaster.disk

import app.fayaz.otgmaster.block.CachedBlockDevice
import app.fayaz.otgmaster.block.ChannelBlockDevice
import app.fayaz.otgmaster.block.RawBlockDeviceAdapter
import app.fayaz.otgmaster.block.ReadOnlyBlockDeviceDriver
import app.fayaz.otgmaster.core.resolve
import app.fayaz.otgmaster.format.ExFatFormatter
import app.fayaz.otgmaster.veracrypt.ContainerType
import app.fayaz.otgmaster.veracrypt.VeraCryptCipher
import app.fayaz.otgmaster.veracrypt.VeraCryptHash
import app.fayaz.otgmaster.veracrypt.VeraCryptImages
import app.fayaz.otgmaster.veracrypt.VeraCryptUnlocker
import app.fayaz.otgmaster.veracrypt.VeraCryptVolumeCreator
import app.fayaz.otgmaster.veracrypt.VolumeCandidate
import me.jahnen.libaums.core.driver.ByteBlockDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Container Master's path through the engine on a VeraCrypt file, before any Android code:
 * the picked file read through its channel (ChannelBlockDevice), unlocked, cached, guarded
 * read-only and mounted by DiskMounter, then served as a DocumentSource. The outer file must
 * not change by a byte.
 */
class ContainerFileMountTest {

    private lateinit var img: File
    private val password = "container-master-test".toCharArray()
    private val manual = ByteArray(300_017).also { java.util.Random(3).nextBytes(it) }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").let { md ->
        f.inputStream().use { i -> val buf = ByteArray(1 shl 20); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    @Before
    fun setUp() {
        assumeTrue("host native library or veracrypt missing", VeraCryptImages.ready)
        img = File.createTempFile("container", ".hc").apply { deleteOnExit() }
        RandomAccessFile(img, "rw").use { it.setLength(16L shl 20) }
        // Made with the engine's own creator and formatter, which VeraCryptVolumeCreatorTest
        // checks against desktop VeraCrypt and cryptsetup; files written through a writable mount.
        VeraCryptImages.ImageDevice(img).use { dev ->
            val vol = VeraCryptVolumeCreator.create(dev, password.copyOf(), 1, VeraCryptCipher.AES, VeraCryptHash.SHA512)
            ExFatFormatter.format(vol, totalSectors = VeraCryptVolumeCreator.dataBytes(img.length() / 512) / 512, label = "CM")
            val fs = DiskMounter.mount(ByteBlockDevice(RawBlockDeviceAdapter(vol)))
            val docs = fs.rootDirectory.createDirectory("Docs")
            docs.createFile("Manual.pdf").apply { write(0, ByteBuffer.wrap(manual)); flush(); close() }
            fs.rootDirectory.createFile("empty.txt").close()
            (fs as? app.fayaz.otgmaster.exfat.ExFatFileSystem)?.unmount()
            vol.close()
        }
    }

    private fun <R> open(pw: CharArray, block: (UsbFileDocumentSource) -> R): R {
        RandomAccessFile(img, "r").use { raf ->
            val dev = ChannelBlockDevice(raf.channel)
            val candidate = VolumeCandidate("picked file", 0, dev.blockCount, ContainerType.VERACRYPT)
            val decrypted = VeraCryptUnlocker().unlock(dev, candidate, pw, pim = 1)
            try {
                val cached = CachedBlockDevice(decrypted, readAheadBytes = CachedBlockDevice.DEFAULT_READAHEAD_BYTES)
                val fs = DiskMounter.mount(ReadOnlyBlockDeviceDriver(ByteBlockDevice(cached)))
                try {
                    return block(UsbFileDocumentSource(fs) { "opened read-only" })
                } finally {
                    (fs as? app.fayaz.otgmaster.exfat.ExFatFileSystem)?.unmount()
                }
            } finally {
                decrypted.close()
            }
        }
    }

    @Test
    fun `a picked VeraCrypt file opens read-only and serves its files byte for byte`() {
        val before = sha(img)
        open(password.copyOf()) { src ->
            assertEquals(listOf("Docs", "empty.txt"), src.children(src.root()).map { it.name }.sorted())
            val node = src.resolve(listOf("Docs", "Manual.pdf"))!!
            assertEquals(manual.size.toLong(), node.size)
            src.open(node).use { h ->
                val out = ByteArray(manual.size)
                var off = 0
                while (off < out.size) {
                    val chunk = ByteArray(65_536)
                    val n = h.read(off.toLong(), chunk, minOf(chunk.size, out.size - off))
                    assertTrue("no progress at $off", n > 0)
                    chunk.copyInto(out, off, 0, n); off += n
                }
                assertEquals(sha(manual), sha(out))
                assertEquals(0, h.read(manual.size.toLong(), ByteArray(16), 16))
                assertThrows(IOException::class.java) { h.write(0, ByteArray(1), 1); h.flush() }
            }
            assertEquals(0L, src.resolve(listOf("empty.txt"))!!.size)
            assertThrows(IOException::class.java) { src.createFile(src.root(), "new.txt") }
        }
        assertEquals("the container changed", before, sha(img))
    }

    @Test
    fun `a wrong password is refused and leaves the file alone`() {
        val before = sha(img)
        assertThrows(IllegalArgumentException::class.java) { open("not-it".toCharArray()) { } }
        assertEquals(before, sha(img))
    }
}

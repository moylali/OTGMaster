package app.fayaz.otgmaster.disk

import app.fayaz.otgmaster.block.FileBlockDevice
import app.fayaz.otgmaster.core.resolve
import app.fayaz.otgmaster.ext4.Ext4FileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * The provider's tree contract over a real filesystem: an ext4 image made by mkfs.ext4
 * and filled through the app's own ext4 driver, so lookups run against what the driver
 * actually lists.
 */
class UsbFileDocumentSourceTest {

    private lateinit var img: File

    private fun run(vararg cmd: String): Int =
        ProcessBuilder(*cmd).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()

    @Before
    fun setUp() {
        assumeTrue("mkfs.ext4 not available", run("sh", "-c", "command -v mkfs.ext4") == 0)
        img = File.createTempFile("docsource", ".img").apply { deleteOnExit() }
        RandomAccessFile(img, "rw").use { it.setLength(32L * 1024 * 1024) }
        assertEquals(0, run("mkfs.ext4", "-q", "-F", "-m", "0", img.absolutePath))
    }

    private fun <R> withSource(block: (UsbFileDocumentSource) -> R): R {
        val dev = FileBlockDevice(img)
        try {
            val fs = Ext4FileSystem.create(dev)
            return block(UsbFileDocumentSource(fs) { fs.readOnlyReason })
        } finally {
            dev.close()
        }
    }

    @Test
    fun `paths resolve exactly, level by level`() = withSource { src ->
        val root = src.root()
        assertTrue(root.isRoot && root.isDirectory)
        val photos = src.createDirectory(root, "Photos")
        val year = src.createDirectory(photos, "2024")
        val pic = src.createFile(year, "a.jpg")
        src.createFile(year, "A.jpg")
        with(UsbFileDocumentSource) { pic.file.write(0, ByteBuffer.wrap(ByteArray(1234) { 7 })) }

        val found = src.resolve(listOf("Photos", "2024", "a.jpg"))
        assertNotNull(found)
        assertEquals("a.jpg", found!!.name)
        assertEquals(1234L, found.size)
        // ext4 names are case-sensitive and document IDs name one entry exactly.
        assertEquals("A.jpg", src.resolve(listOf("Photos", "2024", "A.jpg"))!!.name)
        assertNull(src.resolve(listOf("photos", "2024", "a.jpg")))
        assertNull(src.resolve(listOf("Photos", "2025")))
        // A file is not a folder.
        assertNull(src.resolve(listOf("Photos", "2024", "a.jpg", "x")))
        assertTrue(src.resolve(emptyList())!!.isRoot)
    }

    @Test
    fun `children, rename and delete go through the driver`() = withSource { src ->
        val root = src.root()
        val dir = src.createDirectory(root, "d")
        src.createFile(dir, "one.txt")
        src.createFile(dir, "two.txt")
        assertEquals(listOf("one.txt", "two.txt"), src.children(dir).map { it.name }.sorted())
        // No "." or ".." entries reach the provider.
        assertEquals(listOf("d", "lost+found"), src.children(root).map { it.name }.sorted())

        src.rename(src.child(dir, "one.txt")!!, "uno.txt")
        assertNull(src.child(dir, "one.txt"))
        assertNotNull(src.child(dir, "uno.txt"))
        src.delete(src.child(dir, "two.txt")!!)
        assertEquals(listOf("uno.txt"), src.children(dir).map { it.name })
        assertNull(src.readOnlyReason)
    }

    /**
     * The handle the provider's descriptor callbacks use: writes land, reads return the count
     * actually read (short near the end, 0 at it) and leave the rest of the buffer alone,
     * truncate empties the file, and the volume stays clean for e2fsck.
     */
    @Test
    fun `a handle reads what was written, short at the end, and leaves the volume clean`() {
        assumeTrue("e2fsck not available", run("sh", "-c", "command -v e2fsck") == 0)
        val payload = ByteArray(5000) { (it * 31 + 7).toByte() }
        withSource { src ->
            val node = src.createFile(src.root(), "h.bin")
            src.open(node).use { h ->
                h.write(0, payload, 3000)
                h.write(3000, payload.copyOfRange(3000, 5000), 2000)
                h.flush()
            }
        }
        withSource { src ->
            val node = src.resolve(listOf("h.bin"))!!
            assertEquals(5000L, node.size)
            src.open(node).use { h ->
                assertEquals(5000L, h.size())
                val all = ByteArray(8192) { 0x55 }
                assertEquals(5000, h.read(0, all, 8192))
                assertTrue("content", payload.contentEquals(all.copyOf(5000)))
                assertTrue("bytes past the count were touched", all.copyOfRange(5000, 8192).all { it == 0x55.toByte() })
                val tail = ByteArray(4096)
                assertEquals(904, h.read(4096, tail, 4096))
                assertTrue(payload.copyOfRange(4096, 5000).contentEquals(tail.copyOf(904)))
                assertEquals(0, h.read(5000, tail, 4096))
                assertEquals(0, h.read(9999, tail, 4096))
            }
        }
        withSource { src ->
            src.open(src.resolve(listOf("t.bin")) ?: src.createFile(src.root(), "t.bin")).use { h ->
                h.write(0, payload, 5000)
                h.flush()
                h.truncate()
                assertEquals(0L, h.size())
                h.flush()
            }
        }
        withSource { src -> assertEquals(0L, src.resolve(listOf("t.bin"))!!.size) }
        val p = ProcessBuilder("e2fsck", "-fn", img.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals("e2fsck after the handle's writes:\n$out", 0, p.waitFor())
    }
}

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
}

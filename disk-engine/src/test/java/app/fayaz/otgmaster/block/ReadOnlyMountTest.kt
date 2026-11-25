package app.fayaz.otgmaster.block

import app.fayaz.otgmaster.ext4.Ext4FileSystem
import app.fayaz.otgmaster.ext4.Ext4FileSystemCreator
import me.jahnen.libaums.core.driver.ByteBlockDevice
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.FileSystemCreator
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystemCreator
import me.jahnen.libaums.core.partition.PartitionTableEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * A partition the user mounted read-only must not have a single byte written to
 * it, through the same path a device mount takes: the filesystem creator handed a
 * [ReadOnlyBlockDeviceDriver] over ByteBlockDevice over the raw device.
 *
 * ext4 and FAT32 here, against real mkfs images. exFAT's driver is native Android
 * code and cannot run on the host; it mounts with libexfat's own `ro` option and
 * is checked on a device.
 */
class ReadOnlyMountTest {

    private class FileBlockDevice(private val file: File) : RawBlockDevice {
        private val raf = RandomAccessFile(file, "rw")
        override val blockSize = 512
        override val blockCount get() = file.length() / blockSize
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            val out = ByteArray(blockCount * blockSize)
            raf.seek(startBlock * blockSize); raf.readFully(out); return out
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) {
            raf.seek(startBlock * blockSize); raf.write(data)
        }
        override fun close() = raf.close()
    }

    private val images = mutableListOf<File>()

    @After
    fun cleanUp() = images.forEach { it.delete() }

    private fun run(vararg cmd: String): Int =
        ProcessBuilder(*cmd).redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor() }

    private fun image(sizeMiB: Long, vararg mkfs: String): File {
        assumeTrue("${mkfs[0]} not available", run("sh", "-c", "command -v ${mkfs[0]}") == 0)
        val img = File.createTempFile("readonly", ".img").also { images += it }
        RandomAccessFile(img, "rw").use { it.setLength(sizeMiB * 1024 * 1024) }
        assertEquals("mkfs failed", 0, run(*mkfs, img.absolutePath))
        return img
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Mount [img] read-only through [creator], try to write, and prove nothing changed. */
    private fun assertNothingWritten(img: File, creator: FileSystemCreator, check: (FileSystem) -> Unit = {}) {
        val before = sha256(img)
        val raw = FileBlockDevice(img)
        try {
            val device = ReadOnlyBlockDeviceDriver(ByteBlockDevice(RawBlockDeviceAdapter(raw)))
            val fs = creator.read(PartitionTableEntry(0, 0, 0), device)
            assertNotNull("mounted read-only", fs)
            check(fs!!)
            fs.rootDirectory.listFiles()   // reads work
            try {
                val f = fs.rootDirectory.createFile("must-not-exist.txt")
                f.write(0, ByteBuffer.wrap("x".toByteArray()))
                f.close()
                fail("a write succeeded on a partition mounted read-only")
            } catch (e: IOException) {
                // expected
            }
        } finally {
            raw.close()
        }
        assertEquals("not one byte of the image changed", before, sha256(img))
    }

    @Test
    fun ext4MountedReadOnlyWritesNothing() {
        val img = image(64, "mkfs.ext4", "-q", "-F")
        assertNothingWritten(img, Ext4FileSystemCreator()) { fs ->
            fs as Ext4FileSystem
            assertTrue("ext4 knows it is read-only, so it never attempts a write", fs.isReadOnly)
            assertEquals(Ext4FileSystem.MOUNTED_READ_ONLY, fs.readOnlyReason)
        }
    }

    @Test
    fun fat32MountedReadOnlyWritesNothing() {
        val img = image(64, "mkfs.vfat", "-F", "32")
        assertNothingWritten(img, Fat32FileSystemCreator())
    }

    @Test
    fun theDeviceItselfRefusesWrites() {
        val img = image(8, "mkfs.vfat", "-F", "32")
        val raw = FileBlockDevice(img)
        try {
            val device = ReadOnlyBlockDeviceDriver(ByteBlockDevice(RawBlockDeviceAdapter(raw)))
            val sector = ByteBuffer.allocate(512)
            device.read(0, sector)          // reads pass through
            try {
                device.write(0, ByteBuffer.allocate(512))
                fail("the read-only device accepted a write")
            } catch (e: IOException) {
                assertTrue(e.message!!, e.message!!.contains("read-only"))
            }
        } finally {
            raw.close()
        }
    }
}

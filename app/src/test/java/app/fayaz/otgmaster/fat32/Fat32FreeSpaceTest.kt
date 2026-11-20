package app.fayaz.otgmaster.fat32

import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.block.RawBlockDeviceAdapter
import me.jahnen.libaums.core.driver.ByteBlockDevice
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Free space on a FAT32 volume whose FSInfo does not hold a free cluster count
 * (docs/VENDOR_FIXES.md V30).
 *
 * 0xFFFFFFFF in FSInfo's free count means "unknown, count it". A scratch SD card
 * formatted by Android's vold (OnePlus 7, 2026-11-20) came out that way, and the
 * app showed it as "Used 29.1 GB, Free 0 B" straight after the format: libaums
 * read the field as a signed -1 and multiplied it by the cluster size. The backup
 * and restore space checks would then refuse the card as full.
 */
class Fat32FreeSpaceTest {

    private class FileBlockDevice(private val file: File) : RawBlockDevice {
        private val raf = RandomAccessFile(file, "rw")
        override val blockSize = 512
        override val blockCount get() = file.length() / blockSize
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            val out = ByteArray(blockCount * blockSize)
            raf.seek(startBlock * blockSize)
            raf.readFully(out)
            return out
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) {
            raf.seek(startBlock * blockSize)
            raf.write(data)
        }
        override fun close() = raf.close()
    }

    private lateinit var img: File

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    @Before
    fun setUp() {
        assumeTrue("mkfs.vfat not available", File("/usr/sbin/mkfs.vfat").exists() ||
                File("/sbin/mkfs.vfat").exists())
        img = File.createTempFile("fat32free", ".img")
        RandomAccessFile(img, "rw").use { it.setLength(128L * 1024 * 1024) }
        val (rc, out) = run("mkfs.vfat", "-F", "32", "-s", "8", "-n", "FREETEST", img.absolutePath)
        assumeTrue("mkfs.vfat failed: $out", rc == 0)
    }

    @After
    fun tearDown() {
        if (::img.isInitialized) img.delete()
    }

    private fun <T> withFs(body: (Fat32FileSystem) -> T): T {
        val dev = FileBlockDevice(img)
        try {
            val fs = Fat32FileSystem.read(ByteBlockDevice(RawBlockDeviceAdapter(dev)))
                ?: throw IllegalStateException("not recognised as FAT32")
            return body(fs)
        } finally {
            dev.close()
        }
    }

    private fun le16(o: Long): Int = RandomAccessFile(img, "r").use {
        it.seek(o); (it.read() and 0xFF) or ((it.read() and 0xFF) shl 8)
    }

    /** Byte offset of FSInfo's free cluster count (FSI_Free_Count, offset 488 in the sector). */
    private fun freeCountOffset(): Long = le16(48).toLong() * le16(11) + 488

    private fun setFreeCount(value: Long) = RandomAccessFile(img, "rw").use {
        it.seek(freeCountOffset())
        for (i in 0 until 4) it.write(((value shr (8 * i)) and 0xFF).toInt())
    }

    private fun freeCount(): Long = RandomAccessFile(img, "r").use {
        it.seek(freeCountOffset())
        var v = 0L
        for (i in 0 until 4) v = v or ((it.read().toLong() and 0xFF) shl (8 * i))
        v
    }

    private fun Fat32FileSystem.put(name: String, size: Int) {
        val f = rootDirectory.createFile(name)
        f.write(0, ByteBuffer.wrap(ByteArray(size) { (it % 251).toByte() }))
        f.close()
    }

    /** Free space as mkfs left it, with a file on the volume so the count is not trivially "all". */
    private fun trueFree(): Long = withFs { fs -> fs.put("first.bin", 300 * 1024); fs.freeSpace }

    @Test
    fun anUnknownFreeCountIsCountedFromTheFat() {
        val expected = trueFree()
        setFreeCount(0xFFFFFFFFL)
        withFs { fs -> assertEquals("free space with FSInfo's count unknown", expected, fs.freeSpace) }
    }

    @Test
    fun aFreeCountPastTheVolumeIsCountedFromTheFat() {
        val expected = trueFree()
        setFreeCount(0x7FFFFFFFL)
        withFs { fs -> assertEquals("free space with FSInfo's count out of range", expected, fs.freeSpace) }
    }

    @Test
    fun theCountedFreeSpaceFollowsWritesAndDeletesAndIsNotWrittenBack() {
        val expected = trueFree()
        setFreeCount(0xFFFFFFFFL)
        withFs { fs ->
            val cluster = fs.chunkSize.toLong()
            assertEquals(expected, fs.freeSpace)
            fs.put("second.bin", 10 * cluster.toInt())
            assertEquals("after a 10-cluster write", expected - 10 * cluster, fs.freeSpace)
            fs.rootDirectory.search("second.bin")!!.delete()
            assertEquals("after deleting it", expected, fs.freeSpace)
        }
        assertEquals("FSInfo keeps its \"unknown\"", 0xFFFFFFFFL, freeCount())
        val (rc, out) = run("fsck.vfat", "-n", img.absolutePath)
        assertEquals("fsck.vfat:\n$out", 0, rc)
    }
}

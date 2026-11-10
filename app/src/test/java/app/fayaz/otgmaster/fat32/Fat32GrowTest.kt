package app.fayaz.otgmaster.fat32

import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.block.RawBlockDeviceAdapter
import me.jahnen.libaums.core.driver.ByteBlockDevice
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Growing a FAT32 file must not expose what the drive held before, and must stop at the
 * format's 4 GiB limit. Review findings 2 and 3, 2026-11-09.
 *
 * - A write past the end of a file allocated clusters and moved the length, but left the
 *   gap between the old end and the write as it was on the drive: the slack of the old
 *   last cluster and whole reallocated clusters, which on an encrypted volume is the
 *   decrypted contents of deleted files. Any app with write access to one folder could
 *   read them back.
 * - Nothing refused a file past 0xFFFFFFFF bytes. The directory entry keeps the low 32
 *   bits of the size, so a 4 GiB + 1 byte file was recorded as 1 byte long while its
 *   whole cluster chain stayed allocated.
 */
class Fat32GrowTest {

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

    private var img: File? = null

    @After fun tearDown() { img?.delete() }

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun freshImage(bytes: Long, vararg mkfsArgs: String): File {
        assumeTrue("mkfs.vfat not available",
            File("/usr/sbin/mkfs.vfat").exists() || File("/sbin/mkfs.vfat").exists())
        val f = File.createTempFile("fat32grow", ".img").also { img = it }
        RandomAccessFile(f, "rw").use { it.setLength(bytes) }
        val (rc, out) = run("mkfs.vfat", "-F", "32", *mkfsArgs, "-n", "GROW", f.absolutePath)
        assumeTrue("mkfs.vfat failed: $out", rc == 0)
        return f
    }

    private fun <T> withFs(f: File, block: (UsbFile) -> T): T {
        val dev = FileBlockDevice(f)
        try {
            return block(Fat32FileSystem.read(ByteBlockDevice(RawBlockDeviceAdapter(dev)))!!.rootDirectory)
        } finally { dev.close() }
    }

    private fun UsbFile.put(offset: Long, bytes: ByteArray) = write(offset, ByteBuffer.wrap(bytes))

    private fun UsbFile.get(offset: Long, len: Int): ByteArray {
        val b = ByteBuffer.allocate(len)
        read(offset, b)
        return b.array().copyOf(b.position())
    }

    private fun le(b: ByteArray, o: Int, n: Int): Long =
        (0 until n).fold(0L) { acc, i -> acc or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }

    /** Writes 0xA5 over every cluster after the root directory's (cluster 2). */
    private fun dirtyFreeClusters(f: File) {
        RandomAccessFile(f, "rw").use { raf ->
            val bs = ByteArray(512); raf.seek(0); raf.readFully(bs)
            val bytesPerSector = le(bs, 11, 2)
            val sectorsPerCluster = le(bs, 13, 1)
            val reserved = le(bs, 14, 2)
            val fats = le(bs, 16, 1)
            val sectorsPerFat = le(bs, 36, 4)
            val dataStart = (reserved + fats * sectorsPerFat) * bytesPerSector
            val from = dataStart + sectorsPerCluster * bytesPerSector   // cluster 3
            val chunk = ByteArray(1 shl 20) { 0xA5.toByte() }
            var pos = from
            raf.seek(pos)
            while (pos < raf.length()) {
                val n = minOf(chunk.size.toLong(), raf.length() - pos).toInt()
                raf.write(chunk, 0, n); pos += n
            }
        }
    }

    private fun assertFsckClean(f: File) {
        val (rc, out) = run("fsck.vfat", "-n", f.absolutePath)
        assertEquals("fsck.vfat:\n$out", 0, rc)
    }

    @Test
    fun `a write past the end reads back zeros in the gap, not deleted data`() {
        val f = freshImage(64L shl 20, "-s", "8")   // 4 KiB clusters
        // Every free cluster holds a pattern, as free space on a used drive holds
        // deleted files, so whichever clusters the grow allocates start dirty.
        dirtyFreeClusters(f)
        withFs(f) { root ->
            // A short file, then a write well past its end.
            val g = root.createFile("gap.bin")
            g.put(0, ByteArray(100) { 1 })
            g.put(200_000, byteArrayOf(2))
            g.close()
        }
        withFs(f) { root ->
            val g = root.search("gap.bin")!!
            assertEquals(200_001L, g.length)
            val gap = g.get(100, 200_000 - 100)
            val leaked = gap.count { it != 0.toByte() }
            assertEquals("bytes of the gap that were not zero (deleted data showing through)", 0, leaked)
            assertEquals(2.toByte(), g.get(200_000, 1)[0])
        }
        assertFsckClean(f)
    }

    @Test
    fun `growing inside the last cluster zeroes its old slack`() {
        val f = freshImage(64L shl 20, "-s", "8")
        withFs(f) { root ->
            root.createFile("slack.bin").apply { put(0, ByteArray(4096) { 0x5A }); close() }
            // Shrink to 10 bytes: the rest of the cluster keeps 0x5A on the drive.
            root.search("slack.bin")!!.apply { length = 10; close() }
            root.search("slack.bin")!!.apply { put(3000, byteArrayOf(7)); close() }
        }
        withFs(f) { root ->
            val s = root.search("slack.bin")!!
            val between = s.get(10, 3000 - 10)
            assertEquals("old slack bytes exposed by the grow", 0, between.count { it != 0.toByte() })
        }
        assertFsckClean(f)
    }

    @Test
    fun `a write that would pass 4 GiB is refused before anything is allocated`() {
        // 5 GiB, sparse, so there is room for the file the unfixed code allocated.
        val f = freshImage(5L shl 30)
        withFs(f) { root ->
            val big = root.createFile("big.bin")
            big.put(0, byteArrayOf(1))
            try {
                big.put(0xFFFFFFFFL, byteArrayOf(1, 2))
                fail("a write ending past 0xFFFFFFFF bytes was accepted; the size is now ${big.length}")
            } catch (e: IOException) {
                assertTrue(e.message ?: "", (e.message ?: "").contains("4 GiB"))
            }
            assertEquals("the refused write changed the file", 1L, big.length)
            big.close()
        }
        assertFsckClean(f)
    }
}

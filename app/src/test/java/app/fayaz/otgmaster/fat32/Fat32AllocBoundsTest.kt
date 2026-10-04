package app.fayaz.otgmaster.fat32

import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.block.RawBlockDeviceAdapter
import me.jahnen.libaums.core.driver.ByteBlockDevice
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystem
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * libaums' FAT32 allocator must never hand out a cluster past the data area.
 *
 * A FAT32 table is sized in whole sectors, so it nearly always has entries beyond
 * the last data cluster, and they are zero — "free" to a scan that only looks at
 * the table. The allocator counted upward from the FSInfo hint with no upper bound
 * and no wrap, so on a volume whose hint sat near the end it allocated those
 * entries, and then wrote their clusters past the end of the partition. On the
 * device-matrix drives (OnePlus 7, D1FAT32, nearly full) SlicedBlockDevice refused
 * the write with "Write exceeds slice bounds"; on a stick with a single unsliced
 * partition nothing would have.
 *
 * Here: a fresh FAT32 image, the hint moved to a few clusters before the end, and a
 * file that needs more clusters than remain there. The allocation must wrap to the
 * start of the data area: the image must not grow, fsck.fat must be clean, and the
 * file must read back.
 */
class Fat32AllocBoundsTest {

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

    private fun u16(f: RandomAccessFile, o: Long): Long { f.seek(o); return (f.read() or (f.read() shl 8)).toLong() }
    private fun u32(f: RandomAccessFile, o: Long): Long {
        f.seek(o); return (f.read().toLong() or (f.read().toLong() shl 8) or
            (f.read().toLong() shl 16) or (f.read().toLong() shl 24))
    }
    private fun putU32(f: RandomAccessFile, o: Long, v: Long) {
        f.seek(o); for (i in 0 until 4) f.write(((v shr (8 * i)) and 0xFF).toInt())
    }

    @Test
    fun allocationWrapsInsteadOfRunningPastTheDataArea() {
        assumeTrue("mkfs.vfat not available",
            File("/usr/sbin/mkfs.vfat").exists() || File("/sbin/mkfs.vfat").exists())
        val f = File.createTempFile("fat32bounds", ".img").also { img = it }
        val size = 64L shl 20   // 128 padding entries in its FAT (40 MiB has only 10)
        RandomAccessFile(f, "rw").use { it.setLength(size) }
        val (rc, out) = run("mkfs.vfat", "-F", "32", "-s", "1", "-n", "BOUNDS", f.absolutePath)
        assumeTrue("mkfs.vfat failed: $out", rc == 0)

        val clusters: Long
        RandomAccessFile(f, "rw").use { r ->
            val bps = u16(r, 11)
            r.seek(13); val spc = r.read().toLong()
            val resv = u16(r, 14)
            r.seek(16); val nfat = r.read().toLong()
            val total = u32(r, 32)
            val spf = u32(r, 36)
            val fsInfo = u16(r, 48) * bps
            clusters = (total - resv - nfat * spf) / spc
            // The premise: the table has entries past the last data cluster.
            assumeTrue("FAT has no padding entries", spf * bps / 4 > clusters + 2 + 16)
            // FSInfo "next free" hint (offset 492): a few clusters before the end.
            putU32(r, fsInfo + 492, clusters + 2 - 8)
        }

        val content = Random(7).nextBytes(64 * 512)   // 64 clusters; only ~6 remain at the end
        val dev = FileBlockDevice(f)
        try {
            val fs = Fat32FileSystem.read(ByteBlockDevice(RawBlockDeviceAdapter(dev)))!!
            fs.rootDirectory.createFile("wrap.bin").apply { write(0, ByteBuffer.wrap(content)); close() }
        } finally { dev.close() }

        assertEquals("the image grew: clusters past the data area were written", size, f.length())
        val (fsck, fsckOut) = run("fsck.vfat", "-n", f.absolutePath)
        assertEquals("fsck.vfat:\n$fsckOut", 0, fsck)

        val dev2 = FileBlockDevice(f)
        try {
            val fs = Fat32FileSystem.read(ByteBlockDevice(RawBlockDeviceAdapter(dev2)))!!
            val file = fs.rootDirectory.listFiles().first { it.name == "wrap.bin" }
            val buf = ByteBuffer.allocate(file.length.toInt())
            file.read(0, buf)
            assertArrayEquals(content, buf.array())
        } finally { dev2.close() }
    }
}

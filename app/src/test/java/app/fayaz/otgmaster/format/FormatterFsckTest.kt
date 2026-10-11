package app.fayaz.otgmaster.format

import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.block.RawBlockDeviceAdapter
import me.jahnen.libaums.core.driver.ByteBlockDevice
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystem
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Every volume the formatters write must pass the host's own checker, at every size
 * class where the defaults change (AGENTS.md: a content hash does not verify a
 * filesystem write — run the checker). Images are sparse, so the 40 GiB ones cost only
 * the metadata actually written.
 *
 * The images start full of 0xA5, not zeros: a formatter that forgot to clear a structure
 * leaves garbage the checker sees, where a zeroed sparse file would hide it.
 */
class FormatterFsckTest {

    private class ImageDevice(file: File, override val blockSize: Int = 512) : RawBlockDevice {
        private val raf = RandomAccessFile(file, "rw")
        override val blockCount = file.length() / blockSize
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

    @After fun tearDown() { images.forEach { it.delete() } }

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun tool(name: String) = listOf("/usr/sbin/$name", "/sbin/$name", "/usr/bin/$name").firstOrNull { File(it).exists() }

    /** A sparse image of [bytes], with its first and last 4 MiB filled with 0xA5. */
    private fun image(bytes: Long): File {
        val f = File.createTempFile("format", ".img").also { images += it }
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(bytes)
            val junk = ByteArray(1 shl 20) { 0xA5.toByte() }
            val span = minOf(4L shl 20, bytes / 2)
            var o = 0L
            while (o < span) { raf.seek(o); raf.write(junk, 0, minOf(junk.size.toLong(), span - o).toInt()); o += junk.size }
            o = bytes - span
            while (o < bytes) { raf.seek(o); raf.write(junk, 0, minOf(junk.size.toLong(), bytes - o).toInt()); o += junk.size }
        }
        return f
    }

    // ── FAT32 ────────────────────────────────────────────────────────────────

    private fun assertFat32Clean(f: File, what: String) {
        val fsck = tool("fsck.vfat")
        assumeTrue("fsck.vfat not available", fsck != null)
        val (rc, out) = run(fsck!!, "-n", "-v", f.absolutePath)
        assertEquals("$what: fsck.vfat\n$out", 0, rc)
    }

    @Test fun fat32IsFsckCleanAtEveryDefaultClusterSize() {
        // 40 MiB → 512 B, 100 MiB → 1 KiB, 200 MiB → 2 KiB, 1 GiB → 4 KiB,
        // 12 GiB → 8 KiB, 20 GiB → 16 KiB, 40 GiB → 32 KiB.
        for (mib in listOf(40L, 100L, 200L, 1024L, 12L * 1024, 20L * 1024, 40L * 1024)) {
            val f = image(mib shl 20)
            val l = ImageDevice(f).use { Fat32Formatter.format(it, label = "otg test") }
            assertEquals(Fat32Formatter.defaultClusterBytes(mib shl 20), l.sectorsPerCluster * 512)
            assertFat32Clean(f, "$mib MiB")
        }
    }

    @Test fun fat32KeepsTheLabelAndTakesFilesThroughLibaums() {
        val f = image(64L shl 20)
        ImageDevice(f).use { Fat32Formatter.format(it, label = "Holiday 26") }
        val (rc, out) = run("sh", "-c", "command -v fatlabel >/dev/null && fatlabel '${f.absolutePath}'")
        if (rc == 0) assertEquals("HOLIDAY 26", out.trim())

        val payload = Random(7).nextBytes(3 * 1024 * 1024 + 123)
        ImageDevice(f).use { dev ->
            val root = Fat32FileSystem.read(ByteBlockDevice(RawBlockDeviceAdapter(dev)))!!.rootDirectory
            assertEquals("a fresh volume lists nothing", 0, root.listFiles().size)
            val dir = root.createDirectory("photos")
            val file = dir.createFile("one.bin")
            file.write(0, ByteBuffer.wrap(payload))
            file.close()
        }
        ImageDevice(f).use { dev ->
            val root = Fat32FileSystem.read(ByteBlockDevice(RawBlockDeviceAdapter(dev)))!!.rootDirectory
            val file = root.search("photos/one.bin")!!
            val back = ByteBuffer.allocate(payload.size)
            file.read(0, back)
            assertArrayEquals(payload, back.array())
        }
        assertFat32Clean(f, "after writing")
    }

    @Test fun fat32KeepsAGivenClusterSizeAndRefusesAVolumeTooSmall() {
        val f = image(300L shl 20)
        val l = ImageDevice(f).use { Fat32Formatter.format(it, clusterBytes = 4096) }
        assertEquals(8, l.sectorsPerCluster)
        assertFat32Clean(f, "300 MiB, 4 KiB clusters")
        try {
            Fat32Formatter.layout(512, (16L shl 20) / 512)
            throw AssertionError("16 MiB was accepted as FAT32")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.startsWith("Too small for FAT32"))
        }
    }

    @Test fun fat32OnFourKiBSectors() {
        val f = image(512L shl 20)
        ImageDevice(f, blockSize = 4096).use { Fat32Formatter.format(it, label = "FOURK") }
        assertFat32Clean(f, "4 KiB sectors")
    }

    @Test fun fat32FormatsOnlyTheSectorsItIsGiven() {
        // Inside a VeraCrypt volume the formatter is handed the data area's size, which
        // is smaller than the device it writes through; the sectors after it must survive.
        val f = image(64L shl 20)
        val total = (60L shl 20) / 512
        ImageDevice(f).use { Fat32Formatter.format(it, totalSectors = total) }
        ImageDevice(f).use { dev ->
            val after = dev.readBlocks(total, ((4L shl 20) / 512).toInt())
            assertTrue("bytes past the volume were written", after.all { it == 0xA5.toByte() })
        }
        val trimmed = image(60L shl 20)
        RandomAccessFile(f, "r").use { src -> RandomAccessFile(trimmed, "rw").use { dst ->
            val buf = ByteArray(1 shl 20); var left = 60L shl 20
            while (left > 0) { val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); dst.write(buf, 0, n); left -= n }
        } }
        assertFat32Clean(trimmed, "60 of 64 MiB")
    }

    // ── exFAT ────────────────────────────────────────────────────────────────

    private fun assertExFatClean(f: File, what: String) {
        val fsck = tool("fsck.exfat")
        assumeTrue("fsck.exfat not available", fsck != null)
        val (rc, out) = run(fsck!!, "-n", "-v", f.absolutePath)
        assertEquals("$what: fsck.exfat\n$out", 0, rc)
    }

    @Test fun exFatIsFsckCleanAtEveryDefaultClusterSize() {
        // 8 MiB and 200 MiB → 4 KiB, 1 GiB and 20 GiB → 32 KiB, 40 GiB → 128 KiB.
        for (mib in listOf(8L, 200L, 1024L, 20L * 1024, 40L * 1024)) {
            val f = image(mib shl 20)
            val l = ImageDevice(f).use { ExFatFormatter.format(it, label = "OTG Test") }
            assertEquals(ExFatFormatter.defaultClusterBytes(mib shl 20), l.clusterBytes)
            assertExFatClean(f, "$mib MiB")
        }
    }

    @Test fun exFatKeepsAUnicodeLabelAndAnEmptyOne() {
        val f = image(64L shl 20)
        ImageDevice(f).use { ExFatFormatter.format(it, label = "Fotos 26 ü") }
        assertExFatClean(f, "labelled")
        val label = tool("exfatlabel")
        if (label != null) {
            val (_, out) = run(label, f.absolutePath)
            assertTrue(out, out.contains("Fotos 26 ü"))
        }
        val g = image(64L shl 20)
        ImageDevice(g).use { ExFatFormatter.format(it, label = "") }
        assertExFatClean(g, "unlabelled")
    }

    @Test fun exFatOnFourKiBSectorsAndWithAGivenClusterSize() {
        val f = image(512L shl 20)
        ImageDevice(f, blockSize = 4096).use { ExFatFormatter.format(it, label = "FOURK") }
        assertExFatClean(f, "4 KiB sectors")
        val g = image(512L shl 20)
        val l = ImageDevice(g).use { ExFatFormatter.format(it, clusterBytes = 4096) }
        assertEquals(4096, l.clusterBytes)
        assertExFatClean(g, "4 KiB clusters")
    }

    /**
     * Every size from FAT32's minimum to 2 GiB in 509-sector steps (and exFAT's from
     * 1 MiB), at every cluster size: the FAT must cover every cluster, and the data area
     * must fit and be aligned. The first version iterated fat = f(clusters) eight times
     * and at 64 MiB stopped on a FAT one sector short of the clusters it left.
     */
    @Test fun layoutsCoverEveryClusterAtEverySize() {
        for (clusterBytes in listOf(512, 1024, 2048, 4096, 8192, 16384, 32768)) {
            var sectors = (Fat32Formatter.MIN_CLUSTERS * clusterBytes / 512) + 64
            while (sectors < (2L shl 30) / 512) {
                val l = try { Fat32Formatter.layout(512, sectors, clusterBytes) } catch (e: IllegalArgumentException) { null }
                if (l != null) {
                    assertTrue("FAT32 $sectors/$clusterBytes: FAT short", l.fatSectors * 128 >= l.clusterCount + 2)
                    assertTrue("FAT32 $sectors/$clusterBytes: data area past the end",
                        l.dataStartSector + l.clusterCount * l.sectorsPerCluster <= sectors)
                    assertEquals("FAT32 $sectors/$clusterBytes: data area misaligned", 0L, l.dataStartSector % l.sectorsPerCluster)
                }
                sectors += 509
            }
        }
        for (clusterBytes in listOf(4096, 32768, 131072)) {
            var sectors = 2048L
            while (sectors < (2L shl 30) / 512) {
                val l = try { ExFatFormatter.layout(512, sectors, clusterBytes) } catch (e: IllegalArgumentException) { null }
                if (l != null) {
                    assertTrue("exFAT $sectors/$clusterBytes: FAT short", l.fatLength * 128 >= l.clusterCount + 2)
                    assertTrue("exFAT $sectors/$clusterBytes: heap past the end",
                        l.clusterHeapOffset + l.clusterCount * l.sectorsPerCluster <= sectors)
                    assertTrue("exFAT $sectors/$clusterBytes: FAT overlaps the heap", l.fatOffset + l.fatLength <= l.clusterHeapOffset)
                }
                sectors += 509
            }
        }
    }

    @Test fun exFatUpcaseTableDecompressesToAFullMapping() {
        val t = ExFatFormatter.UpcaseTable.compressed
        val b = ByteBuffer.wrap(t).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var mapped = 0
        while (b.hasRemaining()) {
            val v = b.short.toInt() and 0xFFFF
            if (v == 0xFFFF) mapped += b.short.toInt() and 0xFFFF else mapped++
        }
        assertEquals(0x10000, mapped)
    }
}

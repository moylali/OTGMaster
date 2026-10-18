package app.fayaz.otgmaster.fs

import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.ext4.Ext4FileSystem
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * Volumes over 1 TiB: more than 2^31 512-byte blocks, so a block count turned into an Int
 * goes negative. A 1.8 TB Samsung T7 in VeraCrypt showed as "Unknown" on a Pixel 7 Pro
 * because detection read `minOf(4, blockCount.toInt())` blocks — a negative count, an
 * exception, Unknown — and ext4's mount did the same with 8, so an ext4 volume that size
 * did not mount at all. Both fail on the old code.
 */
class LargeVolumeTest {

    /** [image]'s bytes at the start of a device that claims [blocks] blocks; past them, zeros. */
    private class BigDevice(private val image: ByteArray, override val blockCount: Long) : RawBlockDevice {
        override val blockSize = 512
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            require(blockCount >= 0) { "blockCount must be non-negative" }
            val out = ByteArray(blockCount * 512)
            val from = startBlock * 512
            if (from < image.size) image.copyInto(out, 0, from.toInt(), minOf(image.size.toLong(), from + out.size).toInt())
            return out
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) = throw UnsupportedOperationException()
        override fun close() {}
    }

    private val t7Blocks = 3_907_029_168L   // a 2 TB Samsung T7: 1.82 TiB, well past 2^31 blocks

    private val temps = mutableListOf<File>()
    @After fun tearDown() { temps.forEach { it.delete() } }

    @Test fun anExFatVolumeOverOneTebibyteIsDetectedAsExFat() {
        val boot = ByteArray(2048).also { "EXFAT   ".forEachIndexed { i, c -> it[3 + i] = c.code.toByte() } }
        val r = FilesystemDetector.detect(BigDevice(boot, t7Blocks))
        assertTrue("detected as $r", r is DetectedFilesystem.Supported && r.displayName == "exFAT")
    }

    @Test fun anExt4VolumeOverOneTebibyteMounts() {
        val mkfs = listOf("/usr/sbin/mkfs.ext4", "/sbin/mkfs.ext4").firstOrNull { File(it).exists() }
        assumeTrue("mkfs.ext4 not available", mkfs != null)
        val img = File.createTempFile("big-ext4", ".img").also { temps += it }
        RandomAccessFile(img, "rw").use { it.setLength(16L shl 20) }
        val p = ProcessBuilder(mkfs!!, "-q", "-F", "-m", "0", img.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assumeTrue("mkfs.ext4 failed: $out", p.waitFor() == 0)
        val fs = Ext4FileSystem.create(BigDevice(img.readBytes(), t7Blocks), readOnly = true)
        assertTrue(fs.rootDirectory.isDirectory)
    }
}

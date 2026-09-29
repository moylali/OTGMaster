package app.fayaz.otgmaster.fat32

import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.block.RawBlockDeviceAdapter
import me.jahnen.libaums.core.driver.ByteBlockDevice
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystem
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * A corrupt FAT must not be allowed to destroy the parts of the volume that are
 * still intact.
 *
 * `getChain` followed any cluster below `FAT32_EOF_CLUSTER`, testing only the
 * upper end. A single zero in the middle of a chain therefore did not end the
 * walk: it appended 0, read FAT entry 0 — the media descriptor, `0x0FFFFFF0` —
 * found that below the EOF marker too, and carried on. `free()` then zeroed
 * every entry the walk had collected, including entry 0 and an offset more than
 * a gigabyte past the end of a 57 MiB FAT, which lands in the data area.
 *
 * Measured on a benchmark drive: FAT entry 0 wiped and roughly 514 MB of an
 * unrelated file's chain zeroed, while every on-device check passed — write
 * verify on all three passes, and `fixtures` against host-computed hashes. It
 * was invisible because the app reads the same FAT it corrupted.
 *
 * The assertions here are deliberately not "is the checker happy". `fsck.fat`
 * passed judgment on the damaged drive using the *second* FAT, which libaums
 * never writes and which was therefore fine — a clean `fsck` would not have
 * caught this. What catches it is comparing bytes that nothing should have
 * touched.
 */
class Fat32CorruptChainTest {

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

    /**
     * 2 GiB, sparse. It has to be larger than the byte offset the broken walk
     * computes for cluster 0x0FFFFFF0 — about 1.07 GB — or the read fails with
     * EOF and the bug presents as an exception rather than as the silent
     * corruption it actually is.
     */
    private val imageBytes = 2L * 1024 * 1024 * 1024

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
        img = File.createTempFile("fat32corrupt", ".img")
        RandomAccessFile(img, "rw").use { it.setLength(imageBytes) }
        val (rc, out) = run("mkfs.vfat", "-F", "32", "-n", "TESTVOL", img.absolutePath)
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

    private fun readAt(offset: Long, len: Int): ByteArray {
        RandomAccessFile(img, "r").use {
            it.seek(offset)
            val b = ByteArray(len)
            it.readFully(b)
            return b
        }
    }

    private fun writeAt(offset: Long, bytes: ByteArray) {
        RandomAccessFile(img, "rw").use {
            it.seek(offset)
            it.write(bytes)
        }
    }

    private fun le16(o: Int) = (readAt(o.toLong(), 2).let {
        (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8)
    })

    private fun le32(o: Int) = (readAt(o.toLong(), 4).let {
        (it[0].toLong() and 0xFF) or ((it[1].toLong() and 0xFF) shl 8) or
        ((it[2].toLong() and 0xFF) shl 16) or ((it[3].toLong() and 0xFF) shl 24)
    })

    @Test
    fun aZeroMidChainDoesNotLetDeleteZeroTheRestOfTheVolume() {
        val bytesPerSector = le16(11)
        val reserved = le16(14)
        val sectorsPerFat = le32(36)
        val fatStart = reserved.toLong() * bytesPerSector
        val fatBytes = sectorsPerFat * bytesPerSector

        val before = readAt(fatStart, fatBytes.toInt())

        // A file big enough to occupy several clusters, so there is a chain to
        // break in the middle of.
        withFs { fs ->
            val f = fs.rootDirectory.createFile("bait.bin")
            val buf = ByteBuffer.allocate(256 * 1024)
            buf.put(ByteArray(buf.remaining()) { 0x5A })
            buf.flip()
            f.write(0, buf)
            f.close()
        }

        val after = readAt(fatStart, fatBytes.toInt())

        // Whatever changed in the FAT is bait.bin's chain. Derived rather than
        // assumed, so the test does not depend on allocation order.
        val chain = (0 until (fatBytes / 4).toInt()).filter { e ->
            val o = e * 4
            (0 until 4).any { before[o + it] != after[o + it] }
        }
        assumeTrue("expected a multi-cluster chain, got ${chain.size}", chain.size >= 3)

        // Plant one zeroed entry in the middle of the chain — the state an
        // interrupted write or the V2 defect could leave behind.
        val broken = chain[1]
        writeAt(fatStart + broken * 4, ByteArray(4))

        // Terminate the broken walk deterministically. Without this, entry 0
        // points at 0x0FFFFFF0, whose offset lands in the sparse data area, which
        // reads back as zero — sending the walk to entry 0 again, forever.
        val strayCluster = 0x0FFFFFF0L
        val strayOffset = fatStart + strayCluster * 4
        writeAt(strayOffset, byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F))

        val entryZeroBefore = readAt(fatStart, 4)
        val strayBefore = readAt(strayOffset, 4)

        // Delete it. On the unfixed code this walks 0 -> entry 0 -> 0x0FFFFFF0 and
        // free() zeroes every one of those offsets.
        withFs { fs ->
            fs.rootDirectory.listFiles().firstOrNull { it.name == "bait.bin" }?.delete()
        }

        assertArrayEquals(
            "FAT entry 0 is the media descriptor and belongs to no file — " +
                "delete must not have touched it",
            entryZeroBefore, readAt(fatStart, 4))

        assertArrayEquals(
            "cluster 0x0FFFFFF0 is far outside the FAT; its byte offset lands in " +
                "the data area, and delete must not have written there",
            strayBefore, readAt(strayOffset, 4))
    }
}

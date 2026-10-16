package app.fayaz.otgmaster.fat32

import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.block.RawBlockDeviceAdapter
import me.jahnen.libaums.core.driver.ByteBlockDevice
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystem
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * FAT32 writes in runs of consecutive clusters (docs/VENDOR_FIXES.md V19), checked
 * the AGENTS.md way, content and `fsck.vfat` both.
 *
 * libaums' `ClusterChain.write` wrote at most four clusters per command, 16 KiB at
 * the 4 KiB clusters of the matrix drives, so a 1 MiB write cost 64 of them. Reads
 * were lifted past the same cap in V6 and went from ~5 to ~15 MB/s.
 */
class Fat32WriteRunsTest {

    /** A loopback image as 512-byte sectors, counting the write commands that reach it. */
    private class CountingDevice(private val file: File) : RawBlockDevice {
        private val raf = RandomAccessFile(file, "rw")
        var writes = 0
        override val blockSize = 512
        override val blockCount get() = file.length() / blockSize
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            val out = ByteArray(blockCount * blockSize)
            raf.seek(startBlock * blockSize); raf.readFully(out); return out
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) {
            writes++
            raf.seek(startBlock * blockSize); raf.write(data)
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
        for (t in listOf("mkfs.vfat", "fsck.vfat"))
            assumeTrue("$t not available", run("sh", "-c", "command -v $t").first == 0)
        img = File.createTempFile("fat32runs", ".img")
        RandomAccessFile(img, "rw").use { it.setLength(256L shl 20) }
        // 4 KiB clusters, as on the matrix drives.
        val (rc, out) = run("mkfs.vfat", "-F", "32", "-s", "8", "-n", "RUNS", img.path)
        assertEquals("mkfs.vfat failed: $out", 0, rc)
    }

    @After
    fun tearDown() {
        if (::img.isInitialized) img.delete()
    }

    private fun <R> withFs(block: (Fat32FileSystem, CountingDevice) -> R): R {
        val dev = CountingDevice(img)
        try {
            return block(Fat32FileSystem.read(ByteBlockDevice(RawBlockDeviceAdapter(dev)))!!, dev)
        } finally { dev.close() }
    }

    private fun assertFsckClean(stage: String) {
        val (rc, out) = run("fsck.vfat", "-n", img.path)
        assertTrue("fsck.vfat reported problems after $stage (rc=$rc):\n$out", rc == 0)
    }

    private fun UsbFile.readAll(): ByteArray {
        val out = ByteArray(length.toInt()); read(0, ByteBuffer.wrap(out)); return out
    }

    private fun Fat32FileSystem.file(name: String) = rootDirectory.search(name)!!

    private fun sha256(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /**
     * 16 MiB in 1 MiB calls into a fresh file: 64 data commands per MiB at four
     * clusters each, one in runs, plus the FAT entries the call allocates.
     */
    @Test
    fun aSequentialWriteIssuesAFewCommandsPerMiB() {
        val mib = 1 shl 20
        val payload = ByteArray(16 * mib) { ((it * 31) xor (it ushr 9)).toByte() }
        withFs { fs, dev ->
            val f = fs.rootDirectory.createFile("seq.bin")
            dev.writes = 0
            for (i in 0 until 16) f.write(i.toLong() * mib, ByteBuffer.wrap(payload, i * mib, mib))
            val perMiB = dev.writes / 16.0
            assertTrue("%.1f write commands per MiB; expected at most 8".format(perMiB), perMiB <= 8.0)
            f.close()
        }
        withFs { fs, _ -> assertEquals(sha256(payload), sha256(fs.file("seq.bin").readAll())) }
        assertFsckClean("16 MiB in 1 MiB calls")
    }

    /**
     * Two files appended to in turn, so each chain alternates with the other's. A
     * run must end where the chain jumps, and a write across the jumps that starts
     * and ends inside clusters lands every byte where the chain says.
     */
    @Test
    fun aWriteAcrossAFragmentedChainLandsEveryByte() {
        val cluster = 4096
        val a = ByteArray(64 * cluster) { 'a'.code.toByte() }
        val b = ByteArray(64 * cluster) { 'b'.code.toByte() }
        withFs { fs, _ ->
            val fa = fs.rootDirectory.createFile("a.bin")
            val fb = fs.rootDirectory.createFile("b.bin")
            // Uneven pieces, so the runs between jumps are one and two clusters long.
            var off = 0
            var i = 0
            while (off < a.size) {
                val n = minOf(if (i++ % 3 == 0) 2 * cluster else cluster, a.size - off)
                fa.write(off.toLong(), ByteBuffer.wrap(a, off, n))
                fb.write(off.toLong(), ByteBuffer.wrap(b, off, n))
                off += n
            }
            fa.close(); fb.close()
        }
        val patch = ByteArray(40 * cluster + 1234) { (it * 7 + 3).toByte() }
        val expected = a.copyOf().also { patch.copyInto(it, 1000) }
        withFs { fs, dev ->
            dev.writes = 0
            fs.file("a.bin").run { write(1000, ByteBuffer.wrap(patch)); close() }
            // The chain jumps every one or two clusters, so 41 clusters take 20 or more
            // runs. Fewer would mean the file was not fragmented and this tested nothing.
            assertTrue("only ${dev.writes} writes: a.bin is not fragmented", dev.writes >= 20)
        }
        withFs { fs, _ ->
            assertArrayEquals(expected, fs.file("a.bin").readAll())
            assertArrayEquals("the other file's clusters were written into", b, fs.file("b.bin").readAll())
        }
        assertFsckClean("a write across a fragmented chain")
    }

    /** A write with a partial first and last cluster keeps the bytes around it. */
    @Test
    fun partialFirstAndLastClustersKeepTheirNeighbours() {
        val base = ByteArray(9 * 4096) { 'A'.code.toByte() }
        val patch = ByteArray(6 * 4096 + 100) { 'B'.code.toByte() }
        val expected = base.copyOf().also { patch.copyInto(it, 1000) }
        withFs { fs, _ -> fs.rootDirectory.createFile("p.bin").run { write(0, ByteBuffer.wrap(base)); close() } }
        withFs { fs, _ -> fs.file("p.bin").run { write(1000, ByteBuffer.wrap(patch)); close() } }
        withFs { fs, _ -> assertArrayEquals(expected, fs.file("p.bin").readAll()) }
        assertFsckClean("partial first and last clusters")
    }

    /** Many files written in uneven calls, each read back and the volume checked. */
    @Test
    fun manyFilesInUnevenCallsStayClean() {
        val sizes = intArrayOf(3 shl 20, (1 shl 20) + 4097, 65535, 777_777, 1, 4095)
        val files = (0 until 12).associate { i ->
            "f$i.bin" to ByteArray(sizes[i % sizes.size] + i * 513) { ((it * 13) xor i).toByte() }
        }
        withFs { fs, _ ->
            val dir = fs.rootDirectory.createDirectory("D")
            for ((name, data) in files) {
                val f = dir.createFile(name)
                var off = 0
                var k = 0
                while (off < data.size) {
                    val n = minOf(sizes[(k++ + name.length) % sizes.size], data.size - off)
                    f.write(off.toLong(), ByteBuffer.wrap(data, off, n)); off += n
                }
                f.close()
            }
        }
        withFs { fs, _ ->
            for ((name, data) in files)
                assertEquals(name, sha256(data), sha256(fs.file("D/$name").readAll()))
        }
        assertFsckClean("12 files in uneven calls")
    }
}

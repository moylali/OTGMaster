package app.fayaz.otgmaster.exfat

import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.fs.UsbFile
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * The exFAT write path's shape: data in runs of consecutive clusters, checked the
 * AGENTS.md way, content and `fsck.exfat` both. Drives the same libexfat and JNI
 * bridge the app ships, built for the host by scripts/build_host_native.sh.
 *
 * exFAT wrote at 0.7–0.8 MB/s against FAT32's 6–8 through the same USB and crypto
 * stack, because libexfat wrote one cluster per call (docs/VENDOR_FIXES.md E2), and
 * each call is a JNI upcall and its own device command. A host image cannot show the
 * speed, but it can count the commands, and the count is what the USB link pays for.
 */
class ExFatWriteRunsTest {

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

    companion object {
        private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "scripts/build_host_native.sh").exists() }

        private fun run(vararg cmd: String): Pair<Int, String> {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            return p.waitFor() to out
        }

        private fun toolMissing(tool: String) = run("sh", "-c", "command -v $tool").first != 0

        private var hostLib: File? = null

        @BeforeClass
        @JvmStatic
        fun buildHostLibrary() {
            if (toolMissing("gcc") || toolMissing("g++")) return
            val lib = File(repo, "app/build/host-native/libotg-host.so")
            val (rc, out) = run(File(repo, "scripts/build_host_native.sh").path, lib.path)
            if (rc != 0) throw AssertionError("host build of the native code failed:\n$out")
            System.setProperty("otg.native.hostlib", lib.absolutePath)
            hostLib = lib
        }
    }

    private lateinit var img: File

    @Before
    fun setUp() {
        assumeTrue("no host C toolchain", hostLib != null)
        for (t in listOf("mkfs.exfat", "fsck.exfat"))
            assumeTrue("$t not available", !toolMissing(t))
        img = File.createTempFile("exfatruns", ".img")
        RandomAccessFile(img, "rw").use { it.setLength(256L shl 20) }
        // 4 KiB clusters, as on the matrix drives: the worst case for a per-cluster write.
        val (rc, out) = run("mkfs.exfat", "-c", "4K", "-L", "OTGTEST", img.path)
        assertEquals("mkfs.exfat failed: $out", 0, rc)
    }

    @After
    fun tearDown() {
        if (::img.isInitialized) img.delete()
    }

    private fun <R> withFs(block: (ExFatFileSystem, CountingDevice) -> R): R {
        val dev = CountingDevice(img)
        val ptr = ExFatNative.mount(dev, false)
        assertNotEquals("libexfat refused the image", 0L, ptr)
        val fs = ExFatFileSystem(dev, ptr)
        try { return block(fs, dev) } finally { fs.unmount(); dev.close() }
    }

    private fun assertFsckClean(stage: String) {
        val (rc, out) = run("fsck.exfat", "-n", img.path)
        assertTrue("fsck.exfat reported problems after $stage (rc=$rc):\n$out", rc == 0)
    }

    /** Reads the whole file and closes the handle, as libexfat counts open references. */
    private fun UsbFile.readAll(): ByteArray {
        val out = ByteArray(length.toInt()); read(0, ByteBuffer.wrap(out)); close(); return out
    }

    private fun sha256(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /**
     * 16 MiB in 1 MiB calls into a fresh file. Cluster at a time this was 256 data
     * commands per MiB; in runs it is one per call, plus whatever metadata the call
     * touches.
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
            assertTrue("%.1f write commands per MiB; expected at most 4".format(perMiB), perMiB <= 4.0)
            f.close()
        }
        withFs { fs, _ ->
            assertEquals(sha256(payload), sha256(fs.rootDirectory.search("seq.bin")!!.readAll()))
        }
        assertFsckClean("16 MiB in 1 MiB calls")
    }

    /**
     * A file whose clusters are not consecutive: two files appended to in turn, so
     * each one's chain alternates with the other's. A run must end where the chain
     * jumps, and a write that spans the jumps, starting and ending inside clusters,
     * lands every byte where the chain says.
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
            fs.rootDirectory.search("a.bin")!!.run { write(1000, ByteBuffer.wrap(patch)); close() }
            // The chain jumps every one or two clusters, so 41 clusters take 20 or more
            // runs. Fewer would mean the file was not fragmented and this tested nothing.
            assertTrue("only ${dev.writes} writes: a.bin is not fragmented", dev.writes >= 20)
        }
        withFs { fs, _ ->
            assertArrayEquals(expected, fs.rootDirectory.search("a.bin")!!.readAll())
            assertArrayEquals("the other file's clusters were written into", b,
                fs.rootDirectory.search("b.bin")!!.readAll())
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
        withFs { fs, _ -> fs.rootDirectory.search("p.bin")!!.run { write(1000, ByteBuffer.wrap(patch)); close() } }
        withFs { fs, _ -> assertArrayEquals(expected, fs.rootDirectory.search("p.bin")!!.readAll()) }
        assertFsckClean("partial first and last clusters")
    }

    /** A write past the end leaves zeros between the old end and the new data. */
    @Test
    fun aWritePastTheEndLeavesZerosBehindIt() {
        withFs { fs, _ ->
            val junk = fs.rootDirectory.createFile("junk.bin")
            junk.write(0, ByteBuffer.wrap(ByteArray(8 shl 20) { 0xAA.toByte() }))
            junk.close()
        }
        withFs { fs, _ -> fs.rootDirectory.search("junk.bin")!!.delete() }
        withFs { fs, _ ->
            fs.rootDirectory.createFile("gap.bin").run {
                write(0, ByteBuffer.wrap(ByteArray(100) { 0x11 }))
                write(5 * 4096L + 50, ByteBuffer.wrap(ByteArray(3 * 4096) { 0x22 }))
                close()
            }
        }
        withFs { fs, _ ->
            val got = fs.rootDirectory.search("gap.bin")!!.readAll()
            val expected = ByteArray(8 * 4096 + 50).also {
                for (i in 0 until 100) it[i] = 0x11
                for (i in 5 * 4096 + 50 until 8 * 4096 + 50) it[i] = 0x22
            }
            assertArrayEquals(expected, got)
        }
        assertFsckClean("a write past the end")
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
                assertEquals(name, sha256(data), sha256(fs.rootDirectory.search("D/$name")!!.readAll()))
        }
        assertFsckClean("12 files in uneven calls")
    }
}

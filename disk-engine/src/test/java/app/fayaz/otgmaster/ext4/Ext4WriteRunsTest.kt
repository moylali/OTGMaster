package app.fayaz.otgmaster.ext4

import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.fs.UsbFile
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
 * The write path's shape (#36): data in runs, allocation from a goal, and metadata
 * written once per call — each checked the AGENTS.md way, content and e2fsck both.
 *
 * ext4 wrote 5–7× slower than FAT32 through the same USB and crypto stack because
 * every 4 KiB block was its own device command, plus ~11 metadata commands per call.
 * A host image cannot show the speed, but it can count the commands, and the count
 * is what the USB link pays for.
 */
class Ext4WriteRunsTest {

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
        for (t in listOf("mkfs.ext4", "e2fsck", "debugfs"))
            assumeTrue("$t not available", run("sh", "-c", "command -v $t").first == 0)
        img = File.createTempFile("ext4runs", ".img")
        img.deleteOnExit()
        RandomAccessFile(img, "rw").use { it.setLength(512L * 1024 * 1024) }
        val (rc, out) = run("mkfs.ext4", "-q", "-F", "-m", "0",
            "-E", "lazy_itable_init=0,lazy_journal_init=0", img.absolutePath)
        assertEquals("mkfs.ext4 failed: $out", 0, rc)
    }

    private fun <R> withFs(block: (Ext4FileSystem, CountingDevice) -> R): R {
        val dev = CountingDevice(img)
        try { return block(Ext4FileSystem.create(dev), dev) } finally { dev.close() }
    }

    private fun assertFsckClean(stage: String) {
        val (rc, out) = run("e2fsck", "-fn", img.absolutePath)
        assertTrue("e2fsck reported problems after $stage (rc=$rc):\n$out", rc == 0)
    }

    /** The leaf extents of [path], as debugfs lists them: (logical, physical, length). */
    private fun extents(path: String): List<LongArray> {
        val out = run("debugfs", "-R", "ex $path", img.absolutePath).second
        val row = Regex("""^\s*(\d+)/\s*(\d+)\s+\d+/\s*\d+\s+(\d+)\s*-\s*(\d+)\s+(\d+)\s*-\s*(\d+)\s+(\d+)""")
        return out.lines().mapNotNull { line ->
            val m = row.find(line) ?: return@mapNotNull null
            val (level, depth) = m.groupValues[1].toInt() to m.groupValues[2].toInt()
            if (level != depth) null
            else longArrayOf(m.groupValues[3].toLong(), m.groupValues[5].toLong(), m.groupValues[7].toLong())
        }
    }

    private fun UsbFile.readAll(): ByteArray {
        val out = ByteArray(length.toInt()); read(0, ByteBuffer.wrap(out)); return out
    }

    private fun sha256(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /**
     * 16 MiB in 1 MiB calls into a fresh file. Block at a time this was ~256 data
     * commands per MiB plus ~11 metadata commands per call; in runs it is one data
     * command per call plus the bitmap, descriptor, superblock count and inode.
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
        withFs { fs, _ ->
            assertEquals(sha256(payload), sha256(fs.rootDirectory.search("seq.bin")!!.readAll()))
        }
        assertFsckClean("16 MiB in 1 MiB calls")
    }

    /**
     * A block this write allocates starts as zeros, not as whatever the medium held.
     *
     * A partial first block used to be read from the medium and patched, so the
     * bytes before the write offset were the remains of a deleted file: readable by
     * anyone who opens the new file, and wrong, since a write past the end must
     * leave zeros behind it.
     */
    @Test
    fun aBlockAllocatedByAWriteStartsAsZeros() {
        withFs { fs, _ ->
            val junk = fs.rootDirectory.createFile("junk.bin")
            junk.write(0, ByteBuffer.wrap(ByteArray(8 shl 20) { 0xAA.toByte() }))
            junk.close()
        }
        withFs { fs, _ -> fs.rootDirectory.search("junk.bin")!!.delete() }
        withFs { fs, _ ->
            val f = fs.rootDirectory.createFile("new.bin")
            f.write(1000, ByteBuffer.wrap(ByteArray(100) { 0x11 }))
            f.write(3 * 4096L + 50, ByteBuffer.wrap(ByteArray(10) { 0x22 }))
            f.close()
        }
        withFs { fs, _ ->
            val got = fs.rootDirectory.search("new.bin")!!.readAll()
            val expected = ByteArray(3 * 4096 + 60).also {
                for (i in 1000 until 1100) it[i] = 0x11
                for (i in 3 * 4096 + 50 until 3 * 4096 + 60) it[i] = 0x22
            }
            val stale = got.indices.count { got[it] == 0xAA.toByte() }
            assertEquals("$stale bytes of the deleted file showed through", 0, stale)
            assertArrayEquals(expected, got)
        }
        assertFsckClean("writes past the end into reused blocks")
    }

    /** A write with a partial first and last block keeps the bytes around it. */
    @Test
    fun partialFirstAndLastBlocksKeepTheirNeighbours() {
        val base = ByteArray(5 * 4096) { 'A'.code.toByte() }
        val patch = ByteArray(3 * 4096) { 'B'.code.toByte() }
        val expected = base.copyOf().also { patch.copyInto(it, 1000) }
        withFs { fs, _ -> fs.rootDirectory.createFile("p.bin").run { write(0, ByteBuffer.wrap(base)); close() } }
        withFs { fs, _ -> fs.rootDirectory.search("p.bin")!!.run { write(1000, ByteBuffer.wrap(patch)); close() } }
        withFs { fs, _ -> assertArrayEquals(expected, fs.rootDirectory.search("p.bin")!!.readAll()) }
        assertFsckClean("partial first and last blocks")
    }

    /**
     * An append continues the file's last extent when the space after it is free.
     * First-fit from group 0 put it in the first hole instead, here one left at the
     * front of the volume by a deleted file, and the file split in two.
     */
    @Test
    fun anAppendContinuesTheFilesLastExtent() {
        withFs { fs, _ ->
            fs.rootDirectory.createFile("front.bin").run { write(0, ByteBuffer.wrap(ByteArray(64 * 1024) { 1 })); close() }
            fs.rootDirectory.createFile("grow.bin").run { write(0, ByteBuffer.wrap(ByteArray(1 shl 20) { 2 })); close() }
        }
        withFs { fs, _ -> fs.rootDirectory.search("front.bin")!!.delete() }
        withFs { fs, _ ->
            fs.rootDirectory.search("grow.bin")!!.run {
                write(1L shl 20, ByteBuffer.wrap(ByteArray(64 * 1024) { 3 })); close()
            }
        }
        val ex = extents("/grow.bin")
        assertEquals("grow.bin extents: ${ex.map { it.toList() }}", 1, ex.size)
        assertFsckClean("append")
    }

    /**
     * One file across several block groups, in calls of uneven size: runs break
     * only at metadata (the backup superblocks of sparse_super), and every byte and
     * every structure checks out.
     */
    @Test
    fun aWriteAcrossBlockGroupsStaysContiguousAndClean() {
        val sizes = intArrayOf(3 shl 20, (1 shl 20) + 4097, 65535, 777_777)
        val total = 200L shl 20
        val md = MessageDigest.getInstance("SHA-256")
        withFs { fs, _ ->
            val f = fs.rootDirectory.createDirectory("BIG").createFile("across.bin")
            var off = 0L; var i = 0
            while (off < total) {
                val n = minOf(sizes[i++ % sizes.size].toLong(), total - off).toInt()
                val chunk = ByteArray(n) { ((off + it) * 13 xor ((off + it) ushr 12)).toByte() }
                f.write(off, ByteBuffer.wrap(chunk)); md.update(chunk)
                off += n
            }
            f.close()
        }
        val expected = md.digest().joinToString("") { "%02x".format(it) }
        withFs { fs, _ ->
            // Streamed: 200 MiB in one array, next to the 4 GiB case's, exhausts the test heap.
            val f = fs.rootDirectory.search("BIG/across.bin")!!
            val verify = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(4 shl 20)
            var off = 0L
            while (off < f.length) {
                val n = minOf(buf.size.toLong(), f.length - off).toInt()
                f.read(off, ByteBuffer.wrap(buf, 0, n)); verify.update(buf, 0, n); off += n
            }
            assertEquals(expected, verify.digest().joinToString("") { "%02x".format(it) })
        }
        val ex = extents("/BIG/across.bin")
        // 51,200 blocks over two groups of 32,768: a handful of extents, not hundreds.
        assertTrue("across.bin has ${ex.size} extents", ex.size <= 4)
        assertFsckClean("200 MiB across block groups")
    }

    /**
     * A file whose tree has grown a level still extends its last extent on append,
     * rather than adding a leaf entry per call.
     */
    @Test
    fun extentsMergeOnceTheTreeHasALevel() {
        val chunk = ByteArray(4096) { 'x'.code.toByte() }
        withFs { fs, _ ->
            val files = (0 until 4).map { fs.rootDirectory.createFile("frag$it.bin") }
            repeat(40) { round -> files.forEach { it.write(round.toLong() * 4096, ByteBuffer.wrap(chunk)) } }
            files.forEach { it.close() }
        }
        val before = extents("/frag0.bin").size
        assertTrue("frag0.bin should have grown a tree: $before extents", before > 4)
        withFs { fs, _ ->
            val f = fs.rootDirectory.search("frag0.bin")!!
            repeat(50) { i -> f.write((40L + i * 16) * 4096, ByteBuffer.wrap(ByteArray(16 * 4096) { 7 })) }
            f.close()
        }
        val after = extents("/frag0.bin").size
        assertTrue("50 appends added ${after - before} extents", after - before <= 1)
        assertFsckClean("appends to a depth-1 tree")
    }

    /**
     * Moving a directory changes the link counts of both parents (its ".." moves
     * from one to the other), and both have to reach the disk with the move.
     */
    @Test
    fun movingADirectoryUpdatesBothParentsOnDisk() {
        withFs { fs, _ ->
            val root = fs.rootDirectory
            val a = root.createDirectory("a")
            val b = root.createDirectory("b")
            a.createDirectory("moving").moveTo(b)
        }
        assertFsckClean("moving a directory between parents")
    }
}

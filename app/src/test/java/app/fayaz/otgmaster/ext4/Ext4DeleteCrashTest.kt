package app.fayaz.otgmaster.ext4

import app.fayaz.otgmaster.block.RawBlockDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Deleting or shrinking a file must be safe to interrupt (issue #33).
 *
 * The driver bypasses the journal, so crash safety rests on write order: a block
 * may be marked free only once nothing on disk points at it. Interrupted the other
 * way round, a live file points at blocks the bitmap calls free, and the next
 * writer — Linux mounts ext4 read-write without fsck — can hand them out again.
 * That is what a Huawei P20 Lite left on device-matrix D2L1EXT4 when a 2.2 GB
 * delete was stopped 77 minutes in: `Block bitmap differences: +(774728--914059)`,
 * every block owned by the still-linked `/BENCH_BIG/big.bin`.
 *
 * Here the delete or truncate runs against a device that stops accepting writes
 * after N of them, for a spread of N, and e2fsck must find at most leaked blocks
 * (`-` differences, or an unattached inode) — never a `+` difference or a
 * multiply-claimed block. And the delete itself must not cost writes per block:
 * on the drive that is what made it take over an hour.
 */
class Ext4DeleteCrashTest {

    /** Counts writes, and fails every write after the first [allow] of them. */
    private class CutOffDevice(file: File, private val allow: Int = Int.MAX_VALUE) : RawBlockDevice {
        private val raf = RandomAccessFile(file, "rw")
        private val length = file.length()
        var writes = 0
        override val blockSize = 512
        override val blockCount get() = length / blockSize
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            val out = ByteArray(blockCount * blockSize)
            raf.seek(startBlock * blockSize); raf.readFully(out); return out
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) {
            if (writes >= allow) throw IOException("device gone after $allow writes")
            writes++
            raf.seek(startBlock * blockSize); raf.write(data)
        }
        override fun close() = raf.close()
    }

    private lateinit var img: File
    private lateinit var base: File

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private val fileBytes = 40 * 1024 * 1024   // 10,240 blocks of 4 KiB

    @Before
    fun setUp() {
        assumeTrue("mkfs.ext4 not available", run("sh", "-c", "command -v mkfs.ext4").first == 0)
        assumeTrue("e2fsck not available", run("sh", "-c", "command -v e2fsck").first == 0)
        img = File.createTempFile("ext4del", ".img")
        base = File.createTempFile("ext4del-base", ".img")
        RandomAccessFile(img, "rw").use { it.setLength(256L * 1024 * 1024) }
        val (rc, out) = run("mkfs.ext4", "-q", "-F", "-m", "0",
            "-E", "lazy_itable_init=0,lazy_journal_init=0", img.absolutePath)
        assertEquals("mkfs.ext4 failed: $out", 0, rc)
        val dev = CutOffDevice(img)
        try {
            val root = Ext4FileSystem.create(dev).rootDirectory
            root.createDirectory("BIG").createFile("big.bin").apply {
                write(0, ByteBuffer.wrap(Random(33).nextBytes(fileBytes))); flush()
            }
        } finally { dev.close() }
        val (fsck, fsckOut) = run("e2fsck", "-fn", img.absolutePath)
        assertEquals("the starting volume must be clean:\n$fsckOut", 0, fsck)
        img.copyTo(base, overwrite = true)
    }

    @After fun tearDown() { img.delete(); base.delete() }

    /**
     * What e2fsck says that is not safe: blocks in use but marked free, or shared.
     * Leaks and an unattached inode are what an interrupted unlink-first delete
     * leaves, and fsck reclaims them without losing anything else.
     */
    private fun unsafeFindings(): List<String> {
        val (_, out) = run("e2fsck", "-fn", img.absolutePath)
        return out.lines().filter { l ->
            Regex("""Block bitmap differences:.*\+""").containsMatchIn(l) ||
                l.contains("Multiply-claimed", ignoreCase = true) ||
                l.contains("shared with", ignoreCase = true)
        }
    }

    private fun interruptAt(n: Int, op: (big: me.jahnen.libaums.core.fs.UsbFile) -> Unit): List<String> {
        base.copyTo(img, overwrite = true)
        val dev = CutOffDevice(img, allow = n)
        try {
            val big = Ext4FileSystem.create(dev).rootDirectory.search("BIG/big.bin")!!
            runCatching { op(big) }
        } finally { dev.close() }
        return unsafeFindings()
    }

    private val cutPoints = listOf(1, 2, 3, 4, 5, 6, 8, 12, 20, 50, 200, 2000)

    @Test
    fun anInterruptedDeleteOnlyLeaks() {
        for (n in cutPoints) {
            val bad = interruptAt(n) { it.delete() }
            assertTrue("delete cut off after $n writes left:\n${bad.joinToString("\n")}", bad.isEmpty())
        }
    }

    @Test
    fun anInterruptedTruncateOnlyLeaks() {
        for (n in cutPoints) {
            val bad = interruptAt(n) { it.length = 4096; it.flush() }
            assertTrue("truncate cut off after $n writes left:\n${bad.joinToString("\n")}", bad.isEmpty())
        }
    }

    @Test
    fun deletingALargeFileCostsWritesPerGroupNotPerBlock() {
        base.copyTo(img, overwrite = true)
        val dev = CutOffDevice(img)
        try {
            val root = Ext4FileSystem.create(dev).rootDirectory
            root.search("BIG/big.bin")!!.delete()
            println("deleting 10,240 blocks: ${dev.writes} writes")
            assertTrue("deleting 10,240 blocks took ${dev.writes} writes", dev.writes < 100)
        } finally { dev.close() }
        val (rc, out) = run("e2fsck", "-fn", img.absolutePath)
        assertEquals("e2fsck after a complete delete:\n$out", 0, rc)
    }
}

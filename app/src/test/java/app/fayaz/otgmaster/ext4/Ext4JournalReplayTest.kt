package app.fayaz.otgmaster.ext4

import app.fayaz.otgmaster.block.CachedBlockDevice
import app.fayaz.otgmaster.block.RawBlockDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Journal replay ([Ext4Journal]) against e2fsprogs' own.
 *
 * debugfs can write jbd2 transactions into an image's journal without the kernel
 * (`jo` / `jw` / `jc`), leaving it flagged `needs_recovery` exactly as a cut-off mount
 * does, and can replay them (`jr`) with the recovery.c that e2fsck and the kernel
 * share. Each case builds one journal, replays a copy of it with debugfs as the
 * reference, replays the original with this driver, and requires the two images to
 * match block for block. Only the superblock's block is allowed to differ, and only
 * in fields debugfs stamps when it closes a volume, which are listed.
 *
 * Then the usual pair: e2fsck finds the volume clean, and the volume now takes writes.
 */
class Ext4JournalReplayTest {

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

    private lateinit var dir: File
    private lateinit var img: File

    private fun run(vararg cmd: String, input: String? = null): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (input != null) p.outputStream.use { it.write(input.toByteArray()) } else p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun toolMissing(tool: String) = run("sh", "-c", "command -v $tool").first != 0

    @Before
    fun setUp() {
        for (t in listOf("mkfs.ext4", "e2fsck", "debugfs", "dumpe2fs")) assumeTrue("$t not available", !toolMissing(t))
        dir = kotlin.io.path.createTempDirectory("ext4journal").toFile()
        dir.deleteOnExit()
        img = File(dir, "vol.img")
        // 4 KiB blocks like the real drives (mkfs picks 1 KiB under 512 MiB), and
        // big enough for the blocks the cases log: 32768 blocks.
        RandomAccessFile(img, "rw").use { it.setLength(128L * 1024 * 1024) }
        val (rc, out) = run("mkfs.ext4", "-q", "-F", "-m", "0", "-b", "4096",
            "-E", "lazy_itable_init=0,lazy_journal_init=0", img.absolutePath)
        assertEquals("mkfs.ext4 failed: $out", 0, rc)
    }

    /** A file of [blocks] 4 KiB blocks, each a distinct pattern, for debugfs to log. */
    private fun payload(name: String, blocks: Int, salt: Int): File =
        File(dir, name).also { f ->
            f.writeBytes(ByteArray(blocks * 4096) { ((it * 7 + salt * 131) xor (it ushr 12)).toByte() })
        }

    /** Run debugfs [commands] against the image, read-write. */
    private fun debugfs(image: File, commands: String) {
        val (rc, out) = run("debugfs", "-w", "-f", "-", image.absolutePath, input = commands)
        // debugfs reports a failed command on stdout and still exits 0.
        assertTrue("debugfs failed: $out", rc == 0 && out.lines().none {
            it.contains("journal_write:") || it.contains("journal_open:") || it.contains("journal_run:") })
    }

    private fun features(image: File): String =
        run("dumpe2fs", "-h", image.absolutePath).second.lines()
            .first { it.startsWith("Filesystem features:") }

    private fun journalLine(image: File, key: String): String =
        run("dumpe2fs", "-h", image.absolutePath).second.lines()
            .first { it.startsWith(key) }.substringAfter(":").trim()

    private fun <R> withFs(block: (Ext4FileSystem) -> R): R {
        // A real mount reads through the block cache, so the replay's writes must
        // keep it consistent: read back through the same cache afterwards.
        val dev = CachedBlockDevice(FileBlockDevice(img))
        try { return block(Ext4FileSystem.create(dev)) } finally { dev.close() }
    }

    private fun assertFsckClean(stage: String) {
        val (rc, out) = run("e2fsck", "-fn", img.absolutePath)
        assertTrue("e2fsck reported problems after $stage (rc=$rc):\n$out", rc == 0)
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Replay [img] with debugfs into a copy and with this driver in place, then
     * require them to match. Returns this driver's result.
     */
    private fun replayAndCompare(): Ext4Journal.Result {
        assertTrue("the image should need recovery", features(img).contains("needs_recovery"))
        val ref = File(dir, "ref.img")
        img.copyTo(ref, overwrite = true)
        debugfs(ref, "jr\n")
        assertFalse("debugfs left needs_recovery set", features(ref).contains("needs_recovery"))

        val result = withFs { fs ->
            assertTrue("read-only until replayed", fs.isReadOnly)
            assertTrue(fs.needsJournalRecovery)
            fs.replayJournal().also { assertFalse("writable after the replay", fs.isReadOnly) }
        }

        // Block for block. Block 0 holds the superblock, where debugfs also stamps
        // s_wtime, s_kbytes_written and the checksum over them when it closes, and
        // s_lastcheck after a replay (debugfs 1.47.2 writes a date in 2514 there).
        RandomAccessFile(img, "r").use { a ->
            RandomAccessFile(ref, "r").use { b ->
                val blocks = img.length() / 4096
                val x = ByteArray(4096); val y = ByteArray(4096)
                for (blk in 0 until blocks) {
                    a.seek(blk * 4096); a.readFully(x)
                    b.seek(blk * 4096); b.readFully(y)
                    if (x.contentEquals(y)) continue
                    if (blk == 0L) {
                        val stamped = setOf(1024 + 0x30, 1024 + 0x31, 1024 + 0x32, 1024 + 0x33) +   // s_wtime
                            (1024 + 0x178 until 1024 + 0x180) +                                       // s_kbytes_written
                            (1024 + 0x40 until 1024 + 0x44) +                                         // s_lastcheck
                            (1024 + 0x274 until 1024 + 0x275) +                                       // s_wtime_hi
                            (1024 + 0x277 until 1024 + 0x278) +                                       // s_lastcheck_hi
                            (1024 + 0x3FC until 1024 + 0x400)                                          // s_checksum
                        val diff = x.indices.filter { x[it] != y[it] && it !in stamped }
                        assertTrue("superblock differs from debugfs' replay at offsets $diff", diff.isEmpty())
                        continue
                    }
                    val diff = x.indices.filter { x[it] != y[it] }
                    fail("block $blk differs from debugfs' replay at ${diff.size} bytes, first at ${diff.first()}")
                }
            }
        }
        assertEquals("journal start", "0", journalLine(img, "Journal start"))
        assertEquals("journal sequence", journalLine(ref, "Journal sequence"), journalLine(img, "Journal sequence"))
        assertFsckClean("replay")
        return result
    }

    /** After a replay the volume takes writes, and they leave it clean. */
    private fun assertWritableAfterwards() {
        withFs { fs ->
            fs.rootDirectory.createFile("after-replay.txt").run {
                write(0, ByteBuffer.wrap("written after the journal replay".toByteArray()))
                close()
            }
            fs.unmount()
        }
        assertFsckClean("a write after the replay")
        withFs { fs ->
            assertFalse(fs.isReadOnly)
            val f = fs.rootDirectory.search("after-replay.txt")!!
            val buf = ByteArray(f.length.toInt()); f.read(0, ByteBuffer.wrap(buf))
            assertEquals("written after the journal replay", String(buf))
        }
    }

    @Test
    fun replaysCommittedTransactionsAndHonoursRevokes() {
        payload("a.bin", 2, 1); payload("b.bin", 1, 2); payload("c.bin", 1, 3)
        debugfs(img, """
            jo
            jw -b 20000,20001 ${dir}/a.bin
            jw -b 20002 ${dir}/b.bin
            jw -b 20003 -r 20000 ${dir}/c.bin
            jc
        """.trimIndent() + "\n")
        val r = replayAndCompare()
        assertEquals("three committed transactions", 3, r.transactions)
        assertEquals("20000 is revoked by a later transaction", 1, r.blocksRevoked)
        assertWritableAfterwards()
    }

    /** The format the kernel writes on a metadata_csum volume: CSUM_V3, 64-bit tags. */
    @Test
    fun replaysAChecksummedJournal() {
        payload("a.bin", 3, 4); payload("b.bin", 1, 5)
        debugfs(img, """
            jo -c
            jw -b 21000,21001,21002 ${dir}/a.bin
            jw -b 21003 ${dir}/b.bin
            jc
        """.trimIndent() + "\n")
        assertTrue(journalLine(img, "Journal features").contains("journal_checksum_v3"))
        val r = replayAndCompare()
        assertEquals(2, r.transactions)
        assertEquals(4, r.blocksReplayed)
        assertWritableAfterwards()
    }

    /** A transaction whose commit block never landed is not replayed, by either. */
    @Test
    fun anUncommittedTransactionIsDiscarded() {
        payload("a.bin", 1, 6); payload("b.bin", 1, 7)
        debugfs(img, """
            jo
            jw -b 22000 ${dir}/a.bin
            jw -c -b 22001 ${dir}/b.bin
            jc
        """.trimIndent() + "\n")
        val r = replayAndCompare()
        assertEquals("only the committed transaction", 1, r.transactions)
        assertEquals(1, r.blocksReplayed)
    }

    /**
     * A logged block whose checksum fails inside a committed transaction: recovery.c
     * skips that one block and replays the rest; this driver refuses the whole repair
     * and writes nothing, leaving the volume read-only for e2fsck.
     */
    @Test
    fun aCorruptLoggedBlockStopsTheRepairBeforeAnyWrite() {
        payload("a.bin", 2, 8)
        debugfs(img, "jo -c\njw -b 23000,23001 ${dir}/a.bin\njc\n")
        // The log starts at journal block 1 (descriptor), so its data is at 2 and 3.
        val journalBlock2 = journalPhysical(2)
        RandomAccessFile(img, "rw").use { it.seek(journalBlock2 * 4096 + 100); it.write(0x5A) }
        val before = sha256(img)

        withFs { fs ->
            try {
                fs.replayJournal()
                fail("a replay went ahead over a block that fails its checksum")
            } catch (e: Ext4Journal.NotReplayableException) {
                assertTrue(e.message!!, e.message!!.contains("checksum"))
            }
            assertTrue("still read-only", fs.isReadOnly)
        }
        assertEquals("not one byte of the image changed", before, sha256(img))
    }

    /** needs_recovery with an empty log: nothing to replay, and the flag is cleared. */
    @Test
    fun anEmptyLogOnlyClearsTheFlag() {
        RandomAccessFile(img, "rw").use { raf ->
            raf.seek(1024L + 96)
            val incompat = ByteArray(4).also { raf.readFully(it) }
            incompat[0] = (incompat[0].toInt() or 0x4).toByte()
            raf.seek(1024L + 96); raf.write(incompat)
        }
        withFs { fs ->
            assertTrue(fs.needsJournalRecovery)
            val r = fs.replayJournal()
            assertEquals(0, r.transactions)
            assertFalse(fs.isReadOnly)
        }
        assertFalse(features(img).contains("needs_recovery"))
        assertFsckClean("clearing the flag")
        assertWritableAfterwards()
    }

    /** A partition the user mounted read-only is never repaired. */
    @Test
    fun aReadOnlyMountIsNotOfferedTheRepair() {
        payload("a.bin", 1, 9)
        debugfs(img, "jo\njw -b 24000 ${dir}/a.bin\njc\n")
        val dev = CachedBlockDevice(FileBlockDevice(img))
        try {
            val fs = Ext4FileSystem.create(dev, readOnly = true)
            assertTrue(fs.isReadOnly)
            assertFalse(fs.needsJournalRecovery)
        } finally { dev.close() }
    }

    /** Physical block of journal block [n], via debugfs' block map of inode 8. */
    private fun journalPhysical(n: Int): Long {
        val out = run("debugfs", "-R", "bmap <8> $n", img.absolutePath).second
        return out.lines().last { it.isNotBlank() }.trim().toLong()
    }
}

package app.fayaz.otgmaster.ext4

import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.fs.UsbFile
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
 * Runs the real ext4 write path against a loopback image and then hands the
 * image to `e2fsck`.
 *
 * This exists because content hashing cannot detect the bug class that
 * destroyed a physical test drive.  The on-device benchmark reported
 * `write verify: ALL PASSED` — SHA-256 matched on a cached read, a
 * cache-dropped read and a read after remount — while the filesystem's
 * superblock, inode, bitmap and group-descriptor checksums were all being
 * written with a seed read from the wrong superblock offset.  A hash of the
 * bytes we just wrote cannot see that; `e2fsck` sees it immediately.
 *
 * So every write case here asserts twice: the content is what we wrote, and
 * the filesystem is still structurally valid afterwards.
 */
class Ext4WriteFsckTest {

    /** ext4 image backed by a plain file, presented as 512-byte sectors. */
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

    private lateinit var img: File

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun toolMissing(tool: String) = run("sh", "-c", "command -v $tool").first != 0

    @Before
    fun setUp() {
        assumeTrue("mkfs.ext4 not available", !toolMissing("mkfs.ext4"))
        assumeTrue("e2fsck not available", !toolMissing("e2fsck"))

        img = File.createTempFile("ext4write", ".img")
        img.deleteOnExit()
        // 512 MiB is enough to span several block groups, so allocation has to
        // walk past group 0 — where the uninitialised-bitmap bug lived.
        RandomAccessFile(img, "rw").use { it.setLength(512L * 1024 * 1024) }

        // Same options as scripts/prepare_drive_b.sh, so the feature set under
        // test matches the physical fixture drives (metadata_csum,
        // metadata_csum_seed, 64bit, extents).
        val (rc, out) = run(
            "mkfs.ext4", "-q", "-F", "-m", "0",
            "-E", "lazy_itable_init=0,lazy_journal_init=0", img.absolutePath
        )
        assertEquals("mkfs.ext4 failed: $out", 0, rc)
    }

    /** Fail with e2fsck's own report if the filesystem is not clean. */
    private fun assertFsckClean(stage: String) {
        val (rc, out) = run("e2fsck", "-fn", img.absolutePath)
        // e2fsck exit codes: 0 = clean, 1 = errors corrected, 4 = left uncorrected.
        assertTrue("e2fsck reported problems after $stage (rc=$rc):\n$out", rc == 0)
    }

    /** Open the image, run [block] against it, and always close the device. */
    private fun <R> withFs(block: (Ext4FileSystem) -> R): R {
        val dev = FileBlockDevice(img)
        try {
            return block(Ext4FileSystem.create(dev))
        } finally {
            dev.close()
        }
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b)
            .joinToString("") { "%02x".format(it) }

    private fun UsbFile.writeAll(data: ByteArray) {
        write(0, ByteBuffer.wrap(data))
        flush()
    }

    private fun UsbFile.readAll(): ByteArray {
        val out = ByteArray(length.toInt())
        read(0, ByteBuffer.wrap(out))
        return out
    }

    @Test
    fun freshlyFormattedVolumeIsCleanBeforeWeTouchIt() {
        assertFsckClean("mkfs (control)")
    }

    @Test
    fun creatingFilesAndDirectoriesLeavesTheFilesystemClean() {
        withFs { f ->
            val root = f.rootDirectory
            val dir = root.createDirectory("subdir")
            dir.createFile("a.txt").writeAll("hello ext4".toByteArray())
            root.createFile("b.txt").writeAll(ByteArray(5000) { it.toByte() })
        }
        assertFsckClean("create files and directories")
    }

    @Test
    fun writtenContentReadsBackByteIdenticalAcrossRemount() {
        val payload = ByteArray(3 * 1024 * 1024) { ((it * 31) xor (it shr 11)).toByte() }
        val expected = sha256(payload)

        withFs { it.rootDirectory.createFile("payload.bin").writeAll(payload) }

        // Re-open from scratch so nothing is served from in-memory state.
        withFs { f ->
            val got = f.rootDirectory.search("payload.bin")!!.readAll()
            assertEquals("content changed across remount", expected, sha256(got))
        }
        assertFsckClean("3 MiB write")
    }

    @Test
    fun overwritingExistingContentAtUnalignedOffsetIsCorrect() {
        val base = ByteArray(8192) { 'A'.code.toByte() }
        val patch = ByteArray(700) { 'B'.code.toByte() }
        val expected = base.copyOf().also { patch.copyInto(it, 1234) }

        withFs { it.rootDirectory.createFile("mod.bin").writeAll(base) }
        withFs { f ->
            val file = f.rootDirectory.search("mod.bin")!!
            file.write(1234, ByteBuffer.wrap(patch))
            file.flush()
        }
        withFs { f ->
            val got = f.rootDirectory.search("mod.bin")!!.readAll()
            assertEquals(sha256(expected), sha256(got))
        }
        assertFsckClean("unaligned overwrite")
    }

    @Test
    fun deletingFilesReturnsSpaceAndLeavesTheFilesystemClean() {
        withFs { f ->
            val root = f.rootDirectory
            repeat(12) { i ->
                root.createFile("tmp$i.bin").writeAll(ByteArray(64 * 1024) { i.toByte() })
            }
        }
        assertFsckClean("bulk create")

        withFs { f ->
            val root = f.rootDirectory
            repeat(12) { i -> root.search("tmp$i.bin")!!.delete() }
        }
        assertFsckClean("bulk delete")
    }

    /**
     * Crosses the 32-bit size boundary, where i_size_high and the extent tree's
     * per-extent limits come into play.  Verified by hash, not just by fsck,
     * and read back after a reopen.
     */
    @Test
    fun largeFileAboveFourGiBRoundTripsAndStaysClean() {
        // Opt-in: writes >4 GiB, so it is a release-gate check rather than
        // something every push pays for.  Enable with
        // OTG_EXT4_LARGE_FILE_TEST=1 ./gradlew :app:testDebugUnitTest
        assumeTrue(
            "set OTG_EXT4_LARGE_FILE_TEST=1 to run the >4 GiB case",
            System.getenv("OTG_EXT4_LARGE_FILE_TEST") == "1"
        )
        assumeTrue(
            "needs ~6 GiB of free temp space",
            img.parentFile.usableSpace > 7L * 1024 * 1024 * 1024
        )
        RandomAccessFile(img, "rw").use { it.setLength(6L * 1024 * 1024 * 1024) }
        val (rc, out) = run(
            "mkfs.ext4", "-q", "-F", "-m", "0",
            "-E", "lazy_itable_init=0,lazy_journal_init=0", img.absolutePath
        )
        assertEquals("mkfs.ext4 failed: $out", 0, rc)

        val total = 4L * 1024 * 1024 * 1024 + 1024 * 1024  // 4 GiB + 1 MiB
        val chunk = ByteArray(1 shl 20) { (it and 0xFF).toByte() }
        val digest = MessageDigest.getInstance("SHA-256")

        withFs { f ->
            val file = f.rootDirectory.createFile("huge.bin")
            var off = 0L
            while (off < total) {
                val n = minOf(chunk.size.toLong(), total - off).toInt()
                file.write(off, ByteBuffer.wrap(chunk, 0, n))
                digest.update(chunk, 0, n)
                off += n
            }
            file.flush()
        }
        val expected = digest.digest().joinToString("") { "%02x".format(it) }

        withFs { f ->
            val file = f.rootDirectory.search("huge.bin")!!
            assertEquals("size above 4 GiB not recorded", total, file.length)
            val verify = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1 shl 20)
            var off = 0L
            while (off < total) {
                val n = minOf(buf.size.toLong(), total - off).toInt()
                read(file, off, buf, n)
                verify.update(buf, 0, n)
                off += n
            }
            assertEquals(
                "content above 4 GiB changed",
                expected,
                verify.digest().joinToString("") { "%02x".format(it) })
        }
        assertFsckClean("4 GiB+ write")
    }

    private fun read(file: UsbFile, offset: Long, into: ByteArray, len: Int) {
        file.read(offset, ByteBuffer.wrap(into, 0, len))
    }
}

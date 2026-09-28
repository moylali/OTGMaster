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
    private fun <R> withFs(cached: Boolean = false, block: (Ext4FileSystem) -> R): R {
        val raw = FileBlockDevice(img)
        // On a device every ext4 read and write goes through CachedBlockDevice.
        // A test that talks to the image directly cannot see a bug that depends
        // on what the cache serves back, so the cached path is exercised too.
        val dev: app.fayaz.otgmaster.block.RawBlockDevice =
            if (cached) app.fayaz.otgmaster.block.CachedBlockDevice(raw) else raw
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
     * Deleting a file whose extent tree grew past the four inline entries must
     * release its blocks, including the leaf blocks of the tree itself.
     *
     * Interleaving appends across several files fragments each one, so no run is
     * contiguous with the previous and the merge in appendExtent cannot collapse
     * them — the tree is forced to depth 1 without writing gigabytes.
     */
    @Test
    fun deletingAFragmentedFileReleasesItsExtentTree() {
        val chunk = ByteArray(4096) { 'x'.code.toByte() }
        withFs { f ->
            val root = f.rootDirectory
            val files = (0 until 4).map { root.createFile("frag$it.bin") }
            repeat(40) { round ->
                files.forEach { file ->
                    file.write(round.toLong() * chunk.size, ByteBuffer.wrap(chunk))
                }
            }
            files.forEach { it.flush() }
        }
        assertFsckClean("fragmented create")

        withFs { f ->
            val root = f.rootDirectory
            (0 until 4).forEach { root.search("frag$it.bin")!!.delete() }
        }
        assertFsckClean("fragmented delete")
    }

    /**
     * Mirrors what the benchmark's write-verify section actually does: a file
     * inside a subdirectory, written 64 KiB at a time through one handle, then
     * the whole directory removed.
     *
     * The suite reported "write verify: ALL PASSED" on hardware while leaving
     * 3616 blocks allocated and owned by nothing, so the shape of the write
     * matters, not just the total size.
     */
    @Test
    fun deletingADirectoryWrittenInChunksReleasesEveryBlock() {
        val chunk = ByteArray(64 * 1024) { (it and 0xFF).toByte() }
        val total = 16 * 1024 * 1024L

        withFs { f ->
            val dir = f.rootDirectory.createDirectory("BENCH_WRITE")
            val file = dir.createFile("verify.bin")
            var off = 0L
            while (off < total) {
                file.write(off, ByteBuffer.wrap(chunk))
                off += chunk.size
            }
            file.flush()
        }
        assertFsckClean("16 MiB chunked write")

        withFs { f ->
            val dir = f.rootDirectory.search("BENCH_WRITE")!!
            dir.search("verify.bin")!!.delete()
            dir.delete()
        }
        assertFsckClean("delete of chunk-written file and its directory")
    }

    /**
     * The same write-and-delete cycle, but through CachedBlockDevice, which is
     * what a real mount uses.
     *
     * On hardware this sequence reported "write verify: ALL PASSED" and still
     * left 3616 blocks allocated and owned by no inode; the uncached version of
     * this test passes, so the cache is the only layer that differs.
     */
    @Test
    fun deletingThroughTheBlockCacheReleasesEveryBlock() {
        val chunk = ByteArray(64 * 1024) { (it and 0xFF).toByte() }
        val total = 16 * 1024 * 1024L

        withFs(cached = true) { f ->
            val dir = f.rootDirectory.createDirectory("BENCH_WRITE")
            val file = dir.createFile("verify.bin")
            var off = 0L
            while (off < total) {
                file.write(off, ByteBuffer.wrap(chunk))
                off += chunk.size
            }
            file.flush()
        }
        assertFsckClean("cached 16 MiB chunked write")

        withFs(cached = true) { f ->
            val dir = f.rootDirectory.search("BENCH_WRITE")!!
            dir.search("verify.bin")!!.delete()
            dir.delete()
        }
        assertFsckClean("cached delete")
    }

    /**
     * A large file written into a fragmented volume, then deleted.
     *
     * The hardware drive already holds 2 GiB of fixtures, so the benchmark's
     * 16 MiB file lands in holes and its extent tree is wide rather than the
     * one or two extents a clean image produces.  That run leaked 3616 blocks
     * in group 0 while reporting every check as passing, so the tree's shape at
     * delete time is what this reproduces.
     */
    @Test
    fun deletingALargeFileFromAFragmentedVolumeReleasesEveryBlock() {
        val filler = ByteArray(64 * 1024) { 'f'.code.toByte() }
        withFs { f ->
            val root = f.rootDirectory
            repeat(300) { i -> root.createFile("fill$i.bin").writeAll(filler) }
        }
        // Punch alternating holes so the next allocation cannot be contiguous.
        withFs { f ->
            val root = f.rootDirectory
            (0 until 300 step 2).forEach { i -> root.search("fill$i.bin")!!.delete() }
        }
        assertFsckClean("fragmenting fill")

        val chunk = ByteArray(64 * 1024) { (it and 0xFF).toByte() }
        withFs { f ->
            val file = f.rootDirectory.createDirectory("BENCH_WRITE").createFile("verify.bin")
            var off = 0L
            while (off < 8 * 1024 * 1024L) {
                file.write(off, ByteBuffer.wrap(chunk))
                off += chunk.size
            }
            file.flush()
        }
        assertFsckClean("write into fragmented free space")

        withFs { f ->
            val dir = f.rootDirectory.search("BENCH_WRITE")!!
            dir.search("verify.bin")!!.delete()
            dir.delete()
        }
        assertFsckClean("delete of fragmented large file")
    }

    /**
     * A write whose file is never flushed must still leave the volume consistent.
     *
     * allocateBlocks() makes the bitmap and superblock durable immediately and the
     * data blocks go straight to the medium, but the extent tree lives in the
     * in-memory inode.  If nothing writes that inode back, the bitmap claims
     * blocks no inode references — orphaned blocks, exactly what e2fsck reports
     * as leaked.  Every other test here calls flush(), which is why none of them
     * could see it.
     */
    @Test
    fun aWriteThatIsNeverFlushedLeavesNoOrphanedBlocks() {
        val chunk = ByteArray(64 * 1024) { (it and 0xFF).toByte() }
        withFs { f ->
            val file = f.rootDirectory.createFile("unflushed.bin")
            var off = 0L
            while (off < 4 * 1024 * 1024L) {
                file.write(off, ByteBuffer.wrap(chunk))
                off += chunk.size
            }
            // Deliberately no flush() and no close(): the caller walks away.
        }
        assertFsckClean("write with no flush")
    }

    /**
     * A depth-1 tree whose second leaf covers blocks *below* the first must
     * still read back correctly.
     *
     * extentSearch picks the last index entry with ei_block <= target, so the
     * index array has to stay sorted.  Appending a new index entry blindly is
     * right for ascending writes and wrong the moment a write lands before an
     * existing leaf's range, which sends lookups to the wrong leaf.
     *
     * Built by fragmenting a file high up until its leaf fills, then filling the
     * hole left at block 0 — the new leaf's ei_block is then lower than the
     * existing entry's and must be inserted first, not appended.
     */
    @Test
    fun aLeafCoveringLowerBlocksIsIndexedInOrder() {
        val blk = fs_blockSizeGuess
        val high = ByteArray(blk) { 'H'.code.toByte() }
        val low  = ByteArray(blk) { 'L'.code.toByte() }

        withFs { f ->
            val root = f.rootDirectory
            val target = root.createFile("outoforder.bin")
            val spacer = root.createFile("spacer.bin")
            // Interleave so no two of target's runs are physically adjacent and
            // the merge in appendExtent cannot collapse them.
            for (i in 0 until 400) {
                target.write((1000L + i) * blk, ByteBuffer.wrap(high))
                spacer.write(i.toLong() * blk, ByteBuffer.wrap(low))
            }
            target.flush(); spacer.flush()
            // Now fill the hole below everything already indexed.
            target.write(0L, ByteBuffer.wrap(low))
            target.flush()
        }
        assertFsckClean("out-of-order depth-1 index")

        withFs { f ->
            val target = f.rootDirectory.search("outoforder.bin")
                ?: throw AssertionError(
                    "outoforder.bin is gone after the out-of-order write — an " +
                    "unsorted index sends lookups to the wrong leaf"
                )
            val head = ByteArray(blk)
            target.read(0L, ByteBuffer.wrap(head))
            assertEquals("block 0 must read back as written", 'L'.code.toByte(), head[0])
            val tail = ByteArray(blk)
            target.read(1000L * blk, ByteBuffer.wrap(tail))
            assertEquals("block 1000 must read back as written", 'H'.code.toByte(), tail[0])
            val mid = ByteArray(blk)
            target.read(1399L * blk, ByteBuffer.wrap(mid))
            assertEquals("block 1399 must read back as written", 'H'.code.toByte(), mid[0])
        }
    }

    /** ext4 block size for the images these tests build. */
    private val fs_blockSizeGuess = 4096

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

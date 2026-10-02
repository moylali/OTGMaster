package app.fayaz.otgmaster.ntfs

import app.fayaz.otgmaster.block.CachedBlockDevice
import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.fs.UsbFile
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Drives the NTFS driver — NtfsFileSystem/NtfsFile over the same libntfs-3g and
 * JNI bridge the app ships, built for the host by scripts/build_host_native.sh —
 * against `mkntfs` images, and then checks every image with tools that do not go
 * through that bridge:
 *
 *  - scripts/ntfs_check.py, which parses the volume itself and cross-checks the
 *    cluster bitmap, MFT bitmap, indexes and names (see its header for why the
 *    stock tools are not enough on their own);
 *  - `ntfsfix -n`;
 *  - `ntfscat`, reading each file back through ntfsprogs rather than this driver.
 *
 * A hash of what was just written cannot see a broken bitmap or a missing index
 * entry: the ext4 driver passed every content hash while it destroyed a drive.
 * So each write case asserts both content and structure.
 */
class NtfsWriteCheckTest {

    /** Image file presented as 512-byte sectors, like a USB drive. */
    private class FileBlockDevice(file: File) : RawBlockDevice {
        private val raf = RandomAccessFile(file, "rw")
        override val blockSize = 512
        override val blockCount = file.length() / 512

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
            if (rc != 0) throw AssertionError("host build of libntfs-3g failed:\n$out")
            System.setProperty("otg.native.hostlib", lib.absolutePath)
            hostLib = lib
        }
    }

    private lateinit var img: File

    @Before
    fun setUp() {
        assumeTrue("no host C toolchain", hostLib != null)
        for (tool in listOf("mkntfs", "ntfsfix", "ntfscat", "python3")) {
            assumeTrue("$tool not available", !toolMissing(tool))
        }
        img = File.createTempFile("ntfswrite", ".img")
        // 128 MiB at 4 KiB clusters: room for an 8 MiB file to fragment around
        // a few hundred small ones.
        RandomAccessFile(img, "rw").use { it.setLength(128L shl 20) }
        val (rc, out) = run("mkntfs", "-F", "-q", "-f", "-L", "OTGTEST", img.path)
        assertEquals("mkntfs failed: $out", 0, rc)
    }

    @After
    fun tearDown() {
        if (::img.isInitialized) img.delete()
    }

    // ------------------------------------------------------------------ helpers

    private fun <R> withFs(readOnly: Boolean = false, cached: Boolean = true,
                           block: (NtfsFileSystem) -> R): R {
        val raw = FileBlockDevice(img)
        // On a device every access goes through CachedBlockDevice; exercise it.
        val dev: RawBlockDevice = if (cached) CachedBlockDevice(raw) else raw
        val fs = NtfsFileSystem.mount(dev, readOnly)
        try {
            return block(fs)
        } finally {
            fs.unmount()
            dev.close()
        }
    }

    /** Fails with the checkers' own output unless the volume is structurally clean. */
    private fun assertVolumeClean(stage: String) {
        val (rc, out) = run("python3", File(repo, "scripts/ntfs_check.py").path, img.path)
        assertTrue("ntfs_check.py after $stage (rc=$rc):\n$out", rc == 0)
        val (frc, fout) = run("ntfsfix", "-n", img.path)
        assertTrue("ntfsfix -n after $stage (rc=$frc):\n$fout", frc == 0)
    }

    /** Reads [path] through ntfsprogs, independently of this driver. */
    private fun ntfscat(path: String): ByteArray {
        val p = ProcessBuilder("ntfscat", img.path, path).start()
        val bytes = p.inputStream.readBytes()
        val err = p.errorStream.bufferedReader().readText()
        assertEquals("ntfscat $path failed: $err", 0, p.waitFor())
        return bytes
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
        .joinToString("") { "%02x".format(it) }

    private fun imageSha(): String {
        val md = MessageDigest.getInstance("SHA-256")
        img.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun UsbFile.readAll(): ByteArray {
        val out = ByteArray(length.toInt())
        var off = 0
        while (off < out.size) {
            val n = minOf(65536, out.size - off)
            read(off.toLong(), ByteBuffer.wrap(out, off, n))
            off += n
        }
        return out
    }

    /** Writes [data] in chunks of uneven size, as a SAF client copying a file does. */
    private fun UsbFile.writeChunked(data: ByteArray, rnd: Random) {
        var off = 0
        while (off < data.size) {
            val n = minOf(1 + rnd.nextInt(200_000), data.size - off)
            write(off.toLong(), ByteBuffer.wrap(data, off, n))
            off += n
        }
        flush()
    }

    private fun UsbFile.child(name: String): UsbFile =
        search(name) ?: throw AssertionError("$name not found under $absolutePath")

    // -------------------------------------------------------------------- tests

    @Test
    fun freshVolumeMountsAndIsEmpty() {
        withFs { fs ->
            assertEquals("OTGTEST", fs.volumeLabel)
            assertNull(fs.readOnlyReason)
            assertEquals(0, fs.rootDirectory.listFiles().size)
            assertTrue(fs.freeSpace in 1 until fs.capacity)
        }
        assertVolumeClean("mount and unmount only")
    }

    @Test
    fun filesWrittenHereReadBackEverywhere() {
        val rnd = Random(47)
        val tiny = "hello ntfs\n".toByteArray()            // stays resident in the MFT record
        val medium = rnd.nextBytes(300_000)                // goes non-resident
        val large = rnd.nextBytes(8 shl 20)                // many clusters, written unaligned
        withFs { fs ->
            val root = fs.rootDirectory
            root.createFile("tiny.txt").apply { write(0, ByteBuffer.wrap(tiny)); flush() }
            val docs = root.createDirectory("Documents")
            docs.createFile("medium.bin").writeChunked(medium, rnd)
            docs.createDirectory("Nested").createFile("large.bin").writeChunked(large, rnd)
            root.createFile("naïve — файл ☃.txt").apply { write(0, ByteBuffer.wrap(tiny)) }
        }
        assertVolumeClean("creating files")

        assertArrayEquals(tiny, ntfscat("/tiny.txt"))
        assertEquals(sha(medium), sha(ntfscat("/Documents/medium.bin")))
        assertEquals(sha(large), sha(ntfscat("/Documents/Nested/large.bin")))
        assertArrayEquals(tiny, ntfscat("/naïve — файл ☃.txt"))

        // Read back through this driver after a remount, cached and uncached.
        for (cached in listOf(true, false)) withFs(cached = cached) { fs ->
            val root = fs.rootDirectory
            assertEquals(setOf("tiny.txt", "Documents", "naïve — файл ☃.txt"), root.list().toSet())
            assertArrayEquals(tiny, root.child("tiny.txt").readAll())
            assertEquals(sha(medium), sha(root.child("Documents/medium.bin").readAll()))
            val big = root.child("Documents/Nested/large.bin")
            assertEquals(large.size.toLong(), big.length)
            assertEquals(sha(large), sha(big.readAll()))
            // A read straddling cluster boundaries at an odd offset.
            val part = ByteArray(10_007)
            big.read(4093, ByteBuffer.wrap(part))
            assertArrayEquals(large.copyOfRange(4093, 4093 + 10_007), part)
        }
    }

    @Test
    fun overwriteAppendAndTruncate() {
        val rnd = Random(3)
        val base = rnd.nextBytes(500_000)
        withFs { fs -> fs.rootDirectory.createFile("f.bin").writeChunked(base, rnd) }

        val expected = base.copyOf(700_000)
        withFs { fs ->
            val f = fs.rootDirectory.child("f.bin")
            val patch = rnd.nextBytes(12_345)
            f.write(123_457, ByteBuffer.wrap(patch))                 // overwrite in place
            patch.copyInto(expected, 123_457)
            val tail = rnd.nextBytes(200_000)
            f.write(500_000, ByteBuffer.wrap(tail))                  // append
            tail.copyInto(expected, 500_000)
            assertEquals(700_000L, f.length)
        }
        assertVolumeClean("overwrite and append")
        assertEquals(sha(expected), sha(ntfscat("/f.bin")))

        withFs { fs -> fs.rootDirectory.child("f.bin").length = 250_000 }   // shrink
        assertVolumeClean("truncate down")
        assertEquals(sha(expected.copyOf(250_000)), sha(ntfscat("/f.bin")))

        withFs { fs -> fs.rootDirectory.child("f.bin").length = 1_000_000 }  // grow: zero-filled
        assertVolumeClean("truncate up")
        assertEquals(sha(expected.copyOf(250_000).copyOf(1_000_000)), sha(ntfscat("/f.bin")))

        withFs { fs -> fs.rootDirectory.child("f.bin").length = 0 }
        assertVolumeClean("truncate to zero")
        assertEquals(0, ntfscat("/f.bin").size)
    }

    /** Enough entries to push the directory index out of its root into a B-tree. */
    @Test
    fun largeDirectoryWithDeletes() {
        withFs { fs ->
            val dir = fs.rootDirectory.createDirectory("many")
            for (i in 0 until 600) {
                dir.createFile("entry_%04d.txt".format(i)).write(0, ByteBuffer.wrap("$i".toByteArray()))
            }
        }
        assertVolumeClean("creating 600 files")
        withFs { fs ->
            val dir = fs.rootDirectory.child("many")
            assertEquals(600, dir.listFiles().size)
            for (i in 0 until 600 step 3) dir.child("entry_%04d.txt".format(i)).delete()
        }
        assertVolumeClean("deleting a third of them")
        withFs { fs ->
            val names = fs.rootDirectory.child("many").list().toSet()
            assertEquals(400, names.size)
            assertTrue("entry_0001.txt" in names && "entry_0003.txt" !in names)
        }
        assertEquals("599", String(ntfscat("/many/entry_0599.txt")))
    }

    @Test
    fun renameMoveAndRecursiveDelete() {
        val data = Random(9).nextBytes(100_000)
        withFs { fs ->
            val root = fs.rootDirectory
            val a = root.createDirectory("a")
            val b = root.createDirectory("b")
            a.createDirectory("inner").createFile("deep.bin").write(0, ByteBuffer.wrap(data))
            a.createFile("move-me.txt").write(0, ByteBuffer.wrap("x".toByteArray()))
            root.createFile("old-name.txt").write(0, ByteBuffer.wrap("y".toByteArray()))
            root.createFile("case.txt").write(0, ByteBuffer.wrap("z".toByteArray()))

            root.child("old-name.txt").name = "new-name.txt"     // same directory
            root.child("case.txt").name = "CASE.txt"             // case only
            a.child("move-me.txt").moveTo(b)                     // file across directories
            a.child("inner").moveTo(b)                           // directory across directories
        }
        assertVolumeClean("renames and moves")
        withFs { fs ->
            val root = fs.rootDirectory
            assertEquals(setOf("a", "b", "new-name.txt", "CASE.txt"), root.list().toSet())
            assertEquals(setOf("move-me.txt", "inner"), root.child("b").list().toSet())
            assertEquals(0, root.child("a").listFiles().size)
            assertEquals(sha(data), sha(root.child("b/inner/deep.bin").readAll()))
        }
        assertEquals(sha(data), sha(ntfscat("/b/inner/deep.bin")))

        withFs { fs -> fs.rootDirectory.child("b").delete() }   // non-empty, recursive
        assertVolumeClean("recursive delete")
        withFs { fs -> assertEquals(setOf("a", "new-name.txt", "CASE.txt"), fs.rootDirectory.list().toSet()) }
    }

    @Test
    fun refusesWhatWouldBreakTheVolumeOrWindows() {
        withFs { fs ->
            val root = fs.rootDirectory
            val dir = root.createDirectory("d")
            dir.createDirectory("sub")
            root.createFile("taken.txt")

            for (bad in listOf("a:b", "q?.txt", "CON", "trailing.", "x|y")) {
                try { root.createFile(bad); fail("created forbidden name $bad") } catch (_: IOException) {}
            }
            try { root.createFile("TAKEN.TXT"); fail("created a case-insensitive duplicate") } catch (_: IOException) {}
            try { dir.moveTo(dir.child("sub")); fail("moved a directory into its own child") } catch (_: IOException) {}
            try { root.child("d").name = "taken.txt"; fail("renamed over an existing file") } catch (_: IOException) {}
        }
        assertVolumeClean("refused operations")
        withFs { fs -> assertEquals(setOf("d", "taken.txt"), fs.rootDirectory.list().toSet()) }
    }

    @Test
    fun fillingTheVolumeFailsCleanly() {
        val rnd = Random(5)
        withFs { fs ->
            val f = fs.rootDirectory.createFile("fill.bin")
            val chunk = rnd.nextBytes(1 shl 20)
            var off = 0L
            try {
                while (true) {
                    f.write(off, ByteBuffer.wrap(chunk))
                    off += chunk.size
                    if (off > fs.capacity) fail("wrote past the volume's capacity")
                }
            } catch (_: IOException) {
                // ENOSPC is the expected way out.
            }
            assertTrue("volume filled at only $off bytes", off > 64L shl 20)
            f.delete()
        }
        assertVolumeClean("filling the volume and deleting the fill")
        withFs { fs -> assertTrue(fs.freeSpace > 100L shl 20) }
    }

    @Test
    fun readOnlyMountChangesNothing() {
        withFs { fs -> fs.rootDirectory.createFile("keep.txt").write(0, ByteBuffer.wrap("k".toByteArray())) }
        val before = imageSha()
        withFs(readOnly = true) { fs ->
            assertEquals(NtfsFileSystem.MOUNTED_READ_ONLY, fs.readOnlyReason)
            val root = fs.rootDirectory
            assertArrayEquals("k".toByteArray(), root.child("keep.txt").readAll())
            for (attempt in listOf<() -> Unit>(
                { root.createFile("new.txt") },
                { root.child("keep.txt").write(0, ByteBuffer.wrap("x".toByteArray())) },
                { root.child("keep.txt").length = 0 },
                { root.child("keep.txt").delete() },
                { root.child("keep.txt").name = "renamed.txt" },
            )) {
                try { attempt(); fail("a write succeeded on a read-only mount") } catch (_: IOException) {}
            }
        }
        assertEquals("read-only mount modified the image", before, imageSha())
    }

    /**
     * Browsing a volume mounted read-write must not write to it: no atime
     * updates, no flag changes at mount or unmount. A user who only looks at a
     * drive should be able to hand it back to Windows untouched.
     */
    @Test
    fun browsingWritesNothing() {
        withFs { fs ->
            fs.rootDirectory.createDirectory("d").createFile("f.txt")
                .write(0, ByteBuffer.wrap("content".toByteArray()))
        }
        val before = imageSha()
        withFs { fs ->
            val f = fs.rootDirectory.child("d/f.txt")
            assertArrayEquals("content".toByteArray(), f.readAll())
            fs.rootDirectory.listFiles(); fs.freeSpace; fs.volumeLabel
            f.close()
        }
        assertEquals("a read-write mount used only for reading modified the image", before, imageSha())
    }

    /**
     * A volume Windows left hibernated (or with Fast Startup metadata cached)
     * must come up read-only, untouched. Writing it would be overwritten when
     * Windows resumes, along with anything it had cached.
     */
    @Test
    fun hibernatedVolumeMountsReadOnly() {
        assumeTrue("ntfscp not available", !toolMissing("ntfscp"))
        val hiber = File.createTempFile("hiberfil", ".sys")
        try {
            hiber.writeBytes("hibr".toByteArray() + ByteArray(8188))
            val (rc, out) = run("ntfscp", img.path, hiber.path, "/hiberfil.sys")
            assertEquals("ntfscp failed: $out", 0, rc)
        } finally {
            hiber.delete()
        }
        val before = imageSha()
        withFs { fs ->
            val reason = fs.readOnlyReason
            assertNotNull("a hibernated volume mounted read-write", reason)
            assertTrue(reason!!, reason.contains("hibernated"))
            assertTrue("hiberfil.sys" in fs.rootDirectory.list())
            try { fs.rootDirectory.createFile("x"); fail("wrote to a hibernated volume") } catch (_: IOException) {}
        }
        assertEquals("mounting a hibernated volume modified it", before, imageSha())
    }

    /** Files written by ntfs-3g's own tool read back identically through this driver. */
    @Test
    fun readsWhatTheReferenceDriverWrote() {
        assumeTrue("ntfscp not available", !toolMissing("ntfscp"))
        val rnd = Random(11)
        val files = mapOf("/ref-small.txt" to "small".toByteArray(), "/ref-big.bin" to rnd.nextBytes(3_000_000))
        for ((path, bytes) in files) {
            val src = File.createTempFile("ref", ".bin")
            try {
                src.writeBytes(bytes)
                val (rc, out) = run("ntfscp", img.path, src.path, path)
                assertEquals("ntfscp failed: $out", 0, rc)
            } finally {
                src.delete()
            }
        }
        withFs(readOnly = true) { fs ->
            for ((path, bytes) in files) {
                assertEquals(sha(bytes), sha(fs.rootDirectory.child(path.trimStart('/')).readAll()))
            }
        }
    }
}

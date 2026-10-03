package app.fayaz.otgmaster.apfs

import app.fayaz.otgmaster.block.FileBlockDevice
import app.fayaz.otgmaster.block.SlicedBlockDevice
import app.fayaz.otgmaster.partition.GptParser
import me.jahnen.libaums.core.fs.UsbFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * The four APFS images built on macOS (testdata/apfs/, docs/TEST_DATA.md §6b),
 * mounted through the app's own ApfsFileSystem over libfsapfs, the same native code
 * the APK ships.
 *
 * The images were made by Apple's own tools and their contents checked on the Mac
 * and again on Linux through apfs-fuse, so a match here is a match against two
 * implementations that share no code with this one.
 */
class ApfsImageTest {

    companion object {
        private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "scripts/build_host_native.sh").exists() }
        private val apfsDir = File(repo, "testdata/apfs")
        private var ready = false

        private fun run(vararg cmd: String): Pair<Int, String> {
            val p = ProcessBuilder(*cmd).directory(repo).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            return p.waitFor() to out
        }

        @BeforeClass @JvmStatic
        fun setUp() {
            if (run("sh", "-c", "command -v gcc && command -v g++ && command -v xz").first != 0) return
            if (!File(apfsDir, "apfs_ci/test.img").exists()) {
                val (rc, out) = run("tar", "--warning=no-unknown-keyword", "-xJf",
                    File(apfsDir, "apfs-images.tar.xz").path, "-C", apfsDir.path)
                if (rc != 0) throw AssertionError("unpacking the APFS images failed:\n$out")
            }
            val lib = File(repo, "app/build/host-native/libotg-host.so")
            val (rc, out) = run(File(repo, "scripts/build_host_native.sh").path, lib.path)
            if (rc != 0) throw AssertionError("host native build failed:\n$out")
            System.setProperty("otg.native.hostlib", lib.absolutePath)
            ready = true
        }

        private const val PASSWORD = "password123"
    }

    /** The APFS partition of [case]'s GPT disk, as the app slices it. */
    private fun partition(case: String): SlicedBlockDevice {
        assumeTrue("host toolchain unavailable", ready)
        val disk = FileBlockDevice(File(apfsDir, "$case/test.img"))
        val parts = GptParser.parse(disk)
        assertEquals("$case: one GPT partition, as Disk Utility formats a USB stick", 1, parts.size)
        return SlicedBlockDevice(disk, parts[0].firstLba, parts[0].sectorCount)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** Reads [f] in deliberately odd-sized chunks, so reads straddle APFS blocks and extents. */
    private fun readAll(f: UsbFile): ByteArray {
        val out = ByteArray(f.length.toInt())
        var pos = 0
        var chunk = 777
        while (pos < out.size) {
            val n = minOf(chunk, out.size - pos)
            val buf = ByteBuffer.wrap(out, pos, n)
            f.read(pos.toLong(), buf)
            assertEquals("short read at $pos", pos + n, buf.position())
            pos += n
            chunk = chunk * 3 % 9001 + 1
        }
        return out
    }

    private fun check(case: String, encrypted: Boolean, caseSensitive: Boolean) {
        val dev = partition(case)
        assertEquals("$case: probe", encrypted, ApfsFileSystem.isEncrypted(dev))
        val fs = ApfsFileSystem.mount(dev, if (encrypted) PASSWORD else null)
        try {
            assertEquals("OTGAPFS", fs.volumeLabel)
            assertEquals(caseSensitive, fs.isCaseSensitive)
            assertTrue(fs.capacity in (60L shl 20)..(64L shl 20))
            assertTrue("used ${fs.occupiedSpace}", fs.occupiedSpace in 1L..fs.capacity)
            val root = fs.rootDirectory
            val names = root.list().toSet()
            for (n in listOf("flower.jpg", "nested", "file with spaces.txt", "unicöde_fîle.txt", "large_file.bin", "case.txt"))
                assertTrue("$case: $n missing from ${names.sorted()}", n in names)

            val flower = root.search("flower.jpg")!!
            assertArrayEquals("$case: flower.jpg byte for byte",
                sha256(File(repo, "testdata/flower.jpg").readBytes()), sha256(readAll(flower)))
            assertEquals("Hello World\n", String(readAll(root.search("file with spaces.txt")!!)))
            assertEquals("Unicode\n", String(readAll(root.search("unicöde_fîle.txt")!!)))
            assertEquals(10240L, root.search("large_file.bin")!!.length)
            val empty = root.search("nested/very/deep/folder/empty_file.txt")
            assertNotNull("$case: nested path", empty)
            assertEquals(0L, empty!!.length)
            assertTrue(root.search("nested/very")!!.isDirectory)

            if (caseSensitive) {
                assertTrue("CASE.txt" in names)
                assertEquals("lower\n", String(readAll(root.search("case.txt")!!)))
                assertEquals("UPPER\n", String(readAll(root.search("CASE.txt")!!)))
                assertNull("case-sensitive lookup must not fold case", root.search("Case.TXT"))
            } else {
                assertFalse("CASE.txt" in names)
                assertEquals("UPPER\n", String(readAll(root.search("CASE.TXT")!!)))
            }
            // Normalization-insensitive: the decomposed spelling finds the same file.
            val nfd = java.text.Normalizer.normalize("unicöde_fîle.txt", java.text.Normalizer.Form.NFD)
            assertNotNull(root.search(nfd))

            try { root.createFile("x.txt"); fail("APFS must refuse writes") } catch (_: IOException) {}
        } finally {
            fs.unmount()
        }
    }

    @Test fun plainCaseInsensitive() = check("apfs_ci", encrypted = false, caseSensitive = false)
    @Test fun plainCaseSensitive() = check("apfs_cs", encrypted = false, caseSensitive = true)
    @Test fun encryptedCaseInsensitive() = check("apfs_enc_ci", encrypted = true, caseSensitive = false)
    @Test fun encryptedCaseSensitive() = check("apfs_enc_cs", encrypted = true, caseSensitive = true)

    @Test fun wrongPasswordIsRefused() {
        for (case in listOf("apfs_enc_ci", "apfs_enc_cs")) {
            for (pw in listOf("password124", null)) {
                try {
                    ApfsFileSystem.mount(partition(case), pw).unmount()
                    fail("$case mounted with password $pw")
                } catch (_: ApfsFileSystem.WrongPasswordException) {}
            }
        }
    }

    @Test fun readAfterUnmountFails() {
        val fs = ApfsFileSystem.mount(partition("apfs_ci"), null)
        val flower = fs.rootDirectory.search("flower.jpg")!!
        fs.unmount()
        try { flower.read(0, ByteBuffer.allocate(16)); fail("read after unmount") } catch (_: IOException) {}
    }
}

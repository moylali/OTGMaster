package app.fayaz.otgmaster.apfs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Lzfse against streams Apple's reference encoder writes (pyliblzfse wraps lzfse 1.0's C code):
 * the decoder Container Master hands its DMG reader for ULFO images.
 */
class LzfseTest {

    companion object {
        private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "scripts/build_host_native.sh").exists() }

        private fun run(vararg cmd: String): Pair<Int, String> {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            return p.waitFor() to out
        }

        private fun toolMissing(tool: String) = run("sh", "-c", "command -v $tool").first != 0

        private var ready = false

        @BeforeClass
        @JvmStatic
        fun buildHostLibrary() {
            if (toolMissing("gcc") || toolMissing("g++") || toolMissing("uv")) return
            val lib = File(repo, "disk-engine/build/host-native/libotg-host.so")
            val (rc, out) = run(File(repo, "scripts/build_host_native.sh").path, lib.path)
            if (rc != 0) throw AssertionError("host build of the native code failed:\n$out")
            System.setProperty("otg.native.hostlib", lib.absolutePath)
            ready = true
        }
    }

    /** [data] compressed by the reference encoder, through uv and pyliblzfse. */
    private fun lzfse(data: ByteArray): ByteArray {
        val dir = Files.createTempDirectory("lzfse").toFile()
        val src = File(dir, "in").apply { writeBytes(data) }
        val dst = File(dir, "out")
        val (rc, out) = run("uv", "run", "--quiet", "--with", "pyliblzfse", "python", "-c",
            "import liblzfse,sys; open(sys.argv[2],'wb').write(liblzfse.compress(open(sys.argv[1],'rb').read()))", src.path, dst.path)
        assumeTrue("pyliblzfse could not run: $out", rc == 0)
        return dst.readBytes()
    }

    @Test
    fun `streams from the reference encoder decode exactly`() {
        assumeTrue("no host toolchain or uv", ready)
        val r = java.util.Random(9)
        val random = ByteArray(1 shl 20).also { r.nextBytes(it) }                    // stored, little to gain
        val text = ByteArray(1 shl 20) { "lzfse in a disk image "[it % 22].code.toByte() }   // the LZFSE and LZVN paths
        val zeros = ByteArray(1 shl 20)
        for (data in listOf(random, text, zeros, ByteArray(512) { it.toByte() })) {
            assertArrayEquals(data, Lzfse.decompress(lzfse(data), data.size))
        }
    }

    @Test
    fun `a wrong size or damaged data is an error, not wrong bytes`() {
        assumeTrue("no host toolchain or uv", ready)
        val text = ByteArray(100_000) { "abcdefgh"[it % 8].code.toByte() }
        val c = lzfse(text)
        assertThrows(IOException::class.java) { Lzfse.decompress(c, text.size - 1) }
        assertThrows(IOException::class.java) { Lzfse.decompress(c, text.size + 1) }
        val bad = c.copyOf().also { for (i in 12 until minOf(it.size, 40)) it[i] = 0x7F }
        assertThrows(IOException::class.java) { Lzfse.decompress(bad, text.size) }
    }
}

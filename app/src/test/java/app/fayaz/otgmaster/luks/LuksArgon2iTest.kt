package app.fayaz.otgmaster.luks

import app.fayaz.otgmaster.block.RawBlockDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * LUKS2 keyslots made with Argon2i must unlock. Review, 2026-11-09: LuksHeader read
 * "argon2i" and "argon2id" into the same keyslot type and LuksUnlocker always derived
 * the key with Argon2id, so a valid Argon2i volume (cryptsetup --pbkdf argon2i) never
 * opened and the app said the password was wrong.
 */
class LuksArgon2iTest {

    private class Dev(file: File) : RawBlockDevice {
        private val raf = RandomAccessFile(file, "r")
        override val blockSize = 512
        override val blockCount = file.length() / 512
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            val out = ByteArray(blockCount * 512); raf.seek(startBlock * 512); raf.readFully(out); return out
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) = throw UnsupportedOperationException()
        override fun close() = raf.close()
    }

    companion object {
        private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "scripts/build_host_native.sh").exists() }
        private var ready = false
        private const val PASSWORD = "password123"

        private fun run(vararg cmd: String, input: String? = null): Pair<Int, String> {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            input?.let { p.outputStream.use { o -> o.write(it.toByteArray()) } }
            val out = p.inputStream.bufferedReader().readText()
            return p.waitFor() to out
        }

        @BeforeClass @JvmStatic
        fun setUp() {
            if (run("sh", "-c", "command -v gcc && command -v cryptsetup").first != 0) return
            val lib = File(repo, "app/build/host-native/libotg-host.so")
            val (rc, out) = run(File(repo, "scripts/build_host_native.sh").path, lib.path)
            if (rc != 0) throw AssertionError("host native build failed:\n$out")
            System.setProperty("otg.native.hostlib", lib.absolutePath)
            ready = true
        }
    }

    private val tmp = mutableListOf<File>()
    @After fun cleanUp() { tmp.forEach { it.delete() } }

    private fun unlocks(pbkdf: String) {
        assumeTrue("host toolchain or cryptsetup unavailable", ready)
        val img = File.createTempFile(pbkdf, ".img").also { tmp += it }
        RandomAccessFile(img, "rw").use { it.setLength(20L shl 20) }
        val (rc, out) = run("cryptsetup", "luksFormat", "--type", "luks2", "--batch-mode", "--disable-locks",
            "--pbkdf", pbkdf, "--pbkdf-memory", "32768", "--pbkdf-force-iterations", "4", "--pbkdf-parallel", "1",
            "--luks2-metadata-size", "16k", "--luks2-keyslots-size", "1m", img.path, "-", input = PASSWORD)
        assertEquals("luksFormat: $out", 0, rc)
        val (_, dump) = run("cryptsetup", "luksDump", "--disable-locks", img.path)
        assumeTrue("cryptsetup did not make a $pbkdf keyslot:\n$dump", dump.contains("PBKDF:      $pbkdf") || dump.contains(pbkdf))
        val raw = Dev(img)
        try {
            val dev = LuksUnlocker().unlock(raw, 0, raw.blockCount, PASSWORD.toByteArray())
            dev.close()
        } finally { raw.close() }
    }

    @Test fun `an Argon2i keyslot unlocks`() = unlocks("argon2i")

    @Test fun `an Argon2id keyslot still unlocks`() = unlocks("argon2id")
}

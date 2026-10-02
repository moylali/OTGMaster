package app.fayaz.otgmaster.luks

import app.fayaz.otgmaster.block.RawBlockDevice
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

/**
 * LUKS volumes made by cryptsetup, opened by the app's LuksUnlocker, at every
 * data-segment sector size: 512 (LUKS1 and LUKS2) and the larger ones cryptsetup
 * has chosen by default since 2.4 — which the app used to refuse outright.
 *
 * The payload is written and checked by scripts/luks_payload.py — cryptsetup's
 * key and layout with Python's AES — so the app's reads are checked against an
 * independent encryption, and its writes against an independent decryption.
 * PBKDF2 keyslots: Argon2id needs the Android argon2kt library, which the host
 * JVM does not have; the slot type does not affect the data path under test.
 */
class LuksSectorSizeTest {

    private class RwDevice(file: File) : RawBlockDevice {
        private val raf = RandomAccessFile(file, "rw")
        override val blockSize = 512
        override val blockCount = file.length() / 512
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            val out = ByteArray(blockCount * 512)
            raf.seek(startBlock * 512); raf.readFully(out)
            return out
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) { raf.seek(startBlock * 512); raf.write(data) }
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
            if (run("sh", "-c", "command -v gcc && command -v cryptsetup && python3 -c 'import cryptography'").first != 0) return
            val lib = File(repo, "app/build/host-native/libotg-host.so")
            val (rc, out) = run(File(repo, "scripts/build_host_native.sh").path, lib.path)
            if (rc != 0) throw AssertionError("host native build failed:\n$out")
            System.setProperty("otg.native.hostlib", lib.absolutePath)
            ready = true
        }
    }

    private val tmp = mutableListOf<File>()
    @After fun cleanUp() { tmp.forEach { it.delete() } }

    private fun temp(name: String) = File.createTempFile(name, ".img").also { tmp += it }

    private fun luks(type: String, sectorSize: Int): File {
        val img = temp("$type-$sectorSize")
        RandomAccessFile(img, "rw").use { it.setLength(24L shl 20) }
        val args = mutableListOf("cryptsetup", "luksFormat", "--type", type, "--batch-mode", "--disable-locks",
            "--cipher", "aes-xts-plain64", "--key-size", "512", "--pbkdf", "pbkdf2", "--iter-time", "100")
        if (type == "luks2") args += listOf("--sector-size", "$sectorSize", "--luks2-metadata-size", "16k",
            "--luks2-keyslots-size", "1m")
        val (rc, out) = run(*(args + listOf(img.path, "-")).toTypedArray(), input = PASSWORD)
        assertEquals("luksFormat: $out", 0, rc)
        return img
    }

    private fun payloadSize(img: File): Long {
        val (_, out) = run("cryptsetup", "luksDump", "--disable-locks", img.path)
        val off = Regex("offset:\\s+(\\d+) \\[bytes\\]").find(out)?.groupValues?.get(1)?.toLong()
            ?: Regex("Payload offset:\\s+(\\d+)").find(out)!!.groupValues[1].toLong() * 512
        return img.length() - off
    }

    private fun check(type: String, sectorSize: Int) {
        assumeTrue("host toolchain or cryptsetup unavailable", ready)
        val img = luks(type, sectorSize)
        val plainBytes = Random(sectorSize + type.length).nextBytes(payloadSize(img).toInt())
        val plain = temp("plain").also { it.writeBytes(plainBytes) }
        val script = File(repo, "scripts/luks_payload.py").path
        val (erc, eout) = run("python3", script, "encrypt", img.path, PASSWORD, plain.path)
        assertEquals("independent encrypt: $eout", 0, erc)

        // Read: the app's decryption equals the independent encryption's input.
        val raw = RwDevice(img)
        val dev = LuksUnlocker().unlock(raw, 0, raw.blockCount, PASSWORD.toByteArray())
        assertEquals(plainBytes.size.toLong(), dev.blockCount * 512)
        var b = 0L
        while (b < dev.blockCount) {
            val n = minOf(1000L, dev.blockCount - b).toInt()   // deliberately not sector-aligned
            assertArrayEquals("read at block $b", plainBytes.copyOfRange((b * 512).toInt(), ((b + n) * 512).toInt()),
                dev.readBlocks(b, n))
            b += n
        }

        // Write: unaligned runs through the app, checked by an independent decryption.
        val rnd = Random(7)
        val expected = plainBytes.copyOf()
        repeat(30) {
            val n = 1 + rnd.nextInt(19)
            val s = rnd.nextLong(dev.blockCount - n)
            val data = rnd.nextBytes(n * 512)
            dev.writeBlocks(s, data)
            data.copyInto(expected, (s * 512).toInt())
        }
        dev.close(); raw.close()
        val out = temp("out")
        val (drc, dout) = run("python3", script, "decrypt", img.path, PASSWORD, out.path)
        assertEquals("independent decrypt: $dout", 0, drc)
        assertArrayEquals("$type/$sectorSize: written data through an independent decryption",
            expected, out.readBytes().copyOf(expected.size))
    }

    @Test fun luks1() = check("luks1", 512)
    @Test fun luks2Sector512() = check("luks2", 512)
    @Test fun luks2Sector2048() = check("luks2", 2048)
    @Test fun luks2Sector4096() = check("luks2", 4096)
}

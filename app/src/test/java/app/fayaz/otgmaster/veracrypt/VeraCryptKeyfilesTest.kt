package app.fayaz.otgmaster.veracrypt

import app.fayaz.otgmaster.block.RawBlockDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Keyfiles, both ways, against VeraCrypt itself.
 *
 * Opening: volumes VeraCrypt 1.26 made with keyfiles (scripts/make_veracrypt_keyfile_fixtures.sh)
 * must open here — no password and two keyfiles, one past the 1 MiB that counts; a 40-character
 * password (the 64-byte pool); a 70-character one (the 128-byte pool, which the unlocker's old
 * copy of the mixing did not have, and so could not open).
 *
 * Creating: a volume this app creates with keyfiles must open in cryptsetup's TCRYPT code with
 * the same keyfiles, which shares nothing with ours, and must not open without them.
 *
 * The unlocker's URI half (ContentResolver) needs a device; the E2E keyfile cases cover it.
 */
class VeraCryptKeyfilesTest {

    private class ImageDevice(file: File) : RawBlockDevice {
        private val raf = RandomAccessFile(file, "rw")
        override val blockSize = 512
        override val blockCount = file.length() / 512
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            val out = ByteArray(blockCount * 512)
            raf.seek(startBlock * 512); raf.readFully(out); return out
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) {
            raf.seek(startBlock * 512); raf.write(data)
        }
        override fun close() = raf.close()
    }

    companion object {
        private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "scripts/build_host_native.sh").exists() }
        private val fixtures = File(repo, "app/src/test/resources/veracrypt/keyfiles")
        private var ready = false

        /** The same bytes scripts/make_veracrypt_keyfile_fixtures.sh made the fixtures with. */
        fun keyfile(seed: Int, size: Int) = ByteArray(size) { i -> ((i * 31 + seed * 7 + (i shr 9)) % 251).toByte() }
        val A = keyfile(1, 300)
        val B = keyfile(2, 1_100_000)

        @BeforeClass @JvmStatic
        fun setUp() {
            val gcc = ProcessBuilder("sh", "-c", "command -v gcc").start().waitFor() == 0
            if (!gcc) return
            val lib = File(repo, "app/build/host-native/libotg-host.so")
            val p = ProcessBuilder(File(repo, "scripts/build_host_native.sh").path, lib.path)
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0) throw AssertionError("host native build failed:\n$out")
            System.setProperty("otg.native.hostlib", lib.absolutePath)
            ready = true
        }
    }

    private val temps = mutableListOf<File>()
    @After fun tearDown() { temps.forEach { it.delete() } }
    private fun temp(suffix: String) = File.createTempFile("vckf", suffix).also { temps += it }

    private fun streams(vararg files: ByteArray): List<() -> java.io.InputStream?> =
        files.map { bytes -> { ByteArrayInputStream(bytes) } }

    /** Our mixing, then the header opened with it, as the unlocker does after mixing. */
    private fun opens(fixture: String, password: String, vararg keyfiles: ByteArray): Boolean {
        val mixed = VeraCryptKeyfiles.apply(password.toByteArray(), streams(*keyfiles))
        val header = File(fixtures, "$fixture.bin").readBytes()
        val kdf = KdfParams.forVolume(VeraCryptHash.SHA512, 1)
        val plain = VeraCryptNative.decryptHeader(VeraCryptCipher.AES.nativeIds, VeraCryptHash.SHA512.kdfNativeId!!,
            mixed, header.copyOfRange(0, 64), kdf.iterations, kdf.memoryKiB, header.copyOfRange(64, 512))
        return plain != null && String(plain, 0, 4, Charsets.US_ASCII) == "VERA"
    }

    @Test fun veraCryptVolumesWithKeyfilesOpen() {
        assumeTrue("gcc not available", ready)
        assertTrue("no password, keyfiles A and B", opens("empty_2kf", "", A, B))
        assertTrue("keyfile order does not matter", opens("empty_2kf", "", B, A))
        assertTrue("40-character password, A and B", opens("pw40_2kf", "k".repeat(40), A, B))
        assertTrue("70-character password, A (128-byte pool)", opens("pw70_1kf", "K".repeat(70), A))
    }

    @Test fun theWrongKeyfilesDoNotOpen() {
        assumeTrue("gcc not available", ready)
        assertTrue("one keyfile of two opened it", !opens("empty_2kf", "", A))
        assertTrue("the password alone opened it", !opens("pw70_1kf", "K".repeat(70)))
        // Only the first MiB of a keyfile counts: changing a byte past it changes nothing.
        val bLate = B.copyOf().also { it[(1 shl 20) + 10] = (it[(1 shl 20) + 10] + 1).toByte() }
        assertTrue("a byte past 1 MiB mattered", opens("empty_2kf", "", A, bLate))
        val bEarly = B.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertTrue("a byte inside the first MiB did not matter", !opens("empty_2kf", "", A, bEarly))
    }

    @Test fun anUnreadableKeyfileStopsACreateBeforeAnythingIsWritten() {
        val img = temp(".img").apply { RandomAccessFile(this, "rw").use { it.setLength(4L shl 20) } }
        val before = img.readBytes()
        try {
            ImageDevice(img).use { dev ->
                VeraCryptVolumeCreator.create(dev, "k".repeat(40).toCharArray(), 1, VeraCryptCipher.AES,
                    VeraCryptHash.SHA512, keyfiles = listOf({ throw IOException("gone") }))
            }
            fail("a create with an unreadable keyfile went ahead")
        } catch (_: IOException) {}
        assertTrue("the refused create wrote to the partition", before.contentEquals(img.readBytes()))
    }

    @Test fun cryptsetupOpensAVolumeCreatedWithKeyfilesOnlyWithThem() {
        assumeTrue("gcc not available", ready)
        val cryptsetup = listOf("/usr/sbin/cryptsetup", "/sbin/cryptsetup").firstOrNull { File(it).exists() }
        assumeTrue("cryptsetup not available", cryptsetup != null)
        val ka = temp(".key").apply { writeBytes(A) }
        val kb = temp(".key").apply { writeBytes(B) }
        for ((password, keys) in listOf("" to listOf(ka, kb), "k".repeat(40) to listOf(ka, kb), "K".repeat(70) to listOf(ka))) {
            val img = temp(".img").apply { RandomAccessFile(this, "rw").use { it.setLength(4L shl 20) } }
            ImageDevice(img).use {
                VeraCryptVolumeCreator.create(it, password.toCharArray(), 1, VeraCryptCipher.AES, VeraCryptHash.SHA512,
                    keyfiles = keys.map { k -> { k.inputStream() } })
            }
            val what = "password of ${password.length}, ${keys.size} keyfile(s)"
            val keyArgs = keys.flatMap { listOf("--key-file", it.absolutePath) }.toTypedArray()
            assertEquals("$what: cryptsetup with the keyfiles", 0,
                dump(cryptsetup!!, password, *keyArgs).first)
            assertTrue("$what: cryptsetup opened it without the keyfiles",
                dump(cryptsetup, password).first != 0)
        }
    }

    private fun dump(cryptsetup: String, password: String, vararg extra: String): Pair<Int, String> {
        // The password goes on stdin; in TCRYPT mode --key-file names VeraCrypt keyfiles.
        val img = temps.last { it.name.endsWith(".img") }
        val p = ProcessBuilder(cryptsetup, "tcryptDump", "--veracrypt", "--veracrypt-pim=1", "--hash", "sha512",
            *extra, img.absolutePath).redirectErrorStream(true).start()
        p.outputStream.use { it.write((password + "\n").toByteArray()) }
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 to "timed out" }
        return p.exitValue() to out
    }

    @Test fun aGeneratedKeyfileIsVeraCryptsSizeAndOpensWhatItCreated() {
        assumeTrue("gcc not available", ready)
        val cryptsetup = listOf("/usr/sbin/cryptsetup", "/sbin/cryptsetup").firstOrNull { File(it).exists() }
        assumeTrue("cryptsetup not available", cryptsetup != null)
        val k = temp(".key")
        k.outputStream().use { VeraCryptKeyfiles.generate(it) }
        assertEquals(VeraCryptKeyfiles.NEW_KEYFILE_BYTES.toLong(), k.length())
        val other = java.io.ByteArrayOutputStream().also { VeraCryptKeyfiles.generate(it) }.toByteArray()
        assertTrue("two generated keyfiles were the same", !other.contentEquals(k.readBytes()))

        val img = temp(".img").apply { RandomAccessFile(this, "rw").use { it.setLength(4L shl 20) } }
        ImageDevice(img).use {
            VeraCryptVolumeCreator.create(it, CharArray(0), 1, VeraCryptCipher.AES, VeraCryptHash.SHA512,
                keyfiles = listOf({ k.inputStream() }))
        }
        assertEquals("cryptsetup with the generated keyfile", 0, dump(cryptsetup!!, "", "--key-file", k.absolutePath).first)
    }

    @Test fun passwordRulesWithKeyfilesAreVeraCrypts() {
        // VeraCrypt 1.26.29: keyfiles alone are a valid credential...
        assertEquals(null, VeraCryptVolumeCreator.passwordProblem(CharArray(0), 1, hasKeyfiles = true))
        assertTrue(VeraCryptVolumeCreator.passwordProblem(CharArray(0), null)!!.contains("keyfile"))
        // ...but a short password with PIM 1 is refused with or without them.
        assertTrue(VeraCryptVolumeCreator.passwordProblem("short".toCharArray(), 1, hasKeyfiles = true)!!.contains("485"))
        assertEquals(null, VeraCryptVolumeCreator.passwordProblem("short".toCharArray(), null, hasKeyfiles = true))
    }
}

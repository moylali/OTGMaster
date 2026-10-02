package app.fayaz.otgmaster.bitlocker

import app.fayaz.otgmaster.block.RawBlockDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Opens BitLocker volumes made by Windows — cryptsetup's test images — with the
 * app's parser, key derivation and sector crypto, and requires the SHA-256 of the
 * whole decrypted volume to equal the one cryptsetup records for it.
 *
 * That hash covers every byte: the relocated boot sectors, the zeroed metadata
 * areas and every encrypted sector, so a wrong IV convention, key split or
 * mapping anywhere fails it. Each image is opened twice, with its password and its
 * recovery password, which exercises both protectors' key derivation.
 */
class BitLockerCompatTest {

    class FileDevice(file: File) : RawBlockDevice {
        private val raf = RandomAccessFile(file, "r")
        override val blockSize = 512
        override val blockCount = file.length() / 512
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            val out = ByteArray(blockCount * 512)
            raf.seek(startBlock * 512); raf.readFully(out)
            return out
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) = throw UnsupportedOperationException()
        override fun close() = raf.close()
    }

    companion object {
        private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "scripts/build_host_native.sh").exists() }
        private lateinit var images: File
        private var ready = false
        private val conf = mutableMapOf<String, MutableMap<String, String>>()

        private fun run(vararg cmd: String): Pair<Int, String> {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            return p.waitFor() to out
        }

        @BeforeClass @JvmStatic
        fun setUp() {
            if (run("sh", "-c", "command -v gcc && command -v g++ && command -v tar").first != 0) return
            val lib = File(repo, "app/build/host-native/libotg-host.so")
            val (rc, out) = run(File(repo, "scripts/build_host_native.sh").path, lib.path)
            if (rc != 0) throw AssertionError("host native build failed:\n$out")
            System.setProperty("otg.native.hostlib", lib.absolutePath)

            val dir = File(repo, "app/build/bitlk-images")
            if (!File(dir, "bitlk-images/images.conf").exists()) {
                dir.mkdirs()
                val tar = File(repo, "app/src/test/resources/bitlk/bitlk-images.tar.xz")
                val (trc, tout) = run("tar", "xJf", tar.path, "-C", dir.path)
                if (trc != 0) throw AssertionError("cannot extract test images: $tout")
            }
            images = File(dir, "bitlk-images")
            var section = ""
            for (line in File(images, "images.conf").readLines()) {
                val t = line.trim()
                if (t.startsWith("[")) { section = t.trim('[', ']'); conf[section] = mutableMapOf() }
                else if ('=' in t) conf[section]!![t.substringBefore('=')] = t.substringAfter('=')
            }
            ready = true
        }
    }

    private fun sha256OfVolume(dev: RawBlockDevice): String {
        val md = MessageDigest.getInstance("SHA-256")
        var b = 0L
        while (b < dev.blockCount) {
            val n = minOf(2048L, dev.blockCount - b).toInt()
            md.update(dev.readBlocks(b, n))
            b += n
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun open(name: String, secret: String): BitLockerBlockDevice {
        val raw = FileDevice(File(images, "$name.img"))
        return BitLockerUnlocker().unlock(raw, 0, raw.blockCount, secret.toCharArray())
    }

    private fun check(name: String, vararg secrets: String) {
        assumeTrue("host toolchain unavailable", ready)
        val want = conf[name]!!["SHA256SUM"]!!
        for (secret in secrets) {
            val dev = open(name, secret)
            try {
                assertEquals("$name opened with '$secret'", want, sha256OfVolume(dev))
            } finally {
                dev.close()
            }
        }
    }

    private fun pwAndRecovery(name: String) = check(name, conf[name]!!["PW"]!!, conf[name]!!["RP"]!!)

    @Test fun aesXts128() = pwAndRecovery("bitlk-aes-xts-128")
    @Test fun aesXts256() = pwAndRecovery("bitlk-aes-xts-256")
    @Test fun aesCbc128() = pwAndRecovery("bitlk-aes-cbc-128")
    @Test fun aesCbc256() = pwAndRecovery("bitlk-aes-cbc-256")
    @Test fun aesCbcElephant128() = pwAndRecovery("bitlk-aes-cbc-elephant-128")
    @Test fun aesCbcElephant256() = pwAndRecovery("bitlk-aes-cbc-elephant-256")
    @Test fun aesXts128With4kSectors() = pwAndRecovery("bitlk-aes-xts-128-4k")
    @Test fun aesCbc128With4kSectors() = pwAndRecovery("bitlk-aes-cbc-128-4k")
    @Test fun toGoXts() = pwAndRecovery("bitlk-togo-aes-xts-128")
    @Test fun toGoCbc() = pwAndRecovery("bitlk-togo-aes-cbc-128")
    @Test fun unicodePassword() = pwAndRecovery("bitlk-aes-xts-128-unicode")
    @Test fun recoveryKeyIsFirstProtector() = pwAndRecovery("bitlk-aes-xts-128-first-recovery")
    @Test fun newerMetadataEntries() = pwAndRecovery("bitlk-aes-xts-128-new-entry")
    @Test fun firstMetadataCopyCorrupt() = pwAndRecovery("bitlk-aes-xts-128-crc")
    @Test fun twoRecoveryKeys() {
        val c = conf["bitlk-aes-xts-128-two-recovery"]!!
        check("bitlk-aes-xts-128-two-recovery", c["PW"]!!, c["RP"]!!, c["RP2"]!!)
    }
    @Test fun recoveryKeyBesideSmartCard() = check("bitlk-aes-xts-128-smart-card", conf["bitlk-aes-xts-128-smart-card"]!!["RP"]!!)
    @Test fun recoveryKeyBesideStartupKey() = check("bitlk-aes-xts-128-startup-key", conf["bitlk-aes-xts-128-startup-key"]!!["RP"]!!)
    @Test fun suspendedProtectionOpensWithoutPassword() = check("bitlk-aes-xts-128-clearkey-only", "")

    @Test fun wrongPasswordIsRefused() {
        assumeTrue(ready)
        for (bad in listOf("anaconda2", "", "111111-222222-333333-444444-555555-666666-777777-888888")) {
            try {
                open("bitlk-aes-xts-128", bad).close()
                fail("opened with '$bad'")
            } catch (_: BitLockerUnlocker.WrongPasswordException) {
            }
        }
    }

    @Test fun midConversionVolumesAreRefused() {
        assumeTrue(ready)
        for (name in listOf("bitlk-partially-encrypted-aes-cbc-128", "bitlk-aes-xts-128-eow")) {
            try {
                open(name, conf[name]!!["PW"]!!).close()
                fail("$name opened")
            } catch (e: BitLockerUnlocker.UnsupportedVolumeException) {
                assertTrue(e.message!!, e.message!!.contains("cannot be opened"))
            }
        }
    }

    @Test fun recoveryPasswordParsing() {
        assertTrue(BitLockerUnlocker.parseRecoveryPassword("anaconda".toCharArray()) == null)
        // Each group must be a multiple of 11.
        assertTrue(BitLockerUnlocker.parseRecoveryPassword(
            "000001-000000-000000-000000-000000-000000-000000-000000".toCharArray()) == null)
        val k = BitLockerUnlocker.parseRecoveryPassword(
            "000011-000022-000000-000000-000000-000000-000000-720885".toCharArray())!!
        assertEquals(1, k[0].toInt()); assertEquals(2, k[2].toInt())
        assertEquals(0xFFFF, (k[14].toInt() and 0xFF) or ((k[15].toInt() and 0xFF) shl 8))
    }
}

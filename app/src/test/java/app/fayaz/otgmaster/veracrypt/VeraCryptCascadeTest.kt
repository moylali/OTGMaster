package app.fayaz.otgmaster.veracrypt

import app.fayaz.otgmaster.block.RawBlockDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Every cipher and cascade the app offers, with SHA-512 and with Argon2id, against volumes
 * VeraCrypt 1.26.29 made itself (app/src/test/resources/veracrypt/, from
 * scripts/make_veracrypt_fixtures.sh).
 *
 * Each fixture is a volume's header sector and the first sector of its data area, which
 * VeraCrypt formatted as FAT. Unlocking through [VeraCryptUnlocker] and reading block 0 of
 * the result exercises the whole path — the KDF and its PIM rules, the header cascade, the
 * master-key layout and the data tweak — and has only one way to produce a FAT boot sector.
 * A wrong cascade order, key split or tweak gives noise, not a near miss.
 */
class VeraCryptCascadeTest {

    /** Sector 0 is the header and sector 256 the first data sector; the rest reads as zeros. */
    private class FixtureDevice(fixture: ByteArray) : RawBlockDevice {
        private val sectors = mapOf(0L to fixture.copyOfRange(0, 512), 256L to fixture.copyOfRange(512, 1024))
        override val blockSize = 512
        override val blockCount = 4096L  // 2 MiB, the size of the container it came from
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            val out = ByteArray(blockCount * 512)
            for (i in 0 until blockCount) sectors[startBlock + i]?.copyInto(out, i * 512)
            return out
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) = throw UnsupportedOperationException()
        override fun close() {}
    }

    companion object {
        private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "scripts/build_host_native.sh").exists() }
        private val fixtures = File(repo, "app/src/test/resources/veracrypt")
        private const val PASSWORD = "otgmaster-test-password-2026"
        private var ready = false

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

    private fun fixture(cipher: VeraCryptCipher, hash: VeraCryptHash) =
        File(fixtures, "${cipher.displayName}-${hash.displayName}.bin").readBytes()

    private fun unlock(bytes: ByteArray, cipher: VeraCryptCipher, hash: VeraCryptHash,
                       password: String = PASSWORD): RawBlockDevice {
        val device = FixtureDevice(bytes)
        val candidate = VolumeCandidate("fixture", 0, device.blockCount, ContainerType.VERACRYPT)
        return VeraCryptUnlocker().unlock(device, candidate, password.toCharArray(), pim = 1,
            cipher = cipher, hash = hash)
    }

    private fun assertFatBootSector(sector: ByteArray, what: String) {
        // A jump instruction, then the boot signature: the odds of noise matching both are 2^-24.
        assertTrue("$what: no x86 jump at byte 0 (0x%02x)".format(sector[0]),
            sector[0] == 0xEB.toByte() || sector[0] == 0xE9.toByte())
        assertEquals("$what: boot signature", 0x55, sector[510].toInt() and 0xFF)
        assertEquals("$what: boot signature", 0xAA, sector[511].toInt() and 0xFF)
    }

    @Test fun everyCipherWithEverySupportedKdfOpensVeraCryptsOwnVolume() {
        assumeTrue("gcc not available", ready)
        val supported = VeraCryptHash.entries.filter { it.isSupported }
        assertEquals(listOf(VeraCryptHash.SHA512, VeraCryptHash.ARGON2), supported)
        for (cipher in VeraCryptCipher.entries) for (hash in supported) {
            val what = "${cipher.displayName} / ${hash.displayName}"
            val volume = unlock(fixture(cipher, hash), cipher, hash)
            assertFatBootSector(volume.readBlocks(0, 1), what)
        }
    }

    @Test fun aWrongPasswordIsRefusedNotMisread() {
        assumeTrue("gcc not available", ready)
        for (hash in listOf(VeraCryptHash.SHA512, VeraCryptHash.ARGON2)) {
            try {
                unlock(fixture(VeraCryptCipher.AES_TWOFISH_SERPENT, hash), VeraCryptCipher.AES_TWOFISH_SERPENT,
                    hash, password = PASSWORD.reversed())
                fail("${hash.displayName}: a wrong password unlocked the volume")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!, e.message!!.startsWith("Wrong password"))
            }
        }
    }

    /**
     * A cascade opened as one of its own members, or as the same ciphers in the other
     * order, must not decrypt: that is the check that the order is VeraCrypt's and not
     * merely self-consistent.
     */
    @Test fun aCascadeOnlyOpensAsItself() {
        assumeTrue("gcc not available", ready)
        val bytes = fixture(VeraCryptCipher.SERPENT_AES, VeraCryptHash.SHA512)
        for (other in listOf(VeraCryptCipher.AES, VeraCryptCipher.SERPENT, VeraCryptCipher.AES_TWOFISH)) {
            try {
                unlock(bytes, other, VeraCryptHash.SHA512)
                fail("a Serpent-AES volume opened as ${other.displayName}")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    /** get_argon2_params and get_pkcs5_iteration_count, with the examples VeraCrypt documents. */
    @Test fun pimRulesMatchVeraCrypt() {
        fun argon2(pim: Int?) = KdfParams.forVolume(VeraCryptHash.ARGON2, pim)
        assertEquals(KdfParams(6, 425_984), argon2(null))     // no PIM is PIM 12: 416 MiB, 6 passes
        assertEquals(KdfParams(6, 425_984), argon2(12))
        assertEquals(KdfParams(3, 65_536), argon2(1))         // 64 MiB, 3 passes
        assertEquals(KdfParams(13, 1_048_576), argon2(31))    // memory capped at 1 GiB
        assertEquals(KdfParams(14, 1_048_576), argon2(32))    // then one pass per step
        assertEquals(KdfParams(500_000, 0), KdfParams.forVolume(VeraCryptHash.SHA512, null))
        assertEquals(KdfParams(16_000, 0), KdfParams.forVolume(VeraCryptHash.SHA512, 1))
        assertEquals(KdfParams(500_000, 0), KdfParams.forVolume(VeraCryptHash.SHA512, 485))
    }
}

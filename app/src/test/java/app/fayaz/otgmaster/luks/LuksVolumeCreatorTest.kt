package app.fayaz.otgmaster.luks

import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.format.DriveFormatter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * LUKS2 volumes made by the app's LuksVolumeCreator, checked by cryptsetup itself:
 * `luksDump` must accept the header, cryptsetup must unlock the keyslot with the password
 * (scripts/luks_payload.py asks it for the volume key, then decrypts the data segment with
 * Python's `cryptography`, not the app's code), and the filesystem inside must pass its
 * own checker. The app's LuksUnlocker must open it too.
 */
class LuksVolumeCreatorTest {

    private class RwDevice(file: File) : RawBlockDevice {
        private val raf = RandomAccessFile(file, "rw")
        override val blockSize = 512
        override val blockCount = file.length() / 512
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            val out = ByteArray(blockCount * 512); raf.seek(startBlock * 512); raf.readFully(out); return out
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) { raf.seek(startBlock * 512); raf.write(data) }
        override fun close() = raf.close()
    }

    companion object {
        private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "scripts/build_host_native.sh").exists() }
        private var ready = false
        private const val PASSWORD = "otgmaster-test-password-2026"

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

    private fun checker(fs: DriveFormatter.Filesystem): List<String> = when (fs) {
        DriveFormatter.Filesystem.EXT4 -> listOf("/usr/sbin/e2fsck", "-fn")
        DriveFormatter.Filesystem.EXFAT -> listOf("/usr/sbin/fsck.exfat", "-n")
        DriveFormatter.Filesystem.FAT32 -> listOf("/usr/sbin/fsck.vfat", "-n")
        DriveFormatter.Filesystem.NTFS -> listOf("ntfsfix", "-n")
    }

    @Test
    fun cryptsetupOpensItAndTheFilesystemInsideIsClean() {
        assumeTrue("host toolchain or cryptsetup unavailable", ready)
        for (fs in DriveFormatter.Filesystem.entries) {
            val img = temp("luks2-$fs")
            // 16 MiB of header and keyslots, then 64 MiB of data: enough for FAT32's minimum.
            RandomAccessFile(img, "rw").use { it.setLength(80L shl 20) }
            RwDevice(img).use { raw ->
                val volume = LuksVolumeCreator.create(raw, PASSWORD.toByteArray(), label = "OTGTEST")
                DriveFormatter.writeFilesystem(volume, fs, volume.blockCount, "OTGLUKS", null, 0,
                    DriveFormatter.Progress { _, _ -> }, full = false)
                volume.close()
            }

            val (drc, dump) = run("cryptsetup", "luksDump", "--disable-locks", img.path)
            assertEquals("luksDump:\n$dump", 0, drc)
            assertTrue(dump, dump.contains("Version:       \t2") || Regex("Version:\\s+2").containsMatchIn(dump))
            assertTrue(dump, dump.contains("argon2id"))
            val (trc, tout) = run("cryptsetup", "open", "--test-passphrase", "--disable-locks", img.path, input = PASSWORD)
            assertEquals("cryptsetup --test-passphrase:\n$tout", 0, trc)
            val (wrc, _) = run("cryptsetup", "open", "--test-passphrase", "--disable-locks", img.path, input = "wrong")
            assertTrue("a wrong password was accepted", wrc != 0)

            val plain = temp("plain-$fs")
            val (prc, pout) = run("python3", File(repo, "scripts/luks_payload.py").path, "decrypt", img.path, PASSWORD, plain.path)
            assertEquals("independent decrypt: $pout", 0, prc)
            assertEquals((80L - 16) shl 20, plain.length())
            val (crc, cout) = run(*(checker(fs) + plain.path).toTypedArray())
            assertEquals("$fs inside LUKS2, checked after an independent decryption:\n$cout", 0, crc)

            RwDevice(img).use { raw ->
                val dev = LuksUnlocker().unlock(raw, 0, raw.blockCount, PASSWORD.toByteArray())
                assertEquals(plain.length() / 512, dev.blockCount)
                assertTrue("$fs: the app reads the same plaintext", dev.readBlocks(0, 64)
                    .contentEquals(plain.readBytes().copyOf(64 * 512)))
                dev.close()
            }
        }
    }

    /**
     * The unlocker's Argon2id is now the vendored reference code (it was Argon2Kt): a
     * keyslot cryptsetup made with four lanes, as its default is, must still open.
     */
    @Test
    fun anArgon2idKeyslotFromCryptsetupOpens() {
        assumeTrue("host toolchain or cryptsetup unavailable", ready)
        val img = temp("cs-argon2")
        RandomAccessFile(img, "rw").use { it.setLength(24L shl 20) }
        val (rc, out) = run("cryptsetup", "luksFormat", "--type", "luks2", "--batch-mode", "--disable-locks",
            "--cipher", "aes-xts-plain64", "--key-size", "512", "--pbkdf", "argon2id", "--pbkdf-memory", "65536",
            "--pbkdf-parallel", "4", "--iter-time", "200", "--sector-size", "512",
            "--luks2-metadata-size", "16k", "--luks2-keyslots-size", "1m", img.path, "-", input = PASSWORD)
        assertEquals("luksFormat: $out", 0, rc)
        RwDevice(img).use { raw ->
            LuksUnlocker().unlock(raw, 0, raw.blockCount, PASSWORD.toByteArray()).close()
            try {
                LuksUnlocker().unlock(raw, 0, raw.blockCount, "wrong".toByteArray())
                throw AssertionError("a wrong password opened the volume")
            } catch (e: LuksUnlocker.WrongPasswordException) { }
        }
    }

    @Test
    fun afSplitIsTheInverseOfAfMerge() {
        val key = ByteArray(64) { it.toByte() }
        val split = LuksVolumeCreator.afSplit(key, 4000, java.security.SecureRandom())
        assertTrue(LuksUnlocker().afMerge(split, 4000, 64, "sha256").contentEquals(key))
    }
}

package app.fayaz.otgmaster.veracrypt

import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.format.ExFatFormatter
import app.fayaz.otgmaster.format.Fat32Formatter
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Volumes the app creates must open — in the app, and in a reader that is not the app.
 *
 * Every cipher and cascade with both KDFs is created on an image, unlocked by
 * [VeraCryptUnlocker], checked field by field, and its formatted data area run through
 * the host's fsck. The SHA-512 ones are also opened by cryptsetup's own TCRYPT code
 * (tcryptDump), which shares nothing with ours; cryptsetup has no Argon2 for VeraCrypt,
 * so those are opened in desktop VeraCrypt on the device-matrix stick instead.
 *
 * A round trip through our own reader alone would pass a header both sides get wrong
 * the same way; that is why the independent reader is here.
 */
class VeraCryptVolumeCreatorTest {

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

    private val images = mutableListOf<File>()
    @After fun tearDown() { images.forEach { it.delete() } }

    private fun image(bytes: Long): File = File.createTempFile("vccreate", ".img").also {
        images += it
        RandomAccessFile(it, "rw").use { r -> r.setLength(bytes) }
    }

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun runWithInput(input: String, vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        p.outputStream.use { it.write((input + "\n").toByteArray()) }
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 to "timed out\n$out" }
        return p.exitValue() to out
    }

    private fun unlock(dev: RawBlockDevice, cipher: VeraCryptCipher, hash: VeraCryptHash, pim: Int?,
                       password: String = PASSWORD): RawBlockDevice {
        val candidate = VolumeCandidate("image", 0, dev.blockCount, ContainerType.VERACRYPT)
        return VeraCryptUnlocker().unlock(dev, candidate, password.toCharArray(), pim = pim,
            cipher = cipher, hash = hash)
    }

    /** The decrypted data area copied to its own image, for the host's fsck. */
    private fun plainCopy(volume: RawBlockDevice, sectors: Long): File {
        val out = image(sectors * 512)
        RandomAccessFile(out, "rw").use { raf ->
            var s = 0L
            while (s < sectors) {
                val n = minOf(2048L, sectors - s).toInt()
                raf.seek(s * 512); raf.write(volume.readBlocks(s, n)); s += n
            }
        }
        return out
    }

    @Test fun everyCipherWithEveryKdfCreatesAVolumeThatOpensAndChecksClean() {
        assumeTrue("gcc not available", ready)
        val fsck = listOf("/usr/sbin/fsck.exfat", "/sbin/fsck.exfat").firstOrNull { File(it).exists() }
        for (cipher in VeraCryptCipher.entries.filter { it.isSupported })
            for (hash in VeraCryptHash.entries.filter { it.isSupported }) {
                val what = "${cipher.displayName} / ${hash.displayName}"
                // PIM 1 keeps Argon2 at 64 MiB and PBKDF2 at 16000 iterations, so the 16
                // combinations run in seconds; the default PIM is covered below.
                val f = image(8L shl 20)
                val dataSectors = VeraCryptVolumeCreator.dataBytes(f.length() / 512) / 512
                ImageDevice(f).use { dev ->
                    val vol = VeraCryptVolumeCreator.create(dev, PASSWORD.toCharArray(), 1, cipher, hash)
                    assertEquals(what, dataSectors, vol.blockCount)
                    ExFatFormatter.format(vol, totalSectors = dataSectors, label = "VC")
                }
                ImageDevice(f).use { dev ->
                    val vol = unlock(dev, cipher, hash, 1)
                    val boot = vol.readBlocks(0, 1)
                    assertEquals("$what: exFAT boot sector", "EXFAT   ", String(boot, 3, 8, Charsets.US_ASCII))
                    if (fsck != null) {
                        val (rc, out) = run(fsck, "-n", plainCopy(vol, dataSectors).absolutePath)
                        assertEquals("$what: fsck.exfat\n$out", 0, rc)
                    }
                }
            }
    }

    @Test fun headerFieldsAreVeraCryptsAndTheBackupHeaderOpensOnItsOwn() {
        assumeTrue("gcc not available", ready)
        val cipher = VeraCryptCipher.SERPENT_TWOFISH_AES
        val hash = VeraCryptHash.SHA512
        val f = image(16L shl 20)
        val hostBytes = f.length()
        ImageDevice(f).use { VeraCryptVolumeCreator.create(it, PASSWORD.toCharArray(), 1, cipher, hash) }

        val kdf = KdfParams.forVolume(hash, 1)
        val pwd = PASSWORD.toByteArray()
        fun headerAt(offset: Long): ByteArray {
            val sector = ByteArray(512)
            RandomAccessFile(f, "r").use { it.seek(offset); it.readFully(sector) }
            return VeraCryptNative.decryptHeader(cipher.nativeIds, hash.kdfNativeId!!, pwd,
                sector.copyOfRange(0, 64), kdf.iterations, kdf.memoryKiB, sector.copyOfRange(64, 512))!!
        }
        val primary = headerAt(0)
        val backup = headerAt(hostBytes - 131072)

        for ((name, h) in listOf("primary" to primary, "backup" to backup)) {
            val b = ByteBuffer.wrap(h)
            assertEquals("$name magic", "VERA", String(h, 0, 4, Charsets.US_ASCII))
            assertEquals("$name version", 5, b.getShort(4).toInt())
            assertEquals("$name min program version", 0x010B, b.getShort(6).toInt())
            val keyCrc = CRC32().apply { update(h, 192, 256) }.value.toInt()
            assertEquals("$name key area CRC", keyCrc, b.getInt(8))
            assertEquals("$name hidden volume size", 0L, b.getLong(28))
            assertEquals("$name volume size", hostBytes - 262144, b.getLong(36))
            assertEquals("$name data offset", 131072L, b.getLong(44))
            assertEquals("$name encrypted area size", hostBytes - 262144, b.getLong(52))
            assertEquals("$name flags", 0, b.getInt(60))
            assertEquals("$name sector size", 512, b.getInt(64))
            val fieldCrc = CRC32().apply { update(h, 0, 188) }.value.toInt()
            assertEquals("$name header CRC", fieldCrc, b.getInt(188))
        }
        assertArrayEquals("both headers carry the same master keys",
            primary.copyOfRange(192, 448), backup.copyOfRange(192, 448))
        val salts = RandomAccessFile(f, "r").use { r ->
            val a = ByteArray(64); val c = ByteArray(64)
            r.seek(0); r.readFully(a); r.seek(hostBytes - 131072); r.readFully(c); a to c
        }
        assertTrue("the backup header has its own salt", !salts.first.contentEquals(salts.second))
    }

    @Test fun cryptsetupOpensEverySha512Volume() {
        assumeTrue("gcc not available", ready)
        val cryptsetup = listOf("/usr/sbin/cryptsetup", "/sbin/cryptsetup").firstOrNull { File(it).exists() }
        assumeTrue("cryptsetup not available", cryptsetup != null)
        for (cipher in VeraCryptCipher.entries.filter { it.isSupported }) {
            val f = image(4L shl 20)
            // cryptsetup's --veracrypt-pim must match the PIM the volume was created with.
            ImageDevice(f).use { VeraCryptVolumeCreator.create(it, PASSWORD.toCharArray(), 1, cipher, VeraCryptHash.SHA512) }
            // The password goes on stdin: in TCRYPT mode --key-file names a VeraCrypt
            // keyfile, not the password, and cryptsetup then waits on stdin for one.
            val (rc, out) = runWithInput(PASSWORD, cryptsetup!!, "tcryptDump", "--veracrypt",
                "--veracrypt-pim=1", "--hash", "sha512", f.absolutePath)
            assertEquals("${cipher.displayName}: cryptsetup tcryptDump\n$out", 0, rc)
            // The chain's members, whatever order cryptsetup prints them in; the order
            // itself is pinned by VeraCryptCascadeTest's VeraCrypt-made fixtures, which the
            // same nativeIds open.
            val chain = Regex("Cipher chain:\\s*(\\S+)").find(out)?.groupValues?.get(1)
                ?: fail("${cipher.displayName}: no cipher chain in\n$out")
            assertEquals("${cipher.displayName}: cryptsetup read it as\n$out",
                cipher.displayName.lowercase().split("-").toSet(), chain.toString().split("-").toSet())
        }
    }

    @Test fun theDefaultPimOpensAndAWrongPasswordDoesNot() {
        assumeTrue("gcc not available", ready)
        val f = image(4L shl 20)
        ImageDevice(f).use {
            VeraCryptVolumeCreator.create(it, PASSWORD.toCharArray(), null, VeraCryptCipher.AES, VeraCryptHash.SHA512)
        }
        ImageDevice(f).use { dev ->
            unlock(dev, VeraCryptCipher.AES, VeraCryptHash.SHA512, null)
            try {
                unlock(dev, VeraCryptCipher.AES, VeraCryptHash.SHA512, null, password = PASSWORD.reversed())
                fail("a wrong password opened the new volume")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!, e.message!!.startsWith("Wrong password"))
            }
        }
    }

    @Test fun formattingTheDataAreaLeavesTheBackupHeaderAlone() {
        assumeTrue("gcc not available", ready)
        // FAT32 needs 33+ MiB; 40 MiB host. The decrypted device the unlocker returns runs
        // 128 KiB past the data area into the backup header, so a formatter sized from it
        // would destroy the backup header. The formatters are given the data area's size.
        val f = image(40L shl 20)
        val dataSectors = VeraCryptVolumeCreator.dataBytes(f.length() / 512) / 512
        val backupBefore = RandomAccessFile(f, "r").use { r -> ByteArray(131072).also { r.seek(f.length() - 131072); r.readFully(it) } }
        ImageDevice(f).use { dev ->
            // Created first, then the backup group read back: the creator wrote it.
            VeraCryptVolumeCreator.create(dev, PASSWORD.toCharArray(), 1, VeraCryptCipher.AES, VeraCryptHash.SHA512)
        }
        val backupAfterCreate = RandomAccessFile(f, "r").use { r -> ByteArray(131072).also { r.seek(f.length() - 131072); r.readFully(it) } }
        assertTrue("the creator wrote the backup group", !backupBefore.contentEquals(backupAfterCreate))
        ImageDevice(f).use { dev ->
            val vol = unlock(dev, VeraCryptCipher.AES, VeraCryptHash.SHA512, 1)
            Fat32Formatter.format(vol, totalSectors = dataSectors, label = "VCFAT")
        }
        val backupAfterFormat = RandomAccessFile(f, "r").use { r -> ByteArray(131072).also { r.seek(f.length() - 131072); r.readFully(it) } }
        assertArrayEquals("formatting reached the backup header", backupAfterCreate, backupAfterFormat)
    }

    @Test fun passwordRulesAreVeraCrypts() {
        assertEquals(null, VeraCryptVolumeCreator.passwordProblem("short".toCharArray(), null))
        assertTrue(VeraCryptVolumeCreator.passwordProblem("short".toCharArray(), 1)!!.contains("485"))
        assertEquals(null, VeraCryptVolumeCreator.passwordProblem("short".toCharArray(), 485))
        assertEquals(null, VeraCryptVolumeCreator.passwordProblem(PASSWORD.toCharArray(), 1))
        assertTrue(VeraCryptVolumeCreator.passwordProblem(CharArray(129) { 'a' }, null)!!.contains("128"))
    }
}

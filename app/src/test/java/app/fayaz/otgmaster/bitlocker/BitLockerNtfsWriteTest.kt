package app.fayaz.otgmaster.bitlocker

import app.fayaz.otgmaster.block.CachedBlockDevice
import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.ntfs.NtfsFileSystem
import me.jahnen.libaums.core.fs.UsbFile
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Writes NTFS inside BitLocker — the app's NTFS driver over the app's BitLocker
 * device, on Windows-made volumes — and then judges the result without the app:
 * scripts/bitlk_decrypt.py decrypts the image with the key cryptsetup extracts
 * and Python's AES, and the plaintext goes to ntfs_check.py, `ntfsfix -n` and
 * `ntfscat`.
 *
 * The app reading back what it wrote proves nothing on its own: a wrong IV or a
 * sector written to the wrong place round-trips perfectly through the same code.
 * An independent decryptor finding a clean filesystem with the right bytes is
 * what shows the ciphertext is what BitLocker itself would have written.
 *
 * Also required: the BitLocker metadata areas are byte-identical afterwards. They
 * hold the keys; a write that strayed into them would lock the volume for good.
 */
class BitLockerNtfsWriteTest {

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

    private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "scripts/build_host_native.sh").exists() }
    private val tmp = mutableListOf<File>()

    @After fun cleanUp() { tmp.forEach { it.delete() } }

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun toolsPresent() = listOf("cryptsetup", "python3", "ntfsfix", "ntfscat").all {
        run("sh", "-c", "command -v $it").first == 0
    } && run("python3", "-c", "import cryptography").first == 0

    private fun copyOf(name: String): File {
        BitLockerCompatTest.setUp()
        val src = File(repo, "app/build/bitlk-images/bitlk-images/$name.img")
        assumeTrue("test images unavailable", src.exists())
        return File.createTempFile(name, ".img").also { src.copyTo(it, overwrite = true); tmp += it }
    }

    /**
     * A fresh BitLocker volume around a real NTFS, made by
     * scripts/make_bitlocker_image.py and accepted by cryptsetup (--verify) before
     * the app sees it. cryptsetup's own sample images cannot be used for this: their
     * unused ciphertext is zeroed, so most of the NTFS inside decrypts to noise.
     */
    private fun generated(cipher: String): File {
        BitLockerCompatTest.setUp()
        val plain = File.createTempFile("plain-$cipher", ".img").also { tmp += it }
        java.io.RandomAccessFile(plain, "rw").use { it.setLength(NTFS_SIZE) }
        val (mrc, mout) = run("mkntfs", "-F", "-q", "-f", "-L", "OTGBL", plain.path)
        assertEquals("mkntfs: $mout", 0, mrc)
        val img = File.createTempFile("bitlocker-$cipher", ".img").also { tmp += it }
        val (rc, out) = run("python3", File(repo, "scripts/make_bitlocker_image.py").path, plain.path, img.path,
            "--password", PASSWORD, "--recovery", RECOVERY, "--cipher", cipher, "--verify")
        assertEquals("make_bitlocker_image.py:\n$out", 0, rc)
        return img
    }

    private fun <R> withNtfs(img: File, password: String, body: (NtfsFileSystem) -> R): R {
        val raw = RwDevice(img)
        val bl = BitLockerUnlocker().unlock(raw, 0, raw.blockCount, password.toCharArray())
        val cached = CachedBlockDevice(bl)
        val fs = NtfsFileSystem.mount(cached, readOnly = false)
        try {
            return body(fs)
        } finally {
            fs.unmount()
            cached.close()
            raw.close()
        }
    }

    private fun metadataBytes(img: File, header: BitLockerHeader): List<ByteArray> =
        RandomAccessFile(img, "r").use { f ->
            header.metadataOffsets.map { off ->
                ByteArray(BitLockerHeader.METADATA_AREA_SIZE).also { f.seek(off); f.readFully(it) }
            } + listOf(ByteArray(512).also { f.seek(0); f.readFully(it) })
        }

    private fun headerOf(img: File): BitLockerHeader = RandomAccessFile(img, "r").use { f ->
        BitLockerHeader.parse { off, len -> ByteArray(len).also { f.seek(off); f.readFully(it) } }
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun UsbFile.readAll(): ByteArray {
        val out = ByteArray(length.toInt())
        var o = 0
        while (o < out.size) { val n = minOf(65536, out.size - o); read(o.toLong(), ByteBuffer.wrap(out, o, n)); o += n }
        return out
    }

    private fun writeAndVerify(name: String, secret: String = PASSWORD) {
        assumeTrue("cryptsetup / python cryptography / ntfs-3g tools missing", toolsPresent())
        val img = generated(name)
        val before = metadataBytes(img, headerOf(img))
        val rnd = Random(name.hashCode())
        val big = rnd.nextBytes(5_000_000)
        val small = "written through BitLocker\n".toByteArray()

        withNtfs(img, secret) { fs ->
            assertNull("the Windows-made NTFS volume mounted read-only: ${fs.readOnlyReason}", fs.readOnlyReason)
            val root = fs.rootDirectory
            val dir = root.createDirectory("otg-bitlocker")
            dir.createFile("big.bin").apply {
                var o = 0
                while (o < big.size) {
                    val n = minOf(1 + rnd.nextInt(300_000), big.size - o)
                    write(o.toLong(), ByteBuffer.wrap(big, o, n)); o += n
                }
            }
            dir.createFile("small.txt").write(0, ByteBuffer.wrap(small))
            dir.createFile("gone.txt").write(0, ByteBuffer.wrap("x".toByteArray()))
            dir.search("gone.txt")!!.delete()
            dir.search("small.txt")!!.name = "renamed.txt"
        }

        // The keys must be untouched.
        val after = metadataBytes(img, headerOf(img))
        for (i in before.indices) assertArrayEquals("BitLocker metadata area $i changed", before[i], after[i])

        // Independent decryption, then the NTFS checkers on the plaintext.
        val plain = File.createTempFile("$name-plain", ".img").also { tmp += it }
        val (drc, dout) = run("python3", File(repo, "scripts/bitlk_decrypt.py").path, img.path, PASSWORD, plain.path)
        assertEquals("independent decrypt failed:\n$dout", 0, drc)
        // The generated volumes keep BitLocker's metadata in a tail after the NTFS
        // (Windows keeps it inside, as reserved files), so the NTFS ends before the
        // device does. ntfsfix looks for the backup boot sector in the device's last
        // sector, which here is that zero tail; cut the plaintext to the NTFS itself.
        RandomAccessFile(plain, "rw").use { it.setLength(NTFS_SIZE) }
        val (crc, cout) = run("python3", File(repo, "scripts/ntfs_check.py").path, plain.path)
        assertTrue("ntfs_check.py on the independently decrypted volume:\n$cout", crc == 0)
        val (frc, fout) = run("ntfsfix", "-n", plain.path)
        assertTrue("ntfsfix -n:\n$fout", frc == 0)
        for ((path, want) in listOf("/otg-bitlocker/big.bin" to big, "/otg-bitlocker/renamed.txt" to small)) {
            val p = ProcessBuilder("ntfscat", plain.path, path).start()
            val got = p.inputStream.readBytes()
            assertEquals("ntfscat $path: ${p.errorStream.bufferedReader().readText()}", 0, p.waitFor())
            assertEquals("$path through the independent decryptor", sha(want), sha(got))
        }

        // And the app reads it back after a fresh unlock, with the other secret.
        withNtfs(img, if (secret == PASSWORD) RECOVERY else PASSWORD) { fs ->
            val dir = fs.rootDirectory.search("otg-bitlocker")!!
            assertEquals(setOf("big.bin", "renamed.txt"), dir.list().toSet())
            assertEquals(sha(big), sha(dir.search("big.bin")!!.readAll()))
        }
    }

    @Test fun xts128() = writeAndVerify("xts128")
    @Test fun xts256() = writeAndVerify("xts256")
    @Test fun cbc128() = writeAndVerify("cbc128")
    @Test fun cbc256UnlockedWithRecoveryKey() = writeAndVerify("cbc256", RECOVERY)

    /**
     * Block-level writes on Windows-made volumes, in every cipher mode including
     * Elephant, which none of the generated volumes use. Random runs are written
     * through the app; then, for the modes scripts/bitlk_decrypt.py implements, the
     * independent decryption must show exactly those bytes there and cryptsetup's
     * plaintext everywhere else; for Elephant, a fresh unlock must read them back.
     */
    private fun blockWrites(name: String, independent: Boolean) {
        assumeTrue(toolsPresent())
        val img = copyOf(name)
        val before = metadataBytes(img, headerOf(img))
        val rnd = Random(name.hashCode())
        val writes = mutableListOf<Pair<Long, ByteArray>>()
        val raw = RwDevice(img)
        val dev = BitLockerUnlocker().unlock(raw, 0, raw.blockCount, "anaconda".toCharArray())
        val holes = (dev.header.metadataOffsets.toList() + dev.header.volumeHeaderOffset).map { it / 512 }
        repeat(40) {
            val n = 1 + rnd.nextInt(24)
            var start: Long
            do { start = rnd.nextLong(dev.blockCount - n) } while (holes.any { h -> start < h + 129 && start + n > h - 1 })
            val data = rnd.nextBytes(n * 512)
            dev.writeBlocks(start, data)
            writes += start to data
        }
        // Including the relocated first sectors.
        val first = rnd.nextBytes(4096)
        dev.writeBlocks(0, first)
        writes += 0L to first
        dev.close(); raw.close()

        val after = metadataBytes(img, headerOf(img))
        for (i in before.indices) assertArrayEquals("metadata area $i changed", before[i], after[i])

        val check: (Long, Int) -> ByteArray = if (independent) {
            val plain = File.createTempFile("$name-plain", ".img").also { tmp += it }
            val (rc, out) = run("python3", File(repo, "scripts/bitlk_decrypt.py").path, img.path, "anaconda", plain.path)
            assertEquals(out, 0, rc)
            val f = RandomAccessFile(plain, "r");
            { b, n -> ByteArray(n * 512).also { f.seek(b * 512); f.readFully(it) } }
        } else {
            val raw2 = BitLockerCompatTest.FileDevice(img)
            val dev2 = BitLockerUnlocker().unlock(raw2, 0, raw2.blockCount, "anaconda".toCharArray());
            { b, n -> dev2.readBlocks(b, n) }
        }
        // Later writes win where runs overlap.
        val expected = HashMap<Long, ByteArray>()
        for ((s, d) in writes) for (i in 0 until d.size / 512) expected[s + i] = d.copyOfRange(i * 512, i * 512 + 512)
        for ((b, want) in expected) assertArrayEquals("block $b", want, check(b, 1))
    }

    @Test fun blockWritesXts() = blockWrites("bitlk-aes-xts-128", independent = true)
    @Test fun blockWritesCbc() = blockWrites("bitlk-aes-cbc-256", independent = true)
    @Test fun blockWrites4kSectors() = blockWrites("bitlk-aes-xts-128-4k", independent = true)
    @Test fun blockWritesElephant128() = blockWrites("bitlk-aes-cbc-elephant-128", independent = false)
    @Test fun blockWritesElephant256() = blockWrites("bitlk-aes-cbc-elephant-256", independent = false)

    /** A write into BitLocker's own metadata must be refused, not performed. */
    @Test fun writesIntoMetadataAreRefused() {
        assumeTrue(toolsPresent())
        val img = copyOf("bitlk-aes-xts-128")
        val raw = RwDevice(img)
        val dev = BitLockerUnlocker().unlock(raw, 0, raw.blockCount, "anaconda".toCharArray())
        for (off in dev.header.metadataOffsets.toList() + dev.header.volumeHeaderOffset) {
            try {
                dev.writeBlocks(off / 512, ByteArray(512))
                org.junit.Assert.fail("wrote into BitLocker metadata at $off")
            } catch (_: java.io.IOException) {
            }
        }
        dev.close(); raw.close()
        assertEquals(sha(File(repo, "app/build/bitlk-images/bitlk-images/bitlk-aes-xts-128.img").readBytes()),
            sha(img.readBytes()))
    }

    private companion object {
        const val PASSWORD = "password123"
        const val NTFS_SIZE = 48L shl 20
        const val RECOVERY = "111111-222222-333333-444444-555555-666666-111111-222222"
    }
}

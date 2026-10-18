package app.fayaz.otgmaster.format

import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.block.RawBlockDeviceAdapter
import app.fayaz.otgmaster.veracrypt.ContainerType
import app.fayaz.otgmaster.veracrypt.VeraCryptCipher
import app.fayaz.otgmaster.veracrypt.VeraCryptHash
import app.fayaz.otgmaster.veracrypt.VeraCryptUnlocker
import app.fayaz.otgmaster.veracrypt.VolumeCandidate
import me.jahnen.libaums.core.driver.ByteBlockDevice
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Format and Encrypt as the app runs them, on volumes the host's own mkfs made and filled.
 *
 * Reformat must keep the kind, size, cluster size and label and leave nothing listed;
 * full must leave no byte of the old files anywhere in the image, and quick is checked
 * to do what the UI says it does (leave them). Encrypt must leave a volume the app
 * unlocks, holding a filesystem the host's fsck accepts.
 */
class DriveFormatterTest {

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
        private var nativeReady = false

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
            nativeReady = true
        }

        /** 64 KiB that occurs nowhere by chance; written into files to look for afterwards. */
        private val MARKER = ByteArray(65536) { i -> ((i * 131 + 7) xor (i ushr 8)).toByte() }
    }

    private val images = mutableListOf<File>()
    @After fun tearDown() { images.forEach { it.delete() } }

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun tool(name: String) = listOf("/usr/sbin/$name", "/sbin/$name", "/usr/bin/$name").firstOrNull { File(it).exists() }

    private fun image(bytes: Long): File = File.createTempFile("drivefmt", ".img").also {
        images += it
        RandomAccessFile(it, "rw").use { r -> r.setLength(bytes) }
    }

    private fun fat32WithFiles(mib: Long, clusterSectors: Int, label: String): File {
        val mkfs = tool("mkfs.vfat"); assumeTrue("mkfs.vfat not available", mkfs != null)
        val f = image(mib shl 20)
        val (rc, out) = run(mkfs!!, "-F", "32", "-s", clusterSectors.toString(), "-n", label, f.absolutePath)
        assumeTrue("mkfs.vfat failed: $out", rc == 0)
        ImageDevice(f).use { dev ->
            val root = Fat32FileSystem.read(ByteBlockDevice(RawBlockDeviceAdapter(dev)))!!.rootDirectory
            val dir = root.createDirectory("keep")
            for (i in 0 until 4) {
                val file = dir.createFile("marker$i.bin")
                file.write(0, ByteBuffer.wrap(MARKER)); file.close()
            }
        }
        assertTrue("the marker is on the image before formatting", contains(f, MARKER))
        return f
    }

    private fun contains(f: File, needle: ByteArray): Boolean {
        // The marker is written at cluster starts, so a 512-byte-aligned scan finds it.
        val head = needle.copyOf(512)
        RandomAccessFile(f, "r").use { raf ->
            val buf = ByteArray(1 shl 20)
            var off = 0L
            while (off < raf.length()) {
                raf.seek(off); val n = raf.read(buf)
                var i = 0
                while (i + 512 <= n) {
                    if (buf[i] == head[0] && buf.copyOfRange(i, i + 512).contentEquals(head)) return true
                    i += 512
                }
                off += n
            }
        }
        return false
    }

    private fun assertFat32Clean(f: File) {
        val fsck = tool("fsck.vfat"); assumeTrue("fsck.vfat not available", fsck != null)
        val (rc, out) = run(fsck!!, "-n", f.absolutePath)
        assertEquals("fsck.vfat\n$out", 0, rc)
    }

    private fun assertExFatClean(f: File) {
        val fsck = tool("fsck.exfat"); assumeTrue("fsck.exfat not available", fsck != null)
        val (rc, out) = run(fsck!!, "-n", f.absolutePath)
        assertEquals("fsck.exfat\n$out", 0, rc)
    }

    @Test fun reformattingFat32KeepsItsShapeAndLabelAndListsNothing() {
        val f = fat32WithFiles(300, 8, "KEEPME")
        val before = ImageDevice(f).use { DriveFormatter.readExisting(it, "KEEPME") }
        assertEquals(DriveFormatter.Filesystem.FAT32, before.filesystem)
        assertEquals(4096, before.clusterBytes)
        // mkfs.vfat leaves a few sectors of the image unused (614376 of 614400 here);
        // Format keeps the volume's own size, not the device's.
        assertTrue(before.totalSectors <= f.length() / 512)

        ImageDevice(f).use { DriveFormatter.reformat(it, before, full = false) }
        assertFat32Clean(f)
        val after = ImageDevice(f).use { DriveFormatter.readExisting(it, "KEEPME") }
        assertEquals(before, after)
        ImageDevice(f).use { dev ->
            val fs = Fat32FileSystem.read(ByteBlockDevice(RawBlockDeviceAdapter(dev)))!!
            assertEquals(0, fs.rootDirectory.listFiles().size)
            assertEquals("KEEPME", fs.volumeLabel.trim())
        }
        // Quick leaves the old file contents on the drive, as the screen says it does.
        assertTrue("quick format removed file contents", contains(f, MARKER))
    }

    @Test fun aFullFormatLeavesNoByteOfTheOldFiles() {
        val f = fat32WithFiles(200, 4, "FULL")
        ImageDevice(f).use { dev ->
            DriveFormatter.reformat(dev, DriveFormatter.readExisting(dev, "FULL"), full = true)
        }
        assertFat32Clean(f)
        assertFalse("old file contents survived a full format", contains(f, MARKER))
    }

    @Test fun reformattingExFatKeepsItsClusterSizeAndLabel() {
        val mkfs = tool("mkfs.exfat"); assumeTrue("mkfs.exfat not available", mkfs != null)
        val f = image(300L shl 20)
        val (rc, out) = run(mkfs!!, "-c", "64K", "-L", "KeepEx", f.absolutePath)
        assumeTrue("mkfs.exfat failed: $out", rc == 0)
        val before = ImageDevice(f).use { DriveFormatter.readExisting(it, "KeepEx") }
        assertEquals(DriveFormatter.Filesystem.EXFAT, before.filesystem)
        assertEquals(65536, before.clusterBytes)

        ImageDevice(f).use { DriveFormatter.reformat(it, before, full = true) }
        assertExFatClean(f)
        assertEquals(before, ImageDevice(f).use { DriveFormatter.readExisting(it, "KeepEx") })
        tool("exfatlabel")?.let { assertTrue(run(it, f.absolutePath).second.contains("KeepEx")) }
    }

    @Test fun formattingFat32AsExFatGivesACleanExFatOfTheSameSizeAndLabel() {
        val f = fat32WithFiles(300, 8, "SWAPME")
        val before = ImageDevice(f).use { DriveFormatter.readExisting(it, "SWAPME") }
        ImageDevice(f).use { DriveFormatter.reformat(it, before, full = false, target = DriveFormatter.Filesystem.EXFAT) }
        assertExFatClean(f)
        val after = ImageDevice(f).use { DriveFormatter.readExisting(it, "SWAPME") }
        assertEquals(DriveFormatter.Filesystem.EXFAT, after.filesystem)
        assertEquals(before.totalSectors, after.totalSectors)
        assertEquals(before.partitionOffset, after.partitionOffset)
        // exFAT's own default for 300 MiB, not FAT32's 4 KiB carried across.
        assertEquals(ExFatFormatter.defaultClusterBytes(before.totalSectors * 512), after.clusterBytes)
        tool("exfatlabel")?.let { assertTrue(run(it, f.absolutePath).second.contains("SWAPME")) }
    }

    @Test fun formattingExFatAsFat32GivesACleanFat32ThatListsNothing() {
        val mkfs = tool("mkfs.exfat"); assumeTrue("mkfs.exfat not available", mkfs != null)
        val f = image(300L shl 20)
        val (rc, out) = run(mkfs!!, "-c", "128K", "-L", "toFat", f.absolutePath)
        assumeTrue("mkfs.exfat failed: $out", rc == 0)
        val before = ImageDevice(f).use { DriveFormatter.readExisting(it, "toFat") }
        ImageDevice(f).use { DriveFormatter.reformat(it, before, full = true, target = DriveFormatter.Filesystem.FAT32) }
        assertFat32Clean(f)
        val after = ImageDevice(f).use { DriveFormatter.readExisting(it, "toFat") }
        assertEquals(DriveFormatter.Filesystem.FAT32, after.filesystem)
        assertEquals(before.totalSectors, after.totalSectors)
        assertEquals(Fat32Formatter.defaultClusterBytes(before.totalSectors * 512), after.clusterBytes)
        ImageDevice(f).use { dev ->
            val fs = Fat32FileSystem.read(ByteBlockDevice(RawBlockDeviceAdapter(dev)))!!
            assertEquals(0, fs.rootDirectory.listFiles().size)
            assertEquals("TOFAT", fs.volumeLabel.trim())
        }
    }

    @Test fun aVolumeFat32CannotHoldIsRefusedBeforeAnythingIsWritten() {
        val mkfs = tool("mkfs.exfat"); assumeTrue("mkfs.exfat not available", mkfs != null)
        val f = image(16L shl 20)
        val (rc, out) = run(mkfs!!, "-L", "small", f.absolutePath)
        assumeTrue("mkfs.exfat failed: $out", rc == 0)
        val bytes = f.readBytes()
        val before = ImageDevice(f).use { DriveFormatter.readExisting(it, "small") }
        try {
            ImageDevice(f).use { DriveFormatter.reformat(it, before, full = true, target = DriveFormatter.Filesystem.FAT32) }
            throw AssertionError("a 16 MiB volume was formatted as FAT32")
        } catch (_: IllegalArgumentException) {}
        assertTrue("the refused format changed the volume", bytes.contentEquals(f.readBytes()))
    }

    @Test fun onlyExFatAndFat32CanBeReformatted() {
        val f = image(64L shl 20)
        try {
            ImageDevice(f).use { DriveFormatter.readExisting(it, "") }
            throw AssertionError("an unformatted image was accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.contains("exFAT and FAT32"))
        }
    }

    private fun unlockedCopy(f: File, cipher: VeraCryptCipher, hash: VeraCryptHash, pim: Int?): File {
        val out = image(VeraCryptVolumeCreatorSize.dataBytes(f))
        ImageDevice(f).use { dev ->
            val candidate = VolumeCandidate("image", 0, dev.blockCount, ContainerType.VERACRYPT)
            val vol = VeraCryptUnlocker().unlock(dev, candidate, PASSWORD.toCharArray(), pim = pim, cipher = cipher, hash = hash)
            RandomAccessFile(out, "rw").use { raf ->
                val sectors = out.length() / 512
                var s = 0L
                while (s < sectors) {
                    val n = minOf(2048L, sectors - s).toInt()
                    raf.seek(s * 512); raf.write(vol.readBlocks(s, n)); s += n
                }
            }
        }
        return out
    }

    private object VeraCryptVolumeCreatorSize {
        fun dataBytes(f: File) = app.fayaz.otgmaster.veracrypt.VeraCryptVolumeCreator.dataBytes(f.length() / 512)
    }

    @Test fun encryptingToExFatAndToFat32LeavesAVolumeTheAppOpensAndFsckAccepts() {
        assumeTrue("gcc not available", nativeReady)
        for ((fs, cipher, hash) in listOf(
            Triple(DriveFormatter.Filesystem.EXFAT, VeraCryptCipher.SERPENT_TWOFISH_AES, VeraCryptHash.ARGON2),
            Triple(DriveFormatter.Filesystem.FAT32, VeraCryptCipher.AES, VeraCryptHash.SHA512),
        )) {
            val f = fat32WithFiles(80, 1, "PLAIN")
            ImageDevice(f).use { dev ->
                DriveFormatter.encrypt(dev, PASSWORD.toCharArray(), 1, cipher, hash, fs, "Secret", full = true)
            }
            assertFalse("${fs.displayName}: plaintext survived encrypting", contains(f, MARKER))
            val plain = unlockedCopy(f, cipher, hash, 1)
            if (fs == DriveFormatter.Filesystem.EXFAT) assertExFatClean(plain) else assertFat32Clean(plain)
            val existing = ImageDevice(plain).use { DriveFormatter.readExisting(it, "") }
            assertEquals(fs, existing.filesystem)
        }
    }

    @Test fun encryptRefusesASizeItsFilesystemCannotTakeBeforeWritingAnything() {
        assumeTrue("gcc not available", nativeReady)
        // 20 MiB is too small for FAT32; the drive must be left exactly as it was.
        val f = image(20L shl 20)
        RandomAccessFile(f, "rw").use { it.write(MARKER) }
        try {
            ImageDevice(f).use { dev ->
                DriveFormatter.encrypt(dev, PASSWORD.toCharArray(), 1, VeraCryptCipher.AES, VeraCryptHash.SHA512,
                    DriveFormatter.Filesystem.FAT32, "", full = false)
            }
            throw AssertionError("a 20 MiB FAT32 volume was accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.startsWith("Too small for FAT32"))
        }
        assertTrue("the refused encrypt wrote to the drive", contains(f, MARKER))
    }
}

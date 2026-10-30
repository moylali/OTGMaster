package app.fayaz.otgmaster.recovery

import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.block.RawBlockDeviceAdapter
import me.jahnen.libaums.core.driver.ByteBlockDevice
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystemCreator
import me.jahnen.libaums.core.partition.PartitionTableEntry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.Random

/**
 * Deleted-file listing on FAT32: a real mkfs.vfat volume, files written and deleted
 * through the FAT driver the app ships, then scanned by FatRecovery. It must list the
 * deleted files and only them: not live files, and not the old entry of a moved file.
 */
class FatRecoveryTest {
    private class Img(f: File) : RawBlockDevice {
        private val raf = RandomAccessFile(f, "rw")
        override val blockSize = 512
        override val blockCount = f.length() / 512
        override fun readBlocks(startBlock: Long, blockCount: Int) =
            ByteArray(blockCount * 512).also { raf.seek(startBlock * 512); raf.readFully(it) }
        override fun writeBlocks(startBlock: Long, data: ByteArray) { raf.seek(startBlock * 512); raf.write(data) }
        override fun close() = raf.close()
    }

    private var img: File? = null
    @After fun tearDown() { img?.delete() }

    private fun volume(): File {
        assumeTrue("mkfs.vfat not available", File("/usr/sbin/mkfs.vfat").exists() || File("/sbin/mkfs.vfat").exists())
        val f = File.createTempFile("fatrec", ".img").also { img = it }
        RandomAccessFile(f, "rw").use { it.setLength(64L shl 20) }
        val p = ProcessBuilder("mkfs.vfat", "-F", "32", "-n", "RECOVER", f.path).redirectErrorStream(true).start()
        p.inputStream.readBytes(); assumeTrue(p.waitFor() == 0)
        return f
    }

    private fun data(n: Int, seed: Long) = ByteArray(n).also { Random(seed).nextBytes(it) }

    /**
     * Mounts [f] with the Linux kernel's vfat, as Windows, Android and Linux delete: the
     * entry is marked 0xE5 and its clusters freed. (libaums, the app's own FAT driver,
     * rewrites the directory without the entry, which leaves nothing to find.)
     */
    private fun <T> kernelMounted(f: File, body: (File) -> T): T {
        assumeTrue("passwordless mount not available", ProcessBuilder("sudo", "-n", "/usr/bin/mount", "--version").start().waitFor() == 0)
        val dir = java.nio.file.Files.createTempDirectory("fatrec-mnt").toFile()
        val uid = ProcessBuilder("id", "-u").start().inputStream.bufferedReader().readText().trim()
        fun sh(vararg c: String) = ProcessBuilder(*c).redirectErrorStream(true).start().let { p -> val o = p.inputStream.bufferedReader().readText(); check(p.waitFor() == 0) { "${c.joinToString(" ")}: $o" } }
        sh("sudo", "-n", "/usr/bin/mount", "-o", "loop,uid=$uid,gid=$uid,flush", f.path, dir.path)
        try { return body(dir) } finally { sh("sync"); sh("sudo", "-n", "/usr/bin/umount", dir.path); dir.delete() }
    }

    @Test
    fun `deleted files are listed and read back, live and moved files are not listed`() {
        val f = volume()
        val photo = data(300_000, 1)
        val doc = data(5_000, 2)
        val inFolder = data(70_000, 3)
        kernelMounted(f) { root ->
            root.resolve("keep.txt").writeBytes(data(1000, 9))
            root.resolve("Holiday photo 2026.jpg").apply { writeBytes(photo) }
            root.resolve("notes.doc").writeBytes(doc)
            val dir = root.resolve("Camera").apply { mkdir() }
            dir.resolve("IMG_0001 long name.jpg").writeBytes(inFolder)
            root.resolve("moved.bin").writeBytes(data(4000, 4))
        }
        kernelMounted(f) { root ->
            check(root.resolve("Holiday photo 2026.jpg").delete() && root.resolve("notes.doc").delete())
            check(root.resolve("moved.bin").renameTo(root.resolve("Camera/moved.bin")))
            check(root.resolve("Camera/IMG_0001 long name.jpg").delete())
        }
        val r = FatRecovery(Img(f))
        val found = r.scan()
        val byName = found.associateBy { it.path }
        assertEquals("only the three deleted files: ${found.map { it.path }}",
            // Linux gives "notes.doc" a long name (it is lower case), and the long name's
            // checksum gives back the first letter deletion overwrote.
            setOf("Holiday photo 2026.jpg", "notes.doc", "Camera/IMG_0001 long name.jpg"), byName.keys)
        assertFalse("a live file is never listed", byName.keys.any { "keep" in it })
        assertFalse("a moved file's old entry is not a deleted file", byName.keys.any { "moved" in it })
        for ((name, expected) in listOf("Holiday photo 2026.jpg" to photo, "notes.doc" to doc, "Camera/IMG_0001 long name.jpg" to inFolder)) {
            val d = byName.getValue(name)
            assertEquals(name, DeletedFile.State.RECOVERABLE, d.state)
            assertEquals(expected.size.toLong(), d.size)
            assertArrayEquals(name, expected, ByteArrayOutputStream().also { r.read(d, it) }.toByteArray())
        }
    }

    @Test
    fun `a deleted file whose space a new file took is reported overwritten`() {
        val f = volume()
        // The new file goes in a folder made beforehand, so it does not take the old one's
        // directory slot (which would erase the entry), and it fills the volume, so it has
        // to take the old one's clusters (the kernel otherwise allocates past them).
        kernelMounted(f) { root -> root.resolve("old.bin").writeBytes(data(200_000, 5)); root.resolve("sub").mkdir() }
        kernelMounted(f) { root -> check(root.resolve("old.bin").delete()) }
        kernelMounted<Unit>(f) { root ->
            val fill = root.resolve("sub/new.bin")
            java.io.FileOutputStream(fill).use { o -> val b = ByteArray(1 shl 16); runCatching { while (true) o.write(b) } }
        }
        val found = FatRecovery(Img(f)).scan()
        assertEquals(1, found.size)
        assertEquals("old.bin", found.single().path)
        assertTrue(found.single().state != DeletedFile.State.RECOVERABLE)
    }
}

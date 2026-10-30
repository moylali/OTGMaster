package app.fayaz.otgmaster.recovery

import app.fayaz.otgmaster.block.RawBlockDevice
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Random

/** Deleted-file listing on exFAT, deleting through the Linux kernel's exfat driver. */
class ExFatRecoveryTest {
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

    private fun data(n: Int, seed: Long) = ByteArray(n).also { Random(seed).nextBytes(it) }

    private fun volume(): File {
        assumeTrue("mkfs.exfat not available", ProcessBuilder("sh", "-c", "command -v mkfs.exfat").start().waitFor() == 0)
        val f = File.createTempFile("exfatrec", ".img").also { img = it }
        RandomAccessFile(f, "rw").use { it.setLength(64L shl 20) }
        val p = ProcessBuilder("mkfs.exfat", "-L", "RECOVER", f.path).redirectErrorStream(true).start()
        p.inputStream.readBytes(); assumeTrue(p.waitFor() == 0)
        return f
    }

    private fun <T> kernelMounted(f: File, body: (File) -> T): T {
        assumeTrue("passwordless mount not available", ProcessBuilder("sudo", "-n", "/usr/bin/mount", "--version").start().waitFor() == 0)
        val dir = java.nio.file.Files.createTempDirectory("exfatrec-mnt").toFile()
        val uid = ProcessBuilder("id", "-u").start().inputStream.bufferedReader().readText().trim()
        fun sh(vararg c: String) = ProcessBuilder(*c).redirectErrorStream(true).start().let { p -> val o = p.inputStream.bufferedReader().readText(); check(p.waitFor() == 0) { "${c.joinToString(" ")}: $o" } }
        sh("sudo", "-n", "/usr/bin/mount", "-t", "exfat", "-o", "loop,uid=$uid,gid=$uid", f.path, dir.path)
        try { return body(dir) } finally { sh("sync"); sh("sudo", "-n", "/usr/bin/umount", dir.path); dir.delete() }
    }

    @Test
    fun `deleted files are listed and read back, live and moved files are not listed`() {
        val f = volume()
        val photo = data(300_000, 1)
        val inFolder = data(70_000, 3)
        val inDeletedFolder = data(9_000, 7)
        kernelMounted(f) { root ->
            root.resolve("keep.txt").writeBytes(data(1000, 9))
            root.resolve("Holiday photo 2026.jpg").writeBytes(photo)
            root.resolve("Camera").mkdir()
            root.resolve("Camera/IMG_0001 long name.jpg").writeBytes(inFolder)
            root.resolve("moved.bin").writeBytes(data(4000, 4))
            root.resolve("Old").mkdir()
            root.resolve("Old/inside.txt").writeBytes(inDeletedFolder)
        }
        kernelMounted(f) { root ->
            check(root.resolve("Holiday photo 2026.jpg").delete())
            check(root.resolve("moved.bin").renameTo(root.resolve("Camera/moved.bin")))
            check(root.resolve("Camera/IMG_0001 long name.jpg").delete())
            check(root.resolve("Old/inside.txt").delete() && root.resolve("Old").delete())
        }
        val r = ExFatRecovery(Img(f))
        val found = r.scan().associateBy { it.path }
        assertEquals(setOf("Holiday photo 2026.jpg", "Camera/IMG_0001 long name.jpg", "Old/inside.txt"), found.keys)
        for ((name, expected) in listOf("Holiday photo 2026.jpg" to photo, "Camera/IMG_0001 long name.jpg" to inFolder, "Old/inside.txt" to inDeletedFolder)) {
            val d = found.getValue(name)
            assertEquals(name, DeletedFile.State.RECOVERABLE, d.state)
            assertArrayEquals(name, expected, ByteArrayOutputStream().also { r.read(d, it) }.toByteArray())
        }
    }

    @Test
    fun `a deleted file whose space a new file took is not reported recoverable`() {
        val f = volume()
        kernelMounted(f) { root -> root.resolve("old.bin").writeBytes(data(200_000, 5)); root.resolve("sub").mkdir() }
        kernelMounted(f) { root -> check(root.resolve("old.bin").delete()) }
        kernelMounted<Unit>(f) { root ->
            java.io.FileOutputStream(root.resolve("sub/new.bin")).use { o -> val b = ByteArray(1 shl 16); runCatching { while (true) o.write(b) } }
        }
        val found = ExFatRecovery(Img(f)).scan()
        assertEquals(listOf("old.bin"), found.map { it.path })
        assertTrue(found.single().state != DeletedFile.State.RECOVERABLE)
    }
}

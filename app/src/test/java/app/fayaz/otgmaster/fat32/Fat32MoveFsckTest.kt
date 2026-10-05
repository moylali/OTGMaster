package app.fayaz.otgmaster.fat32

import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.block.RawBlockDeviceAdapter
import me.jahnen.libaums.core.driver.ByteBlockDevice
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystem
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Moves, renames and empty files must leave a FAT32 volume that fsck.fat accepts.
 *
 * Found by the device-matrix `bigwrite` tree on all four FAT32 partitions (OnePlus 7,
 * run 7): every file read back correctly, on the phone and on the host, while
 * fsck.fat reported "Duplicate directory entry" and "File size is 0 bytes, cluster
 * chain length is > 0 bytes". Contents were fine; the structure was not.
 *
 * - A move kept the file's 8.3 short name, which was unique only in the directory it
 *   came from. libaums generates short names like `F30000~0.BIN`, so `f32.bin` and
 *   `f33.bin` created in different directories get the same one, and moving either
 *   next to the other duplicates it.
 * - `createFile` gave every new file a cluster. An empty file must have none.
 * - A moved directory's `..` still named its old parent.
 */
class Fat32MoveFsckTest {

    private class FileBlockDevice(private val file: File) : RawBlockDevice {
        private val raf = RandomAccessFile(file, "rw")
        override val blockSize = 512
        override val blockCount get() = file.length() / blockSize
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            val out = ByteArray(blockCount * blockSize)
            raf.seek(startBlock * blockSize); raf.readFully(out); return out
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) {
            raf.seek(startBlock * blockSize); raf.write(data)
        }
        override fun close() = raf.close()
    }

    private var img: File? = null

    @After fun tearDown() { img?.delete() }

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun freshImage(): File {
        assumeTrue("mkfs.vfat not available",
            File("/usr/sbin/mkfs.vfat").exists() || File("/sbin/mkfs.vfat").exists())
        val f = File.createTempFile("fat32move", ".img").also { img = it }
        RandomAccessFile(f, "rw").use { it.setLength(64L shl 20) }
        val (rc, out) = run("mkfs.vfat", "-F", "32", "-s", "1", "-n", "MOVE", f.absolutePath)
        assumeTrue("mkfs.vfat failed: $out", rc == 0)
        return f
    }

    private fun <T> withFs(f: File, block: (UsbFile) -> T): T {
        val dev = FileBlockDevice(f)
        try {
            return block(Fat32FileSystem.read(ByteBlockDevice(RawBlockDeviceAdapter(dev)))!!.rootDirectory)
        } finally { dev.close() }
    }

    private fun assertFsckClean(f: File) {
        val (rc, out) = run("fsck.vfat", "-n", f.absolutePath)
        assertEquals("fsck.vfat:\n$out", 0, rc)
    }

    private fun UsbFile.put(name: String, bytes: ByteArray) =
        createFile(name).apply { if (bytes.isNotEmpty()) write(0, ByteBuffer.wrap(bytes)); close() }

    private fun UsbFile.readAll(path: String): ByteArray {
        val file = search(path)!!
        val buf = ByteBuffer.allocate(file.length.toInt())
        file.read(0, buf)
        return buf.array()
    }

    @Test
    fun movingAFileGivesItAShortNameUniqueInItsNewDirectory() {
        val f = freshImage()
        val a = Random(1).nextBytes(3000)
        val b = Random(2).nextBytes(5000)
        withFs(f) { root ->
            val da = root.createDirectory("da")
            val db = root.createDirectory("db")
            da.put("f32.bin", a)
            db.put("f33.bin", b)
            db.search("f33.bin")!!.moveTo(da)
        }
        assertFsckClean(f)
        withFs(f) { root ->
            assertArrayEquals(a, root.readAll("da/f32.bin"))
            assertArrayEquals(b, root.readAll("da/f33.bin"))
        }
    }

    @Test
    fun anEmptyFileOwnsNoCluster() {
        val f = freshImage()
        withFs(f) { root ->
            root.put("empty.bin", ByteArray(0))
            root.createDirectory("d").put("empty2.bin", ByteArray(0))
        }
        assertFsckClean(f)
        val data = Random(3).nextBytes(10_000)
        withFs(f) { root ->
            assertEquals(0L, root.search("empty.bin")!!.length)
            // An empty file still grows normally.
            root.search("d/empty2.bin")!!.apply { write(0, ByteBuffer.wrap(data)); close() }
        }
        assertFsckClean(f)
        withFs(f) { root -> assertArrayEquals(data, root.readAll("d/empty2.bin")) }
    }

    @Test
    fun aMovedDirectoryPointsBackAtItsNewParent() {
        val f = freshImage()
        val a = Random(4).nextBytes(7000)
        withFs(f) { root ->
            val da = root.createDirectory("da")
            val db = root.createDirectory("db")
            da.createDirectory("sub").put("x.bin", a)
            da.search("sub")!!.moveTo(db)
            da.createDirectory("top").moveTo(root)   // the root's `..` is cluster 0
        }
        assertFsckClean(f)
        withFs(f) { root -> assertArrayEquals(a, root.readAll("db/sub/x.bin")) }
    }
}

package app.fayaz.otgmaster.exfat

import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.fs.UsbFile
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Deleting a folder deletes what it holds, as it does on FAT32, NTFS and ext4. libexfat's
 * rmdir refuses a folder with anything in it, so the Files app got "-39" for any folder that
 * was not already empty.
 *
 * Judged by `fsck.exfat` and by the free space, which is checked after a fresh mount:
 * fsck.exfat does not report clusters marked used with no file owning them, which is what a
 * delete leaves if it loses track of them.
 */
class ExFatDeleteTreeTest {

    private class Device(private val file: File, private val allow: Int = Int.MAX_VALUE) : RawBlockDevice {
        private val raf = RandomAccessFile(file, "rw")
        var writes = 0
            private set
        override val blockSize = 512
        override val blockCount get() = file.length() / blockSize
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            val out = ByteArray(blockCount * blockSize)
            raf.seek(startBlock * blockSize); raf.readFully(out); return out
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) {
            if (writes >= allow) throw IOException("device gone after $allow writes")
            writes++
            raf.seek(startBlock * blockSize); raf.write(data)
        }
        override fun close() = raf.close()
    }

    companion object {
        private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "scripts/build_host_native.sh").exists() }

        private fun run(vararg cmd: String): Pair<Int, String> {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            return p.waitFor() to out
        }

        private fun toolMissing(tool: String) = run("sh", "-c", "command -v $tool").first != 0

        private var hostLib: File? = null

        @BeforeClass
        @JvmStatic
        fun buildHostLibrary() {
            if (toolMissing("gcc") || toolMissing("g++")) return
            val lib = File(repo, "app/build/host-native/libotg-host.so")
            val (rc, out) = run(File(repo, "scripts/build_host_native.sh").path, lib.path)
            if (rc != 0) throw AssertionError("host build of the native code failed:\n$out")
            System.setProperty("otg.native.hostlib", lib.absolutePath)
            hostLib = lib
        }
    }

    private lateinit var img: File
    private val keep = Random(1).nextBytes(300_000)
    private val big = Random(2).nextBytes(3_000_000)

    @Before
    fun setUp() {
        assumeTrue("no host C toolchain", hostLib != null)
        for (t in listOf("mkfs.exfat", "fsck.exfat")) assumeTrue("$t not available", !toolMissing(t))
        img = File.createTempFile("exfattree", ".img")
        RandomAccessFile(img, "rw").use { it.setLength(128L shl 20) }
        val (rc, out) = run("mkfs.exfat", "-c", "4K", "-L", "OTGTEST", img.path)
        assertEquals("mkfs.exfat failed: $out", 0, rc)
    }

    @After
    fun tearDown() {
        if (::img.isInitialized) img.delete()
    }

    private fun <R> withFs(block: (ExFatFileSystem) -> R): R {
        val dev = Device(img)
        val ptr = ExFatNative.mount(dev, false)
        assertNotEquals("libexfat refused the image", 0L, ptr)
        val fs = ExFatFileSystem(dev, ptr)
        try { return block(fs) } finally { fs.unmount(); dev.close() }
    }

    private fun assertFsckClean(stage: String) {
        val (rc, out) = run("fsck.exfat", "-n", img.path)
        assertEquals("fsck.exfat after $stage:\n$out", 0, rc)
    }

    private fun UsbFile.put(name: String, data: ByteArray) = createFile(name).apply {
        write(0, ByteBuffer.wrap(data)); close()
    }

    private fun UsbFile.bytes(): ByteArray {
        val out = ByteArray(length.toInt()); read(0, ByteBuffer.wrap(out)); close(); return out
    }

    private fun tree(root: UsbFile) {
        val tree = root.createDirectory("tree")
        tree.put("a.txt", "a".toByteArray())
        tree.put("empty.bin", ByteArray(0))
        tree.put("big.bin", big)
        tree.createDirectory("emptydir")
        val deep = tree.createDirectory("sub").createDirectory("deep").createDirectory("deeper")
        deep.put("file.bin", Random(3).nextBytes(50_000))
        // Past the first cluster of the folder's own entries, so the folder has to shrink.
        val many = tree.createDirectory("many")
        repeat(300) { many.put("a_file_with_a_longer_name_$it.txt", "file $it".toByteArray()) }
    }

    @Test
    fun aFolderGoesWithEverythingInItAndItsSpaceComesBack() {
        val freeBefore = withFs { fs ->
            fs.rootDirectory.put("keep.bin", keep)
            fs.freeSpace
        }
        withFs { fs -> tree(fs.rootDirectory) }
        assertFsckClean("making the tree")
        withFs { fs -> assertTrue(fs.freeSpace < freeBefore - big.size) }

        withFs { fs -> fs.rootDirectory.search("tree")!!.delete() }

        assertFsckClean("deleting the tree")
        withFs { fs ->
            assertNull(fs.rootDirectory.search("tree"))
            assertArrayEquals(keep, fs.rootDirectory.search("keep.bin")!!.bytes())
            assertEquals("free space after a fresh mount", freeBefore, fs.freeSpace)
        }
    }

    @Test
    fun theSpaceIsBackBeforeTheUnmount() {
        withFs { fs ->
            val freeBefore = fs.freeSpace
            tree(fs.rootDirectory)
            fs.rootDirectory.search("tree")!!.delete()
            assertEquals("free space straight after the delete", freeBefore, fs.freeSpace)
            // The handles of the listings are still open, so writing proves those clusters are usable.
            fs.rootDirectory.put("after.bin", big)
            assertArrayEquals(big, fs.rootDirectory.search("after.bin")!!.bytes())
        }
        assertFsckClean("writing over what the tree held")
    }

    @Test
    fun aFileHeldOpenInTheFolderDoesNotStopIt() {
        withFs { fs ->
            tree(fs.rootDirectory)
            val held = fs.rootDirectory.search("tree/big.bin")!!
            val first = ByteArray(16)
            held.read(0, ByteBuffer.wrap(first))
            fs.rootDirectory.search("tree")!!.delete()
            assertNull(fs.rootDirectory.search("tree"))
            held.close()
        }
        assertFsckClean("deleting a folder with a file held open")
    }

    @Test
    fun theRootIsRefusedAndKeepsEverything() {
        withFs { fs ->
            fs.rootDirectory.put("keep.bin", keep)
            assertThrows(IOException::class.java) { fs.rootDirectory.delete() }
            assertNotNull(fs.rootDirectory.search("keep.bin"))
        }
        assertFsckClean("refusing the root")
        withFs { fs -> assertArrayEquals(keep, fs.rootDirectory.search("keep.bin")!!.bytes()) }
    }

    /** A read-only volume writes nothing, and that includes the contents it would go through first. */
    @Test
    fun aReadOnlyVolumeRefusesAndKeepsEverything() {
        withFs { fs -> tree(fs.rootDirectory) }
        val before = img.readBytes()
        val dev = Device(img)
        val ptr = ExFatNative.mount(dev, true)
        assertNotEquals(0L, ptr)
        val fs = ExFatFileSystem(dev, ptr, readOnly = true)
        try {
            assertThrows(IOException::class.java) { fs.rootDirectory.search("tree")!!.delete() }
            assertNotNull(fs.rootDirectory.search("tree/many"))
        } finally {
            fs.unmount(); dev.close()
        }
        assertArrayEquals("a refused delete wrote to the volume", before, img.readBytes())
    }

    /**
     * Cut off part way through, the volume must still mount and hold keep.bin, and fsck must
     * find nothing wrong with what is left: a folder emptied but not yet gone is a valid state.
     */
    @Test
    fun anInterruptedDeleteLeavesAValidVolume() {
        withFs { fs ->
            fs.rootDirectory.put("keep.bin", keep)
            tree(fs.rootDirectory)
        }
        val base = File.createTempFile("exfattree-base", ".img")
        try {
            img.copyTo(base, overwrite = true)
            val total = Device(img).let { dev ->
                val fs = ExFatFileSystem(dev, ExFatNative.mount(dev, false))
                fs.rootDirectory.search("tree")!!.delete()
                val n = dev.writes
                fs.unmount(); dev.close()
                n
            }
            println("deleting 304 files in 6 folders: $total writes")
            for (n in (1..20).map { it * total / 21 }) {
                base.copyTo(img, overwrite = true)
                val dev = Device(img, allow = n)
                val fs = ExFatFileSystem(dev, ExFatNative.mount(dev, false))
                runCatching { fs.rootDirectory.search("tree")!!.delete() }
                runCatching { fs.unmount() }
                dev.close()
                val (rc, out) = run("fsck.exfat", "-n", img.path)
                val bad = out.lines().filter { l ->
                    l.contains("ERROR", ignoreCase = true) && !l.contains("dirty", ignoreCase = true)
                }
                assertTrue("cut off after $n writes (rc=$rc):\n$out", bad.isEmpty())
                withFs { f -> assertArrayEquals("cut off after $n writes", keep, f.rootDirectory.search("keep.bin")!!.bytes()) }
            }
        } finally {
            base.delete()
        }
    }
}

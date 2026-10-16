package app.fayaz.otgmaster.fat32

import app.fayaz.otgmaster.block.FileBlockDevice
import app.fayaz.otgmaster.block.RawBlockDeviceAdapter
import me.jahnen.libaums.core.driver.ByteBlockDevice
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystem
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * A garbage collection must not leave two in-memory copies of one directory.
 *
 * Each FatDirectory holds its entries as read from disk, and writes the whole list back
 * on every change. Handles are found again through `fs.fileCache`, a WeakHashMap keyed
 * by path strings nothing else holds, so any GC empties it while the directory objects
 * are still referenced, from a file handle's `parent` for one. The next lookup then
 * reads a second copy of the directory. A change made through one copy is written over
 * by the next change made through the other.
 *
 * On the OnePlus 7 (D1FAT32, 2026-10-16) bigwrite's tree check lost `d2/e2/f71.bin`
 * that way: moved into one copy of `d2/e2`, then a delete through an older handle wrote
 * the other copy, without it. fsck.fat found its 355 clusters allocated to nothing.
 *
 * The test forces a real collection between taking the old handle and the lookup.
 */
class Fat32HandleAliasTest {

    private lateinit var img: File

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    @Before
    fun setUp() {
        for (t in listOf("mkfs.vfat", "fsck.vfat"))
            assumeTrue("$t not available", run("sh", "-c", "command -v $t").first == 0)
        img = File.createTempFile("fat32alias", ".img")
        RandomAccessFile(img, "rw").use { it.setLength(64L shl 20) }
        val (rc, out) = run("mkfs.vfat", "-F", "32", "-s", "8", "-n", "ALIAS", img.path)
        assertTrue("mkfs.vfat failed: $out", rc == 0)
    }

    @After
    fun tearDown() {
        if (::img.isInitialized) img.delete()
    }

    private fun <R> withFs(block: (Fat32FileSystem) -> R): R {
        val dev = FileBlockDevice(img)
        try {
            return block(Fat32FileSystem.read(ByteBlockDevice(RawBlockDeviceAdapter(dev)))!!)
        } finally { dev.close() }
    }

    /**
     * A full collection, waited for: until an object nothing references is gone. Whatever
     * libaums keeps only weakly is gone too, and whatever is still referenced survives.
     */
    private fun collectGarbage() {
        val sentinel = java.lang.ref.WeakReference(Any())
        val deadline = System.currentTimeMillis() + 10_000
        while (sentinel.get() != null) {
            check(System.currentTimeMillis() < deadline) { "the JVM did not collect in 10 s" }
            System.gc(); Thread.sleep(20)
        }
        System.gc(); Thread.sleep(20)
    }

    private fun assertFsckClean(stage: String) {
        val (rc, out) = run("fsck.vfat", "-n", img.path)
        assertTrue("fsck.vfat reported problems after $stage (rc=$rc):\n$out", rc == 0)
    }

    @Test
    fun aMoveIntoADirectorySurvivesADeleteThroughAnOlderHandle() {
        withFs { fs ->
            val e = fs.rootDirectory.createDirectory("d").createDirectory("e")
            for (n in listOf("old.bin", "keep.bin")) e.createFile(n).run {
                write(0, ByteBuffer.wrap(ByteArray(10_000) { 7 })); close()
            }
            fs.rootDirectory.createDirectory("src").createFile("moved.bin").run {
                write(0, ByteBuffer.wrap(ByteArray(20_000) { 9 })); close()
            }
        }
        withFs { fs ->
            // A handle taken now holds this copy of d/e as its parent.
            val old = fs.rootDirectory.search("d/e/old.bin")!!
            collectGarbage()
            // The next lookup of d/e reads it again; the move goes into that copy.
            fs.rootDirectory.search("src/moved.bin")!!.moveTo(fs.rootDirectory.search("d/e")!!)
            // The delete goes through the first copy, which has never seen moved.bin.
            old.delete()
        }
        withFs { fs ->
            assertNotNull("d/e/moved.bin was lost", fs.rootDirectory.search("d/e/moved.bin"))
            assertNotNull("d/e/keep.bin was lost", fs.rootDirectory.search("d/e/keep.bin"))
        }
        assertFsckClean("a move and a delete through two copies of one directory")
    }

    /**
     * A file kept open while other files are created: closing it rewrites its parent,
     * which must still list the files created through a later lookup of the directory.
     */
    @Test
    fun aFileCreatedThroughAFreshLookupSurvivesAWriteThroughAnOlderHandle() {
        withFs { fs -> fs.rootDirectory.createDirectory("d").createFile("open.bin") }
        withFs { fs ->
            val open = fs.rootDirectory.search("d/open.bin")!!
            collectGarbage()
            for (i in 0 until 5) fs.rootDirectory.search("d")!!.createFile("new$i.bin")
            open.write(0, ByteBuffer.wrap(ByteArray(50_000) { 3 }))
            open.close()
        }
        withFs { fs ->
            for (i in 0 until 5) assertNotNull("d/new$i.bin was lost", fs.rootDirectory.search("d/new$i.bin"))
            assertNotNull(fs.rootDirectory.search("d/open.bin"))
        }
        assertFsckClean("creates through a fresh lookup, then a write through an older handle")
    }

    /**
     * A directory renamed through one lookup, then written through an older handle of it.
     * This passes on the old code too (a rename rewrites the parent, which was never
     * duplicated); it covers V20 keeping the handle map keyed through a rename.
     */
    @Test
    fun aRenamedDirectoryKeepsFilesCreatedThroughAnOlderHandle() {
        withFs { fs -> fs.rootDirectory.createDirectory("a").createFile("first.bin") }
        withFs { fs ->
            val older = fs.rootDirectory.search("a")!!
            collectGarbage()
            fs.rootDirectory.search("a")!!.name = "b"
            older.createFile("second.bin")
        }
        withFs { fs ->
            assertNotNull(fs.rootDirectory.search("b/first.bin"))
            assertNotNull("b/second.bin was lost", fs.rootDirectory.search("b/second.bin"))
        }
        assertFsckClean("a rename and a create through two lookups of one directory")
    }
}

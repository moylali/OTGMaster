package app.fayaz.otgmaster.backup.restic

import app.fayaz.otgmaster.backup.LocalFileTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A restore's space is counted before anything is written, in the units the target
 * volume allocates. The 0.5.3 matrix filled a 2 GiB exFAT partition with 32 KiB
 * clusters: about 20,000 small files took far more than their byte count, and the
 * write that did not fit failed with a bare `-28` partway through.
 */
class RestoreSpaceTest {
    companion object {
        @JvmStatic
        @org.junit.BeforeClass
        fun loadHostLibrary() = ZstdNativeTest.loadHostLibrary()
    }

    private class Setup(val tmp: File, val repo: ResticRepository, val snap: ResticRepository.Snapshot, val dest: File)

    private fun setUp(files: Int, size: Int): Setup {
        val tmp = Files.createTempDirectory("restorespace").toFile()
        val src = tmp.resolve("src").also { it.mkdirs() }
        for (i in 0 until files) src.resolve("f$i.bin").writeBytes(ByteArray(size) { (it + i).toByte() })
        val repoDir = tmp.resolve("repo").also { it.mkdirs() }
        val repo = ResticWriter.init(LocalBackend(repoDir), "pw".toByteArray(), "phone", "otg")
        val id = ResticWriter(repo, "phone", "otg", "test").backup(FileSource(src), "/src")
        val reopened = ResticRepository.open(LocalBackend(repoDir), "pw".toByteArray())!!
        val dest = tmp.resolve("dest").also { it.mkdirs() }
        return Setup(tmp, reopened, reopened.snapshots().first { it.id == id }, dest)
    }

    @Test
    fun `small files are counted in whole clusters, not bytes`() {
        val s = setUp(files = 200, size = 100)
        val r = ResticRestore(s.repo)
        val need = r.spaceNeeded(r.find(s.snap, "src")!!, LocalFileTree(s.dest), "src", 32 * 1024)
        // 200 files of 100 bytes are 20,000 bytes, but each takes a 32 KiB cluster,
        // and the new folder one more.
        assertEquals(201L * 32 * 1024, need)
        s.tmp.deleteRecursively()
    }

    @Test
    fun `files already there add only the difference, plus the largest one written beside its old copy`() {
        val s = setUp(files = 3, size = 10_000)
        val r = ResticRestore(s.repo)
        val dest = LocalFileTree(s.dest)
        r.restore(r.find(s.snap, "src")!!, dest, "src")
        val need = r.spaceNeeded(r.find(s.snap, "src")!!, dest, "src", 4096)
        // Same sizes: no growth. The replaced file is written in full before its old copy goes.
        assertEquals(12_288L, need)
        s.tmp.deleteRecursively()
    }

    @Test
    fun `an empty file takes no cluster`() {
        val s = setUp(files = 2, size = 0)
        val r = ResticRestore(s.repo)
        val need = r.spaceNeeded(r.find(s.snap, "src")!!, LocalFileTree(s.dest), "src", 4096)
        assertTrue("got $need", need == 4096L)
        s.tmp.deleteRecursively()
    }
}

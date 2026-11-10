package app.fayaz.otgmaster.backup.restic

import app.fayaz.otgmaster.backup.LocalFileTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * A restore that fails or is cancelled must leave what was at the destination. Review
 * finding 10 and the restore-cancel issue, 2026-11-09: the destination file was
 * truncated before the first blob was read, so a damaged or missing blob, or a backup
 * drive pulled out mid-file, left the user's good file empty or half written; a folder
 * in the way of a file was deleted outright; and Cancel was looked at once, before the
 * restore began.
 */
class RestoreSafetyTest {
    companion object {
        @JvmStatic
        @org.junit.BeforeClass
        fun loadHostLibrary() = ZstdNativeTest.loadHostLibrary()
    }

    private class Setup(val tmp: File, val repo: ResticRepository, val snap: ResticRepository.Snapshot, val dest: File)

    private fun setUp(): Setup {
        val tmp = Files.createTempDirectory("restoresafety").toFile()
        val src = tmp.resolve("src").also { it.mkdirs() }
        src.resolve("doc.txt").writeText("from the backup ".repeat(1000))
        src.resolve("second.txt").writeText("second file")
        val repoDir = tmp.resolve("repo").also { it.mkdirs() }
        val repo = ResticWriter.init(LocalBackend(repoDir), "pw".toByteArray(), "phone", "otg")
        val id = ResticWriter(repo, "phone", "otg", "test").backup(FileSource(src), "/src")
        val reopened = ResticRepository.open(LocalBackend(repoDir), "pw".toByteArray())!!
        val dest = tmp.resolve("dest").also { it.mkdirs() }
        return Setup(tmp, reopened, reopened.snapshots().first { it.id == id }, dest)
    }

    /** Damages every data pack, as a bad sector or a half-copied repository would. */
    private fun damagePacks(s: Setup) {
        s.tmp.resolve("repo/data").walkTopDown().filter { it.isFile }.forEach { f ->
            val b = f.readBytes(); b[b.size / 2] = (b[b.size / 2].toInt() xor 0xFF).toByte(); f.writeBytes(b)
        }
    }

    @Test
    fun `a restore that hits damaged data leaves the existing file as it was`() {
        val s = setUp()
        s.dest.resolve("doc.txt").writeText("the user's current, good version")
        damagePacks(s)
        val r = ResticRestore(s.repo)
        try {
            r.restore(r.find(s.snap, "src/doc.txt")!!, LocalFileTree(s.dest), "doc.txt")
            fail("a damaged blob was restored")
        } catch (e: IOException) { /* DamagedException or an authentication failure */ }
        assertEquals("the failed restore changed the existing file",
            "the user's current, good version", s.dest.resolve("doc.txt").readText())
        assertEquals("a temporary file was left behind", listOf("doc.txt"), s.dest.list()!!.sorted())
        s.tmp.deleteRecursively()
    }

    @Test
    fun `a folder in the way of a file is not deleted`() {
        val s = setUp()
        s.dest.resolve("doc.txt").mkdirs()
        s.dest.resolve("doc.txt/precious.jpg").writeText("not in any backup")
        val r = ResticRestore(s.repo)
        try {
            r.restore(r.find(s.snap, "src/doc.txt")!!, LocalFileTree(s.dest), "doc.txt")
            fail("the restore replaced a folder with a file")
        } catch (e: IOException) { /* expected */ }
        assertEquals("not in any backup", s.dest.resolve("doc.txt/precious.jpg").readText())
        s.tmp.deleteRecursively()
    }

    @Test
    fun `cancel stops a restore part way, between files`() {
        val s = setUp()
        val r = ResticRestore(s.repo)
        var files = 0
        r.isCancelled = { files >= 1 }
        r.onFileDone = { files++ }
        try {
            r.restore(r.find(s.snap, "src")!!, LocalFileTree(s.dest), "src")
            fail("the restore ignored Cancel")
        } catch (e: CancelledException) { /* expected */ }
        val written = s.dest.resolve("src").list()!!.filter { !it.startsWith(".") }
        assertEquals("files restored after Cancel: $written", 1, written.size)
        s.tmp.deleteRecursively()
    }

    @Test
    fun `a restore over an existing file replaces it completely`() {
        val s = setUp()
        s.dest.resolve("doc.txt").writeText("older")
        val r = ResticRestore(s.repo)
        r.restore(r.find(s.snap, "src/doc.txt")!!, LocalFileTree(s.dest), "doc.txt")
        assertEquals("from the backup ".repeat(1000), s.dest.resolve("doc.txt").readText())
        assertTrue(s.dest.list()!!.toList() == listOf("doc.txt"))
        s.tmp.deleteRecursively()
    }
}

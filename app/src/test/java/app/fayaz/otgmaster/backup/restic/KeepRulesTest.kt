package app.fayaz.otgmaster.backup.restic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/**
 * Keep rules must never delete the last good copy. Review findings 4 and 5, 2026-11-09:
 * a backup that could not read a file still counted as the newest snapshot, so "keep
 * last 1" deleted the previous one, the only copy of that file; and a restore's safety
 * snapshot let the keep rules delete the snapshot being restored.
 */
class KeepRulesTest {
    companion object {
        @JvmStatic
        @org.junit.BeforeClass
        fun loadHostLibrary() = ZstdNativeTest.loadHostLibrary()
    }

    /** [FileSource], except that the file named [unreadable] fails to open, as a file Android will not hand over. */
    private class FlakySource(private val f: File, private val unreadable: String?) : BackupSource {
        override val name: String get() = f.name
        override val isDirectory get() = f.isDirectory
        override val size get() = f.length()
        override val modifiedMillis get() = f.lastModified()
        override fun children() = f.listFiles()?.map { FlakySource(it, unreadable) }.orEmpty()
        override fun open(): InputStream =
            if (f.name == unreadable) throw IOException("permission denied") else f.inputStream()
    }

    private fun setUp(): Triple<File, File, ResticRepository> {
        val tmp = Files.createTempDirectory("keeprules").toFile()
        val src = tmp.resolve("src").also { it.mkdirs() }
        src.resolve("a.txt").writeText("alpha")
        src.resolve("only-copy.txt").writeText("the file that becomes unreadable")
        val repo = ResticWriter.init(LocalBackend(tmp.resolve("repo").also { it.mkdirs() }), "pw".toByteArray(), "phone", "otg")
        return Triple(tmp, src, repo)
    }

    private fun backup(repo: ResticRepository, src: File, unreadable: String? = null): Pair<String, List<String>> {
        val w = ResticWriter(repo, "phone", "otg", "test")
        val id = w.backup(FlakySource(src, unreadable), "/src")
        Thread.sleep(5)   // distinct snapshot times
        return id to w.errors.toList()
    }

    private fun reopen(tmp: File) = ResticRepository.open(LocalBackend(tmp.resolve("repo")), "pw".toByteArray())!!

    @Test
    fun `an incomplete backup does not let keep-last-1 delete the last complete snapshot`() {
        val (tmp, src, repo) = setUp()
        val (good, _) = backup(repo, src)
        val (partial, errors) = backup(repo, src, unreadable = "only-copy.txt")
        assertEquals("the second run should have failed to read one file", 1, errors.size)

        val m = ResticMaintenance(repo, "phone", "otg")
        val snaps = repo.snapshots()
        // What the old code did: the policy alone drops the complete snapshot.
        assertEquals("precondition: keep last 1 alone drops the good snapshot",
            listOf(good), m.toForget(snaps, KeepPolicy(last = 1)).map { it.id })

        val r = KeepRules.apply(m, snaps, KeepPolicy(last = 1))
        assertTrue("forgot ${r.forgotten.map { it.id }}", r.forgotten.isEmpty())

        val after = reopen(tmp)
        assertEquals(setOf(good, partial), after.snapshots().map { it.id }.toSet())
        // And the only copy of the file still restores, byte for byte.
        val snap = after.snapshots().first { it.id == good }
        val node = ResticRestore(after).find(snap, "src/only-copy.txt")!!
        assertArrayEquals("the file that becomes unreadable".toByteArray(),
            node.content.flatMap { after.blob(it).toList() }.toByteArray())
        tmp.deleteRecursively()
    }

    @Test
    fun `a pinned snapshot survives the keep rules`() {
        val (tmp, src, repo) = setUp()
        val (restoring, _) = backup(repo, src)
        src.resolve("a.txt").writeText("changed since")
        backup(repo, src)   // the safety snapshot, newest and complete
        val r = KeepRules.apply(ResticMaintenance(repo, "phone", "otg"), repo.snapshots(), KeepPolicy(last = 1),
            pinned = setOf(restoring))
        assertTrue(r.forgotten.isEmpty())
        assertTrue("the snapshot being restored was deleted",
            reopen(tmp).snapshots().any { it.id == restoring })
        tmp.deleteRecursively()
    }

    @Test
    fun `complete backups are still pruned as the policy says`() {
        val (tmp, src, repo) = setUp()
        val (first, _) = backup(repo, src)
        src.resolve("a.txt").writeText("second")
        val (second, _) = backup(repo, src)
        val r = KeepRules.apply(ResticMaintenance(repo, "phone", "otg"), repo.snapshots(), KeepPolicy(last = 1))
        assertEquals(listOf(first), r.forgotten.map { it.id })
        assertEquals(listOf(second), reopen(tmp).snapshots().map { it.id })
        tmp.deleteRecursively()
    }
}

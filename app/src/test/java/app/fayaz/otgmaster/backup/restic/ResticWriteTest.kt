package app.fayaz.otgmaster.backup.restic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Random

/** App -> desktop, and a repository both write to: checked by restic itself. */
class ResticWriteTest {
    companion object {
        @JvmStatic
        @org.junit.BeforeClass
        fun loadHostLibrary() = ZstdNativeTest.loadHostLibrary()
    }

    private fun restic(): String? = listOf(System.getenv("RESTIC"), System.getProperty("user.home") + "/.local/bin/restic", "/usr/bin/restic")
        .firstOrNull { it != null && File(it).canExecute() }

    private fun run(vararg cmd: String, password: String = "pw"): String {
        val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
        pb.environment()["RESTIC_PASSWORD"] = password
        val p = pb.start(); val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor() == 0) { "${cmd.joinToString(" ")} failed:\n$out" }
        return out
    }

    private fun tree(dir: File, seed: Long): Map<String, ByteArray> {
        val rnd = Random(seed)
        val files = linkedMapOf(
            "video.mp4" to ByteArray(20_000_000).also { rnd.nextBytes(it) },
            "notes.txt" to "line\n".repeat(30_000).toByteArray(),
            "empty.dat" to ByteArray(0),
            "Pictures/2026/a.jpg" to ByteArray(800_000).also { rnd.nextBytes(it) },
            "Pictures/2026/b.jpg" to ByteArray(2_500_000).also { rnd.nextBytes(it) },
            "Pictures/empty-dir-sibling.txt" to "x".toByteArray(),
            "naïve 日本/😀.txt" to "emoji name".toByteArray(),
        )
        for ((rel, data) in files) dir.resolve(rel).apply { parentFile.mkdirs(); writeBytes(data) }
        dir.resolve("EmptyFolder").mkdirs()
        return files
    }

    private fun assertSame(expected: Map<String, ByteArray>, restored: File) {
        for ((rel, data) in expected) assertArrayEquals("restored $rel", data, restored.resolve(rel).readBytes())
        assertTrue("empty folder restored", restored.resolve("EmptyFolder").isDirectory)
        val got = restored.walkTopDown().filter { it.isFile }.map { it.relativeTo(restored).path }.toSet()
        assertEquals(expected.keys, got)
    }

    @Test
    fun `restic checks every byte of a repository this code created, and restores it`() {
        val restic = restic(); assumeTrue("restic not installed", restic != null)
        val tmp = Files.createTempDirectory("restic-write").toFile()
        val src = tmp.resolve("src").also { it.mkdirs() }
        val expected = tree(src, 1)
        val repoDir = tmp.resolve("repo").also { it.mkdirs() }
        val repo = ResticWriter.init(LocalBackend(repoDir), "pw".toByteArray(), "phone", "otg")
        val id = ResticWriter(repo, "phone", "otg", "OTG Master test").backup(FileSource(src), "/storage/emulated/0/DCIM")

        val check = run(restic!!, "-r", repoDir.path, "check", "--read-data")
        assertTrue(check, check.contains("no errors were found"))
        assertTrue(run(restic, "-r", repoDir.path, "snapshots").contains(id.substring(0, 8)))
        val target = tmp.resolve("restore")
        run(restic, "-r", repoDir.path, "restore", id.substring(0, 8), "--target", target.path)
        assertSame(expected, target.resolve("storage/emulated/0/DCIM"))
        assertTrue("no lock left behind", repoDir.resolve("locks").list()!!.isEmpty())
        tmp.deleteRecursively()
    }

    @Test
    fun `a backup into a repository restic created deduplicates against it, and restic still checks clean`() {
        val restic = restic(); assumeTrue("restic not installed", restic != null)
        val tmp = Files.createTempDirectory("restic-mixed").toFile()
        val src = tmp.resolve("src").also { it.mkdirs() }
        val expected = tree(src, 2)
        val repoDir = tmp.resolve("repo")
        run(restic!!, "-r", repoDir.path, "init")
        run(restic, "-r", repoDir.path, "backup", "--compression", "auto", "--host", "laptop", src.path)

        // The phone backs up the same files, plus one new one.
        src.resolve("new-on-phone.bin").writeBytes(ByteArray(1_500_000).also { Random(3).nextBytes(it) })
        val repo = ResticRepository.open(LocalBackend(repoDir), "pw".toByteArray())!!
        val w = ResticWriter(repo, "phone", "otg", "OTG Master test")
        val id = w.backup(FileSource(src), "/sdcard/backup")
        assertTrue("existing data was not stored again: added ${w.bytesAdded} of ${w.bytesRead} bytes",
            w.bytesAdded < 2_000_000)

        val check = run(restic, "-r", repoDir.path, "check", "--read-data")
        assertTrue(check, check.contains("no errors were found"))
        val target = tmp.resolve("restore")
        run(restic, "-r", repoDir.path, "restore", id.substring(0, 8), "--target", target.path)
        assertSame(expected + ("new-on-phone.bin" to src.resolve("new-on-phone.bin").readBytes()),
            target.resolve("sdcard/backup"))
        // And the desktop's own snapshot still restores.
        val target2 = tmp.resolve("restore2")
        run(restic, "-r", repoDir.path, "restore", "latest", "--host", "laptop", "--target", target2.path)
        assertFalse(target2.resolve(src.path.trimStart('/')).resolve("new-on-phone.bin").exists())
        tmp.deleteRecursively()
    }

    @Test
    fun `the chosen chunker polynomial is irreducible, and a reducible one is told apart`() {
        val p = Polynomials.randomIrreducible(SecureRandom())
        assertEquals(53, ResticChunker.deg(p))
        assertTrue(Polynomials.irreducible(p))
        assertTrue(Polynomials.irreducible(ResticChunker.parsePolynomial("3dea92648f6e83"))) // restic's test polynomial
        // (x^2+x+1)(x^51+x+1) is reducible.
        val reducible = Polynomials.mulMod(0b111, (1L shl 51) or 0b11, Long.MAX_VALUE ushr 1)
        assertFalse(Polynomials.irreducible(reducible))
    }

    @Test
    fun `through the FileTree adapters the app uses, restic still checks clean and restores`() {
        val restic = restic(); assumeTrue("restic not installed", restic != null)
        val tmp = Files.createTempDirectory("restic-tree").toFile()
        val src = tmp.resolve("src").also { it.mkdirs() }
        val expected = tree(src, 4)
        val repoDir = tmp.resolve("repo").also { it.mkdirs() }
        val backend = FileTreeBackend(app.fayaz.otgmaster.backup.LocalFileTree(repoDir))
        val repo = ResticWriter.init(backend, "pw".toByteArray(), "phone", "otg")
        val id = ResticWriter(repo, "phone", "otg", "OTG Master test")
            .backup(FileTreeSource.root(app.fayaz.otgmaster.backup.LocalFileTree(src)), "/drive/Backups")
        assertTrue("no temporary file left", repoDir.walkTopDown().none { it.name.endsWith(FileTreeBackend.TMP) })
        val check = run(restic!!, "-r", repoDir.path, "check", "--read-data")
        assertTrue(check, check.contains("no errors were found"))
        val target = tmp.resolve("restore")
        run(restic, "-r", repoDir.path, "restore", id.substring(0, 8), "--target", target.path)
        assertSame(expected, target.resolve("drive/Backups"))
        // And the app reads its own repository back through the same adapters.
        val again = ResticRepository.open(backend, "pw".toByteArray())!!
        assertEquals(id, again.snapshots().single().id)
        tmp.deleteRecursively()
    }

    @Test
    fun `a second backup reads only what changed, and restic restores both`() {
        val restic = restic(); assumeTrue("restic not installed", restic != null)
        val tmp = Files.createTempDirectory("restic-incr").toFile()
        val src = tmp.resolve("src").also { it.mkdirs() }
        tree(src, 5)
        val repoDir = tmp.resolve("repo").also { it.mkdirs() }
        val repo = ResticWriter.init(LocalBackend(repoDir), "pw".toByteArray(), "phone", "otg")
        ResticWriter(repo, "phone", "otg", "t").backup(FileSource(src), "/p", listOf("otg-job:1"))
        // One file changes, one is added.
        src.resolve("notes.txt").writeText("changed\n"); src.resolve("notes.txt").setLastModified(1_800_000_000_000)
        src.resolve("added.bin").writeBytes(ByteArray(10_000) { 7 })
        val repo2 = ResticRepository.open(LocalBackend(repoDir), "pw".toByteArray())!!
        val parent = repo2.snapshots().single()
        val w = ResticWriter(repo2, "phone", "otg", "t")
        val id2 = w.backup(FileSource(src), "/p", listOf("otg-job:1"), parent)
        assertEquals("the 6 unchanged files skipped (7 minus notes.txt)", 6L, w.filesUnchanged)
        assertTrue("only the changed bytes read: ${w.bytesRead}", w.bytesRead < 20_000)
        val check = run(restic!!, "-r", repoDir.path, "check", "--read-data")
        assertTrue(check, check.contains("no errors were found"))
        val target = tmp.resolve("restore")
        run(restic, "-r", repoDir.path, "restore", id2.substring(0, 8), "--target", target.path)
        for (f in src.walkTopDown().filter { it.isFile }) {
            assertArrayEquals(f.path, f.readBytes(), target.resolve("p").resolve(f.relativeTo(src).path).readBytes())
        }
        tmp.deleteRecursively()
    }

    @Test
    fun `forget and prune free what only old snapshots used, and restic still checks clean`() {
        val restic = restic(); assumeTrue("restic not installed", restic != null)
        val tmp = Files.createTempDirectory("restic-prune").toFile()
        val src = tmp.resolve("src").also { it.mkdirs() }
        tree(src, 6)
        val gone = src.resolve("old-video.bin").apply { writeBytes(ByteArray(40_000_000).also { Random(9).nextBytes(it) }) }
        val repoDir = tmp.resolve("repo").also { it.mkdirs() }
        val repo = ResticWriter.init(LocalBackend(repoDir), "pw".toByteArray(), "phone", "otg")
        ResticWriter(repo, "phone", "otg", "t").backup(FileSource(src), "/p")
        gone.delete()
        val r2 = ResticRepository.open(LocalBackend(repoDir), "pw".toByteArray())!!
        val keepId = ResticWriter(r2, "phone", "otg", "t").backup(FileSource(src), "/p", parent = r2.snapshots().single())

        val r3 = ResticRepository.open(LocalBackend(repoDir), "pw".toByteArray())!!
        val m = ResticMaintenance(r3, "phone", "otg")
        val drop = m.toForget(r3.snapshots(), KeepPolicy(last = 1))
        assertEquals(1, drop.size)
        m.forget(drop.map { it.id })
        val before = repoDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val res = m.prune()
        val after = repoDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        // The 40 MB file shares its first and last pack with files still in use, and packs in
        // part use are kept whole (no repacking), so one full 16 MB pack is what goes.
        assertTrue("a pack only the forgotten snapshot used was deleted: ${res.packsDeleted}", res.packsDeleted >= 1)
        assertTrue("at least 15 MB freed: ${before - after}", before - after >= 15_000_000)

        val check = run(restic!!, "-r", repoDir.path, "check", "--read-data")
        assertTrue(check, check.contains("no errors were found"))
        val target = tmp.resolve("restore")
        run(restic, "-r", repoDir.path, "restore", keepId.substring(0, 8), "--target", target.path)
        for (f in src.walkTopDown().filter { it.isFile }) {
            assertArrayEquals(f.path, f.readBytes(), target.resolve("p").resolve(f.relativeTo(src).path).readBytes())
        }
        assertTrue("no lock left behind", repoDir.resolve("locks").list()!!.isEmpty())
        tmp.deleteRecursively()
    }
}

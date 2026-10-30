package app.fayaz.otgmaster.backup.restic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Random

/** Desktop -> app: repositories written by restic itself, read back byte for byte. */
class ResticReadTest {

    private fun restic(): String? = listOf(System.getenv("RESTIC"), System.getProperty("user.home") + "/.local/bin/restic", "/usr/bin/restic")
        .firstOrNull { it != null && File(it).canExecute() }

    private fun run(vararg cmd: String, password: String) {
        val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
        pb.environment()["RESTIC_PASSWORD"] = password
        val p = pb.start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor() == 0) { "${cmd.joinToString(" ")} failed:\n$out" }
    }

    /** A source tree with what a phone folder holds: big and small files, empty ones, nesting, repeats. */
    private fun sourceTree(dir: File): Map<String, ByteArray> {
        val rnd = Random(2026)
        val files = linkedMapOf(
            "big.bin" to ByteArray(9_000_000).also { rnd.nextBytes(it) },
            "text.txt" to "hello restic\n".repeat(5000).toByteArray(),
            "empty" to ByteArray(0),
            "DCIM/Camera/IMG_0001.jpg" to ByteArray(1_234_567).also { rnd.nextBytes(it) },
            "DCIM/Camera/copy.jpg" to ByteArray(0),
            "a/b/c/d/deep.dat" to ByteArray(70_000).also { rnd.nextBytes(it) },
            "unicöde 日本.txt" to "ünïcode".toByteArray(),
        )
        files["DCIM/Camera/copy.jpg"] = files.getValue("DCIM/Camera/IMG_0001.jpg") // deduplicated by restic
        for ((rel, data) in files) dir.resolve(rel).apply { parentFile.mkdirs(); writeBytes(data) }
        return files
    }

    private fun restoreAll(repo: ResticRepository, treeId: String, prefix: String = "", out: MutableMap<String, ByteArray> = linkedMapOf()): Map<String, ByteArray> {
        for (n in repo.tree(treeId)) {
            val path = if (prefix.isEmpty()) n.name else "$prefix/${n.name}"
            when (n.type) {
                "dir" -> restoreAll(repo, n.subtree!!, path, out)
                "file" -> out[path] = ByteArrayOutputStream().also { b -> repo.readFile(n) { b.write(it) } }.toByteArray()
            }
        }
        return out
    }

    private fun check(compression: String) {
        val restic = restic(); assumeTrue("restic not installed", restic != null)
        val tmp = Files.createTempDirectory("restic-read").toFile()
        val src = tmp.resolve("src").also { it.mkdirs() }
        val expected = sourceTree(src)
        val repoDir = tmp.resolve("repo")
        run(restic!!, "-r", repoDir.path, "init", password = "pw")
        // --tag and a relative path through -C would vary; back up the directory's contents.
        run(restic, "-r", repoDir.path, "backup", "--compression", compression, "--host", "laptop", src.path, password = "pw")

        val repo = ResticRepository.open(LocalBackend(repoDir), "pw".toByteArray())
        assertNotNull(repo)
        val snap = repo!!.snapshots().single()
        assertEquals("laptop", snap.hostname)
        // restic stores the absolute path as nested directories from the root.
        var treeId = snap.tree
        for (part in src.path.trim('/').split('/')) {
            treeId = repo.tree(treeId).single { it.name == part }.subtree!!
        }
        val got = restoreAll(repo, treeId)
        assertEquals(expected.keys.sorted(), got.keys.sorted())
        for ((k, v) in expected) assertArrayEquals("content of $k", v, got[k])
        tmp.deleteRecursively()
    }

    @Test fun `a compressed restic repository restores byte for byte`() = check("auto")
    @Test fun `an uncompressed restic repository restores byte for byte`() = check("off")

    @Test
    fun `a damaged pack is reported, never returned as data`() {
        val restic = restic(); assumeTrue("restic not installed", restic != null)
        val tmp = Files.createTempDirectory("restic-damage").toFile()
        val src = tmp.resolve("src").also { it.mkdirs() }
        src.resolve("f.bin").writeBytes(ByteArray(300_000).also { Random(1).nextBytes(it) })
        val repoDir = tmp.resolve("repo")
        run(restic!!, "-r", repoDir.path, "init", password = "pw")
        run(restic, "-r", repoDir.path, "backup", "--compression", "off", src.path, password = "pw")
        // Flip one byte in the middle of every data pack.
        repoDir.resolve("data").walkTopDown().filter { it.isFile }.forEach { f ->
            f.setWritable(true) // restic writes packs read-only
            val b = f.readBytes(); b[b.size / 3] = (b[b.size / 3].toInt() xor 0x40).toByte(); f.writeBytes(b)
        }
        val repo = ResticRepository.open(LocalBackend(repoDir), "pw".toByteArray())!!
        val failure = runCatching {
            var t = repo.snapshots().single().tree
            for (part in src.path.trim('/').split('/')) t = repo.tree(t).single { it.name == part }.subtree!!
            restoreAll(repo, t)
        }.exceptionOrNull()
        assertNotNull("a flipped byte must not restore silently", failure)
        tmp.deleteRecursively()
    }
}

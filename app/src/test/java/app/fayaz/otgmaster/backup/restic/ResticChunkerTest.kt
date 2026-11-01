package app.fayaz.otgmaster.backup.restic

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Random

class ResticChunkerTest {
    companion object {
        @JvmStatic
        @org.junit.BeforeClass
        fun loadHostLibrary() = ZstdNativeTest.loadHostLibrary()
    }

    private fun restic(): String? = listOf(System.getenv("RESTIC"), System.getProperty("user.home") + "/.local/bin/restic", "/usr/bin/restic")
        .firstOrNull { it != null && File(it).canExecute() }

    private fun run(vararg cmd: String) {
        val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
        pb.environment()["RESTIC_PASSWORD"] = "pw"
        val p = pb.start(); val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor() == 0) { "${cmd.joinToString(" ")} failed:\n$out" }
    }

    @Test
    fun `chunks are exactly the ones restic cuts, with the repository's polynomial`() {
        val restic = restic(); assumeTrue("restic not installed", restic != null)
        val tmp = Files.createTempDirectory("restic-chunk").toFile()
        val file = tmp.resolve("src/data.bin").apply { parentFile.mkdirs() }
        // Random data with a repeated stretch, so cut points fall at varied offsets.
        val rnd = Random(87)
        val data = ByteArray(30_000_000).also { rnd.nextBytes(it) }
        System.arraycopy(data, 1_000_000, data, 17_000_000, 4_000_000)
        file.writeBytes(data)
        val repoDir = tmp.resolve("repo")
        run(restic!!, "-r", repoDir.path, "init")
        run(restic, "-r", repoDir.path, "backup", "--compression", "off", file.parent)

        val repo = ResticRepository.open(LocalBackend(repoDir), "pw".toByteArray())!!
        val pol = ResticChunker.parsePolynomial(repo.config.getString("chunker_polynomial"))
        // restic's data blobs for the file, in order, from its tree.
        var t = repo.snapshots().single().tree
        for (part in file.parent.trim('/').split('/')) t = repo.tree(t).single { it.name == part }.subtree!!
        val resticChunks = repo.tree(t).single { it.name == "data.bin" }.content

        val ours = mutableListOf<String>()
        val c = ResticChunker(pol, file.inputStream().buffered())
        while (true) { val chunk = c.next() ?: break; ours += ResticRepository.sha256Hex(chunk) }
        assertEquals(resticChunks, ours)
        tmp.deleteRecursively()
    }

    @Test
    fun `small inputs are one chunk and empty input none`() {
        val pol = ResticChunker.parsePolynomial("3dea92648f6e83")
        assertEquals(null, ResticChunker(pol, ByteArray(0).inputStream()).next())
        val c = ResticChunker(pol, ByteArray(1000) { it.toByte() }.inputStream())
        assertEquals(1000, c.next()!!.size)
        assertEquals(null, c.next())
    }
}

package app.fayaz.otgmaster.backup.restic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.random.Random

/**
 * "Mount a backup": a repository restic itself wrote (two snapshots, compressed, files
 * spanning many blobs) browsed through [ResticFileSystem], every file read back whole and
 * at random offsets against the originals.
 */
class ResticFileSystemTest {
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
        val p = pb.start(); val out = p.inputStream.bufferedReader().readText()
        assertEquals("${cmd.joinToString(" ")}:\n$out", 0, p.waitFor())
    }

    @Test
    fun `a restic repository browses and reads like a drive`() {
        val restic = restic(); assumeTrue("restic not installed", restic != null)
        val tmp = Files.createTempDirectory("restic-fs").toFile()
        try {
            val src = File(tmp, "src").apply { mkdirs() }
            val rnd = Random(11)
            val big = rnd.nextBytes(20 * 1024 * 1024 + 777)       // several chunks
            File(src, "big.bin").writeBytes(big)
            File(src, "docs").mkdirs()
            File(src, "docs/note.txt").writeText("first version\n")
            File(src, "empty.txt").writeBytes(ByteArray(0))
            val repoDir = File(tmp, "repo")
            run(restic!!, "-r", repoDir.path, "init")
            run(restic, "-r", repoDir.path, "backup", "--host", "test", src.path)
            File(src, "docs/note.txt").writeText("second version, longer\n")
            run(restic, "-r", repoDir.path, "backup", "--host", "test", src.path)

            val repo = ResticRepository.open(LocalBackend(repoDir), "pw".toByteArray())!!
            val fs = ResticFileSystem(repo, "Test backup")
            val snaps = fs.rootDirectory.listFiles()
            assertEquals(2, snaps.size)
            assertTrue(snaps.all { it.isDirectory && Regex("\\d{4}-\\d\\d-\\d\\d \\d\\d\\.\\d\\d\\.\\d\\d \\([0-9a-f]{8}\\)").matches(it.name) })

            // restic stores the absolute path: walk down to the source folder.
            fun srcDir(snapshot: me.jahnen.libaums.core.fs.UsbFile): me.jahnen.libaums.core.fs.UsbFile {
                var d = snapshot
                for (part in src.path.trim('/').split('/')) d = d.listFiles().first { it.name == part }
                return d
            }
            val notes = snaps.map { s -> srcDir(s).listFiles().first { it.name == "docs" }.listFiles().single() }
            fun readAll(f: me.jahnen.libaums.core.fs.UsbFile): ByteArray =
                ByteArray(f.length.toInt()).also { if (it.isNotEmpty()) f.read(0, ByteBuffer.wrap(it)) }
            assertEquals("first version\n", String(readAll(notes[0])))
            assertEquals("second version, longer\n", String(readAll(notes[1])))

            val dir = srcDir(snaps[1])
            assertEquals(setOf("big.bin", "docs", "empty.txt"), dir.list().toSet())
            val bigFile = dir.listFiles().first { it.name == "big.bin" }
            assertEquals(big.size.toLong(), bigFile.length)
            assertArrayEquals(big, readAll(bigFile))
            repeat(40) {
                val off = rnd.nextLong(big.size.toLong())
                val len = minOf(1 + rnd.nextInt(3 shl 20), (big.size - off).toInt())
                val buf = ByteArray(len)
                bigFile.read(off, ByteBuffer.wrap(buf))
                assertArrayEquals("read of $len at $off", big.copyOfRange(off.toInt(), off.toInt() + len), buf)
            }
            assertEquals(0L, dir.listFiles().first { it.name == "empty.txt" }.length)
            try { dir.createFile("x"); throw AssertionError("a mounted backup took a new file") } catch (e: java.io.IOException) { }
        } finally {
            tmp.deleteRecursively()
        }
    }
}

package app.fayaz.otgmaster.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/**
 * A plain copy's manifest must not take the place of a file being copied, and Verify
 * must not call an incomplete copy verified. Review finding 11, 2026-11-09: the manifest
 * was written into the copy's top folder as otgmaster-copy.json after the files, so a
 * source file of that name was copied, checked, then overwritten (and a source folder of
 * that name deleted); and Verify ignored the errors the manifest recorded.
 */
class CopyManifestTest {

    /** [LocalFileTree], except that the file [unreadable] fails to open. */
    private class FlakyTree(root: File, private val unreadable: String) : FileTree by LocalFileTree(root) {
        private val inner = LocalFileTree(root)
        override fun openRead(path: String): InputStream =
            if (path == unreadable) throw IOException("permission denied") else inner.openRead(path)
    }

    private fun tmp() = Files.createTempDirectory("copymanifest").toFile()

    @Test
    fun `a source file named like the manifest is copied intact and verifies`() {
        val t = tmp()
        val src = t.resolve("src").also { it.mkdirs() }
        src.resolve(CopyEngine.MANIFEST).writeText("the user's own json")
        src.resolve("other.txt").writeText("other")
        val dst = LocalFileTree(t.resolve("dst").also { it.mkdirs() })
        CopyEngine.copy(LocalFileTree(src), dst, "copy-1", CopyEngine.Options())
        assertEquals("the manifest overwrote the copied file",
            "the user's own json", t.resolve("dst/copy-1/${CopyEngine.MANIFEST}").readText())
        assertEquals(emptyList<String>(), CopyEngine.verify(dst, "copy-1"))
        t.deleteRecursively()
    }

    @Test
    fun `a source folder named like the manifest is not deleted`() {
        val t = tmp()
        val src = t.resolve("src").also { it.mkdirs() }
        src.resolve("${CopyEngine.MANIFEST}/inside.txt").apply { parentFile.mkdirs(); writeText("kept") }
        val dst = LocalFileTree(t.resolve("dst").also { it.mkdirs() })
        CopyEngine.copy(LocalFileTree(src), dst, "copy-1", CopyEngine.Options())
        assertEquals("kept", t.resolve("dst/copy-1/${CopyEngine.MANIFEST}/inside.txt").readText())
        assertEquals(emptyList<String>(), CopyEngine.verify(dst, "copy-1"))
        t.deleteRecursively()
    }

    @Test
    fun `Verify reports what the copy could not read`() {
        val t = tmp()
        val src = t.resolve("src").also { it.mkdirs() }
        src.resolve("good.txt").writeText("good")
        src.resolve("bad.txt").writeText("bad")
        val dst = LocalFileTree(t.resolve("dst").also { it.mkdirs() })
        val m = CopyEngine.copy(FlakyTree(src, "bad.txt"), dst, "copy-1", CopyEngine.Options())
        assertEquals(1, m.errors.size)
        val problems = CopyEngine.verify(dst, "copy-1")
        assertTrue("Verify called a copy that left out bad.txt complete: $problems",
            problems.any { it.contains("bad.txt") })
        t.deleteRecursively()
    }

    @Test
    fun `a copy made before this change still verifies`() {
        val t = tmp()
        val src = t.resolve("src").also { it.mkdirs() }
        src.resolve("a.txt").writeText("a")
        val dst = LocalFileTree(t.resolve("dst").also { it.mkdirs() })
        CopyEngine.copy(LocalFileTree(src), dst, "copy-1", CopyEngine.Options())
        // The old layout: the manifest under its plain name, without the marker.
        val manifests = t.resolve("dst/copy-1").listFiles()!!.filter { it.name.startsWith("otgmaster-copy") }
        assertEquals(1, manifests.size)
        val json = org.json.JSONObject(manifests[0].readText()).apply { remove("kind") }
        manifests[0].delete()
        t.resolve("dst/copy-1/${CopyEngine.MANIFEST}").writeText(json.toString())
        assertEquals(emptyList<String>(), CopyEngine.verify(dst, "copy-1"))
        t.deleteRecursively()
    }
}

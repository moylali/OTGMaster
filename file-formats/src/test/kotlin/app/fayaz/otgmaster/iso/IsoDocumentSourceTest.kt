package app.fayaz.otgmaster.iso

import app.fayaz.otgmaster.core.DocumentNode
import app.fayaz.otgmaster.core.resolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * The ISO reader against images made by another tool: xorriso when installed (CI installs
 * it), else pycdlib through uv. 7-Zip, which shares no code with the reader, lists the same
 * files when present.
 */
class IsoDocumentSourceTest {

    private val manual = ByteArray(1_500_017).also { java.util.Random(5).nextBytes(it) }

    private fun has(tool: String) = ProcessBuilder("sh", "-c", "command -v $tool").start().waitFor() == 0
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** An image holding [manual] as Docs/Manual.pdf, a long Unicode name, an empty file; [joliet] adds Joliet names. */
    private fun makeIso(joliet: Boolean): File {
        val iso = File.createTempFile("isotest", ".iso").apply { delete(); deleteOnExit() }
        val tree = java.nio.file.Files.createTempDirectory("isotree").toFile()
        File(tree, "DOCS").mkdirs(); File(tree, "DOCS/MANUAL.PDF").writeBytes(manual)
        File(tree, "EMPTY.TXT").writeBytes(ByteArray(0))
        if (joliet) File(tree, "a long file name, ünïcode.txt").writeText("Unicode\n")
        val p = if (has("xorriso")) {
            ProcessBuilder(listOf("xorriso", "-as", "mkisofs", "-quiet") + (if (joliet) listOf("-J") else emptyList()) +
                listOf("-o", iso.path, tree.path))
        } else {
            assumeTrue("neither xorriso nor uv", has("uv"))
            val py = """
import pycdlib, sys, os
iso = pycdlib.PyCdlib(); iso.new(interchange_level=3, joliet=${if (joliet) 3 else "None"})
root = sys.argv[2]
iso.add_directory('/DOCS', **({'joliet_path': '/DOCS'} if ${if (joliet) "True" else "False"} else {}))
for rel, iso_name, jname in [('DOCS/MANUAL.PDF', '/DOCS/MANUAL.PDF;1', '/DOCS/MANUAL.PDF'), ('EMPTY.TXT', '/EMPTY.TXT;1', '/EMPTY.TXT')]:
    iso.add_file(os.path.join(root, rel), iso_name, **({'joliet_path': jname} if ${if (joliet) "True" else "False"} else {}))
if ${if (joliet) "True" else "False"}:
    iso.add_file(os.path.join(root, 'a long file name, ünïcode.txt'), '/LONGNAME.TXT;1', joliet_path='/a long file name, ünïcode.txt')
iso.write(sys.argv[1]); iso.close()
""".trimIndent()
            ProcessBuilder("uv", "run", "--quiet", "--with", "pycdlib", "python", "-c", py, iso.path, tree.path)
        }
        val proc = p.redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertEquals("image build failed: $out", 0, proc.waitFor())
        tree.deleteRecursively()
        return iso
    }

    private fun readAll(src: IsoDocumentSource, n: DocumentNode): ByteArray = src.open(n).use { h ->
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(65_536); var off = 0L
        while (true) { val r = h.read(off, buf, buf.size); if (r == 0) break; out.write(buf, 0, r); off += r }
        out.toByteArray()
    }

    @Test
    fun `a Joliet image reads byte for byte with its long Unicode names`() {
        val iso = makeIso(joliet = true)
        RandomAccessFile(iso, "r").use { raf ->
            val src = IsoDocumentSource.open(raf.channel)
            assertEquals(listOf("DOCS", "EMPTY.TXT", "a long file name, ünïcode.txt"), src.children(src.root()).map { it.name }.sorted())
            assertEquals(sha(manual), sha(readAll(src, src.resolve(listOf("DOCS", "MANUAL.PDF"))!!)))
            assertEquals("Unicode\n", String(readAll(src, src.resolve(listOf("a long file name, ünïcode.txt"))!!)))
            assertEquals(0, readAll(src, src.resolve(listOf("EMPTY.TXT"))!!).size)
            src.open(src.resolve(listOf("DOCS", "MANUAL.PDF"))!!).use { h ->
                val b = ByteArray(4096); assertEquals(17, h.read(manual.size - 17L, b, 4096))
                assertEquals(sha(manual.copyOfRange(manual.size - 17, manual.size)), sha(b.copyOf(17)))
            }
            assertThrows(IOException::class.java) { src.createFile(src.root(), "x") }
        }
        if (has("7z")) {
            val p = ProcessBuilder("7z", "l", iso.path).redirectErrorStream(true).start()
            val listing = p.inputStream.bufferedReader().readText(); p.waitFor()
            assertTrue(listing, listing.contains("MANUAL.PDF") || listing.contains("Manual.pdf"))
        }
    }

    @Test
    fun `a plain ISO 9660 image uses its names without the version or a trailing dot`() {
        val iso = makeIso(joliet = false)
        RandomAccessFile(iso, "r").use { raf ->
            val src = IsoDocumentSource.open(raf.channel)
            assertEquals(listOf("DOCS", "EMPTY.TXT"), src.children(src.root()).map { it.name }.sorted())
            assertEquals(sha(manual), sha(readAll(src, src.resolve(listOf("DOCS", "MANUAL.PDF"))!!)))
            assertNull(src.resolve(listOf("DOCS", "MANUAL.PDF;1")))
        }
    }

    @Test
    fun `a file that is not an ISO image is refused`() {
        val f = File.createTempFile("notiso", ".bin").apply { deleteOnExit(); writeBytes(ByteArray(64 * 1024)) }
        RandomAccessFile(f, "r").use { raf ->
            assertTrue(assertThrows(IsoDocumentSource.IsoFormatException::class.java) { IsoDocumentSource.open(raf.channel) }
                .message!!.contains("not an ISO 9660 image"))
        }
    }

    @Test
    fun `a directory record pointing outside the image is refused`() {
        val iso = makeIso(joliet = false)
        // Cut the image short: the file extents now lie past its end.
        RandomAccessFile(iso, "rw").use { it.setLength(iso.length() - 1_000_000) }
        RandomAccessFile(iso, "r").use { raf ->
            val src = IsoDocumentSource.open(raf.channel)
            assertThrows(IOException::class.java) { src.children(src.resolve(listOf("DOCS"))!!) }
        }
    }
}

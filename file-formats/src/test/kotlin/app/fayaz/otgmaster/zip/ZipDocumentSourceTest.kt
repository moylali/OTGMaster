package app.fayaz.otgmaster.zip

import app.fayaz.otgmaster.core.DocumentNode
import app.fayaz.otgmaster.core.resolve
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest

/**
 * The ZIP reader against archives made by other tools: Info-ZIP's zip (stored, Deflate, ZIP64
 * forced), 7-Zip, and archives Python's zipfile writes on purpose wrong (unsafe names,
 * duplicates, a damaged CRC, an entry larger than declared).
 */
class ZipDocumentSourceTest {

    private lateinit var tree: File
    private val small = ByteArray(300_017).also { java.util.Random(1).nextBytes(it) }
    // Larger than MEMORY_ENTRY_BYTES, so it is streamed; compressible so the archive stays small.
    private val big = ByteArray((ZipDocumentSource.MEMORY_ENTRY_BYTES + 3_000_001).toInt()) { (it / 7 % 251).toByte() }

    private fun run(dir: File, vararg cmd: String): Int =
        ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()

    private fun has(tool: String) = run(File("."), "sh", "-c", "command -v $tool") == 0

    @Before
    fun setUp() {
        tree = Files.createTempDirectory("ziptree").toFile()
        File(tree, "Docs/deep/er").mkdirs()
        File(tree, "Docs/Manual.pdf").writeBytes(small)
        File(tree, "Docs/deep/er/big.bin").writeBytes(big)
        File(tree, "file with spaces.txt").writeText("Hello World\n")
        File(tree, "unicöde_fîle.txt").writeText("Unicode\n")
        File(tree, "empty.txt").writeBytes(ByteArray(0))
    }

    private fun <R> withZip(zip: File, block: (ZipDocumentSource) -> R): R =
        RandomAccessFile(zip, "r").use { raf -> block(ZipDocumentSource(ZipArchive.open(raf.channel))) }

    private fun readAll(src: ZipDocumentSource, node: DocumentNode, chunk: Int = 65_536): ByteArray =
        src.open(node).use { h ->
            val out = java.io.ByteArrayOutputStream()
            var off = 0L
            val buf = ByteArray(chunk)
            while (true) { val n = h.read(off, buf, chunk); if (n == 0) break; out.write(buf, 0, n); off += n }
            out.toByteArray()
        }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun checkTree(src: ZipDocumentSource) {
        assertEquals(listOf("Docs", "empty.txt", "file with spaces.txt", "unicöde_fîle.txt"),
            src.children(src.root()).map { it.name }.sorted())
        assertEquals(sha(small), sha(readAll(src, src.resolve(listOf("Docs", "Manual.pdf"))!!)))
        assertEquals(sha(big), sha(readAll(src, src.resolve(listOf("Docs", "deep", "er", "big.bin"))!!, chunk = 1_000_003)))
        assertEquals("Hello World\n", String(readAll(src, src.resolve(listOf("file with spaces.txt"))!!)))
        assertEquals("Unicode\n", String(readAll(src, src.resolve(listOf("unicöde_fîle.txt"))!!)))
        assertEquals(0, readAll(src, src.resolve(listOf("empty.txt"))!!).size)
        assertNull(src.resolve(listOf("docs", "Manual.pdf")))
        assertThrows(IOException::class.java) { src.createFile(src.root(), "x") }
    }

    @Test
    fun `Info-ZIP archives, Deflate and stored, read byte for byte`() {
        assumeTrue("zip missing", has("zip"))
        for (level in listOf("-6", "-0")) {
            val z = File.createTempFile("info", ".zip").apply { delete(); deleteOnExit() }
            assertEquals(0, run(tree, "zip", "-q", "-r", level, z.path, "."))
            withZip(z) { checkTree(it) }
        }
    }

    @Test
    fun `a ZIP64 archive (Info-ZIP -fz) reads`() {
        assumeTrue("zip missing", has("zip"))
        val z = File.createTempFile("z64", ".zip").apply { delete(); deleteOnExit() }
        assertEquals(0, run(tree, "zip", "-q", "-r", "-fz", z.path, "."))
        withZip(z) { checkTree(it) }
    }

    @Test
    fun `a 7-Zip archive reads`() {
        assumeTrue("7z missing", has("7z"))
        val z = File.createTempFile("sevenz", ".zip").apply { delete(); deleteOnExit() }
        assertEquals(0, run(tree, "7z", "a", "-tzip", "-bd", z.path, "."))
        withZip(z) { checkTree(it) }
    }

    @Test
    fun `seeks in a streamed Deflate entry, backwards and forwards, return the right bytes`() {
        assumeTrue("zip missing", has("zip"))
        val z = File.createTempFile("seek", ".zip").apply { delete(); deleteOnExit() }
        assertEquals(0, run(tree, "zip", "-q", "-r", "-6", z.path, "."))
        withZip(z) { src ->
            src.open(src.resolve(listOf("Docs", "deep", "er", "big.bin"))!!).use { h ->
                val buf = ByteArray(4096)
                for (off in listOf(big.size - 4096L, 0L, 10_000_000L, 9_999_000L, 5L, big.size - 1L)) {
                    val n = h.read(off, buf, 4096)
                    assertEquals("at $off", minOf(4096L, big.size - off).toInt(), n)
                    assertArrayEquals("at $off", big.copyOfRange(off.toInt(), off.toInt() + n), buf.copyOf(n))
                }
                assertEquals(0, h.read(big.size.toLong(), buf, 4096))
            }
        }
    }

    /** Builds a ZIP with Python's zipfile from a script body that has `z` open for writing. */
    private fun python(body: String): File {
        assumeTrue("python3 missing", has("python3"))
        val z = File.createTempFile("pyzip", ".zip").apply { deleteOnExit() }
        val script = "import zipfile,sys\nz=zipfile.ZipFile(sys.argv[1],'w',zipfile.ZIP_DEFLATED)\n$body\nz.close()\n"
        val p = ProcessBuilder("python3", "-c", script, z.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())
        return z
    }

    @Test
    fun `unsafe names are left out and counted`() {
        val z = python("""
for n in ['../evil.txt','/abs.txt','a/./b.txt','a//b.txt','ok.txt']: z.writestr(n, 'x')
""".trimIndent())
        withZip(z) { src ->
            assertEquals(listOf("ok.txt"), src.children(src.root()).map { it.name })
            assertEquals(4, src.archive.skippedUnsafe)
        }
    }

    @Test
    fun `a duplicate name, or a file where a folder must be, is refused`() {
        val dup = python("z.writestr('a.txt','1')\nz.writestr('a.txt','2')")
        assertThrows(ZipArchive.ZipFormatException::class.java) { withZip(dup) { } }
        val clash = python("z.writestr('a','file')\nz.writestr('a/b.txt','x')")
        assertThrows(ZipArchive.ZipFormatException::class.java) { withZip(clash) { } }
    }

    @Test
    fun `a damaged CRC fails the read instead of returning the bytes`() {
        val z = python("z.writestr('a.bin', b'A'*100000)")
        // The CRC-32 is at byte 16 of the central directory entry; change it there.
        val bytes = z.readBytes()
        val cd = (bytes.size - 22 downTo 0).first { i -> bytes[i] == 0x50.toByte() && bytes[i+1] == 0x4b.toByte() && bytes[i+2] == 1.toByte() && bytes[i+3] == 2.toByte() }
        bytes[cd + 16] = (bytes[cd + 16].toInt() xor 0xFF).toByte()
        z.writeBytes(bytes)
        withZip(z) { src ->
            assertThrows(IOException::class.java) { src.open(src.resolve(listOf("a.bin"))!!) }   // in-memory: checked on open
        }
    }

    @Test
    fun `an entry that inflates past its declared size is refused`() {
        val z = python("z.writestr('bomb.bin', b'\\0'*(20<<20))")
        // Declare 1 MiB instead of 20 MiB in the central directory.
        val bytes = z.readBytes()
        val cd = (bytes.size - 22 downTo 0).first { i -> bytes[i] == 0x50.toByte() && bytes[i+1] == 0x4b.toByte() && bytes[i+2] == 1.toByte() && bytes[i+3] == 2.toByte() }
        java.nio.ByteBuffer.wrap(bytes, cd + 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(1 shl 20)
        z.writeBytes(bytes)
        withZip(z) { src ->
            assertTrue(assertThrows(IOException::class.java) { src.open(src.resolve(listOf("bomb.bin"))!!) }.message!!.contains("declared size"))
        }
    }
}

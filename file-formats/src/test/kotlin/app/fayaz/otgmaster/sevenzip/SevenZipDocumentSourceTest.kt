package app.fayaz.otgmaster.sevenzip

import app.fayaz.otgmaster.core.DocumentNode
import app.fayaz.otgmaster.core.resolve
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files

/** The 7z reader against archives 7-Zip (p7zip's 7z) makes: solid LZMA2, plain and encrypted. */
class SevenZipDocumentSourceTest {

    private lateinit var tree: File
    private val small = ByteArray(300_017).also { java.util.Random(1).nextBytes(it) }
    private val big = ByteArray((SevenZipDocumentSource.MEMORY_ENTRY_BYTES + 3_000_001).toInt()) { (it / 7 % 251).toByte() }

    private fun run(dir: File, vararg cmd: String): Int =
        ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()

    private fun has(tool: String) = run(File("."), "sh", "-c", "command -v $tool") == 0

    @Before
    fun setUp() {
        tree = Files.createTempDirectory("sztree").toFile()
        File(tree, "Docs/deep/er").mkdirs()
        File(tree, "Docs/Manual.pdf").writeBytes(small)
        File(tree, "Docs/deep/er/big.bin").writeBytes(big)
        File(tree, "file with spaces.txt").writeText("Hello World\n")
        File(tree, "unicöde_fîle.txt").writeText("Unicode\n")
        File(tree, "empty.txt").writeBytes(ByteArray(0))
    }

    private fun sevenZip(vararg opts: String): File {
        assumeTrue("7z (p7zip) is not installed", has("7z"))
        val out = File(Files.createTempDirectory("szout").toFile(), "a.7z")
        assertEquals(0, run(tree, "7z", "a", *opts, out.path, "Docs", "file with spaces.txt", "unicöde_fîle.txt", "empty.txt"))
        return out
    }

    private fun <R> with7z(f: File, password: String = "", block: (SevenZipDocumentSource) -> R): R =
        RandomAccessFile(f, "r").use { raf -> SevenZipDocumentSource.open(raf.channel, password.toCharArray()).let { s -> try { block(s) } finally { s.close() } } }

    private fun readAll(src: SevenZipDocumentSource, node: DocumentNode, chunk: Int = 65_536): ByteArray =
        src.open(node).use { h ->
            val out = java.io.ByteArrayOutputStream()
            var off = 0L
            val buf = ByteArray(chunk)
            while (true) { val n = h.read(off, buf, chunk); if (n == 0) break; out.write(buf, 0, n); off += n }
            out.toByteArray()
        }

    private fun node(src: SevenZipDocumentSource, path: String) = src.resolve(path.split("/")) ?: error("$path not found")

    private fun checkTree(src: SevenZipDocumentSource) {
        assertArrayEquals(small, readAll(src, node(src, "Docs/Manual.pdf")))
        assertArrayEquals(big, readAll(src, node(src, "Docs/deep/er/big.bin"), chunk = 1 shl 20))
        assertEquals("Hello World\n", String(readAll(src, node(src, "file with spaces.txt"))))
        assertEquals("Unicode\n", String(readAll(src, node(src, "unicöde_fîle.txt"))))
        assertEquals(0, readAll(src, node(src, "empty.txt")).size)
        assertEquals(listOf("Manual.pdf", "deep"), src.children(node(src, "Docs")).map { it.name }.sorted())
    }

    @Test fun `solid LZMA2, the 7z default`() { with7z(sevenZip("-mx=5")) { checkTree(it) } }

    @Test fun `not solid, LZMA`() { with7z(sevenZip("-ms=off", "-m0=LZMA")) { checkTree(it) } }

    @Test
    fun `encrypted contents open with the password, and fail clearly without it`() {
        val f = sevenZip("-pPassw0rd!")
        with7z(f, "Passw0rd!") { checkTree(it) }
        with7z(f) { src ->
            val e = assertThrows(IOException::class.java) { readAll(src, node(src, "Docs/Manual.pdf")) }
            assert("password" in (e.message ?: "")) { e.message!! }
        }
    }

    @Test
    fun `an encrypted list of files needs the password to open`() {
        val f = sevenZip("-pPassw0rd!", "-mhe=on")
        with7z(f, "Passw0rd!") { checkTree(it) }
        assertThrows(IOException::class.java) { with7z(f) { } }
        // A wrong password decrypts the list to garbage, which XZ reports as corrupt data.
        assertThrows(IOException::class.java) { with7z(f, "not-it") { } }
    }

    @Test
    fun `a large file reads at any offset and backwards`() {
        with7z(sevenZip()) { src ->
            src.open(node(src, "Docs/deep/er/big.bin")).use { h ->
                val buf = ByteArray(4096)
                for (off in listOf(10_000_000L, 1_000_000L, big.size - 100L, 0L)) {
                    val n = h.read(off, buf, buf.size)
                    assertEquals(minOf(4096L, big.size - off).toInt(), n)
                    assertArrayEquals(big.copyOfRange(off.toInt(), off.toInt() + n), buf.copyOf(n))
                }
            }
        }
    }

    @Test
    fun `unsafe names are refused by the path check`() {
        assertEquals(null, SevenZipDocumentSource.safePath("../evil"))
        assertEquals(null, SevenZipDocumentSource.safePath("/abs"))
        assertEquals(null, SevenZipDocumentSource.safePath("a\\..\\b"))
        assertEquals(listOf("a", "b.txt"), SevenZipDocumentSource.safePath("a\\b.txt"))
    }
}

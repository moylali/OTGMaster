package app.fayaz.otgmaster.tar

import app.fayaz.otgmaster.core.DocumentNode
import app.fayaz.otgmaster.core.resolve
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files

/**
 * The TAR reader against archives made by other tools: GNU tar in its default (GNU), POSIX
 * (PAX) and old v7 formats, gzipped by gzip itself, and archives Python's tarfile writes on
 * purpose wrong (an unsafe name, a link, a damaged checksum).
 */
class TarDocumentSourceTest {

    private lateinit var tree: File
    private val small = ByteArray(300_017).also { java.util.Random(1).nextBytes(it) }
    // Larger than MEMORY_ENTRY_BYTES, so the .tar.gz path streams it; compressible to keep the archive small.
    private val big = ByteArray((TarDocumentSource.MEMORY_ENTRY_BYTES + 3_000_001).toInt()) { (it / 7 % 251).toByte() }
    // Over 100 bytes, so GNU tar needs its long-name header and PAX its path record.
    private val longDir = "a".repeat(60) + "/" + "b".repeat(60)

    private fun run(dir: File, vararg cmd: String): Int =
        ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()

    private fun has(tool: String) = run(File("."), "sh", "-c", "command -v $tool") == 0

    @Before
    fun setUp() {
        tree = Files.createTempDirectory("tartree").toFile()
        File(tree, "Docs/deep/er").mkdirs()
        File(tree, "Docs/Manual.pdf").writeBytes(small)
        File(tree, "Docs/deep/er/big.bin").writeBytes(big)
        File(tree, "file with spaces.txt").writeText("Hello World\n")
        File(tree, "unicöde_fîle.txt").writeText("Unicode\n")
        File(tree, "empty.txt").writeBytes(ByteArray(0))
        File(tree, longDir).mkdirs()
        File(tree, "$longDir/long-name-file.txt").writeText("long\n")
    }

    private fun tar(format: String, gzip: Boolean): File {
        assumeTrue("GNU tar is not installed", has("tar"))
        val out = File.createTempFile("tartest", if (gzip) ".tar.gz" else ".tar").apply { deleteOnExit() }
        val args = mutableListOf("tar", "--format=$format", "-c", "-f", out.path)
        if (gzip) args.add(2, "-z")
        args += listOf("Docs", "file with spaces.txt", "unicöde_fîle.txt", "empty.txt", longDir.substringBefore('/'))
        assertEquals(0, run(tree, *args.toTypedArray()))
        return out
    }

    private fun <R> withTar(f: File, gzip: Boolean, block: (TarDocumentSource) -> R): R =
        RandomAccessFile(f, "r").use { raf -> block(TarDocumentSource.open(raf.channel, gzip)) }

    private fun readAll(src: TarDocumentSource, node: DocumentNode, chunk: Int = 65_536): ByteArray =
        src.open(node).use { h ->
            val out = java.io.ByteArrayOutputStream()
            var off = 0L
            val buf = ByteArray(chunk)
            while (true) { val n = h.read(off, buf, chunk); if (n == 0) break; out.write(buf, 0, n); off += n }
            out.toByteArray()
        }

    private fun node(src: TarDocumentSource, path: String) = src.resolve(path.split("/")) ?: error("$path not found")

    private fun checkTree(src: TarDocumentSource) {
        assertArrayEquals(small, readAll(src, node(src, "Docs/Manual.pdf")))
        assertArrayEquals(big, readAll(src, node(src, "Docs/deep/er/big.bin"), chunk = 1 shl 20))
        assertEquals("Hello World\n", String(readAll(src, node(src, "file with spaces.txt"))))
        assertEquals("Unicode\n", String(readAll(src, node(src, "unicöde_fîle.txt"))))
        assertEquals(0, readAll(src, node(src, "empty.txt")).size)
        assertEquals("long\n", String(readAll(src, node(src, "$longDir/long-name-file.txt"))))
        assertEquals(big.size.toLong(), node(src, "Docs/deep/er/big.bin").size)
        assertEquals(listOf("Manual.pdf", "deep"), src.children(node(src, "Docs")).map { it.name }.sorted())
    }

    @Test fun `GNU format, plain`() { withTar(tar("gnu", false), false) { checkTree(it) } }
    @Test fun `POSIX PAX format, plain`() { withTar(tar("posix", false), false) { checkTree(it) } }
    @Test fun `GNU format, gzipped`() { withTar(tar("gnu", true), true) { checkTree(it) } }
    @Test fun `POSIX PAX format, gzipped`() { withTar(tar("posix", true), true) { checkTree(it) } }

    @Test
    fun `reads at any offset and backwards, plain and gzipped`() {
        for (gz in listOf(false, true)) withTar(tar("gnu", gz), gz) { src ->
            src.open(node(src, "Docs/deep/er/big.bin")).use { h ->
                val buf = ByteArray(4096)
                for (off in listOf(10_000_000L, 1_000_000L, big.size - 100L, 0L)) {
                    val n = h.read(off, buf, buf.size)
                    assertEquals(minOf(4096L, big.size - off).toInt(), n)
                    assertArrayEquals(big.copyOfRange(off.toInt(), off.toInt() + n), buf.copyOf(n))
                }
                assertEquals(0, h.read(big.size.toLong(), buf, buf.size))
            }
        }
    }

    @Test
    fun `unsafe names and links are left out, and a damaged header is refused`() {
        assumeTrue("python3 is not installed", has("python3"))
        val f = File.createTempFile("bad", ".tar").apply { deleteOnExit() }
        val script = """
import tarfile, io, sys
with tarfile.open(sys.argv[1], "w", format=tarfile.GNU_FORMAT) as t:
    for name, data in (("ok.txt", b"ok\n"), ("../evil.txt", b"x"), ("/abs.txt", b"x"), ("a/./b.txt", b"x")):
        i = tarfile.TarInfo(name); i.size = len(data); t.addfile(i, io.BytesIO(data))
    l = tarfile.TarInfo("link"); l.type = tarfile.SYMTYPE; l.linkname = "/etc/passwd"; t.addfile(l)
"""
        assertEquals(0, run(File("."), "python3", "-c", script, f.path))
        withTar(f, false) { src ->
            assertEquals(listOf("ok.txt"), src.children(src.root()).map { it.name })
            assertEquals(3, src.archive.skippedUnsafe)
            assertEquals(1, src.archive.skippedOther)
            assertNull(src.child(src.root(), "link"))
        }
        RandomAccessFile(f, "rw").use { it.seek(0); it.write('X'.code) }   // the first header's name, so its checksum fails
        assertThrows(IOException::class.java) { withTar(f, false) { } }
    }

    @Test
    fun `nothing can be written`() {
        withTar(tar("gnu", false), false) { src ->
            assertThrows(IOException::class.java) { src.createFile(src.root(), "x") }
            assertThrows(IOException::class.java) { src.open(node(src, "empty.txt")).write(0, ByteArray(1), 1) }
        }
    }
}

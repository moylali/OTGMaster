package app.fayaz.otgmaster.aescrypt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** The AES Crypt reader against files pyAesCrypt (run through uv) encrypted. */
class AesCryptSourceTest {

    private val password = "aes-crypt-test-pw"

    private fun encrypt(plain: ByteArray): File {
        assumeTrue("uv missing", ProcessBuilder("sh", "-c", "command -v uv").start().waitFor() == 0)
        val src = File.createTempFile("aesplain", ".bin").apply { deleteOnExit(); writeBytes(plain) }
        val out = File.createTempFile("Manual.pdf", ".aes").apply { delete(); deleteOnExit() }
        val p = ProcessBuilder("uv", "run", "--quiet", "--with", "pyAesCrypt", "python", "-c",
            "import pyAesCrypt,sys; pyAesCrypt.encryptFile(sys.argv[1], sys.argv[2], sys.argv[3])",
            src.path, out.path, password).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        assertEquals(log, 0, p.waitFor())
        return out
    }

    private fun <R> open(f: File, pw: String = password, block: (AesCryptSource) -> R): R =
        RandomAccessFile(f, "r").use { raf -> block(AesCryptSource.open(raf.channel, f.length(), pw.toCharArray(), "Manual.pdf.aes")) }

    @Test
    fun `every padding case reads back byte for byte`() {
        for (size in listOf(0, 1, 15, 16, 17, 1_000_003)) {
            val plain = ByteArray(size).also { java.util.Random(size.toLong()).nextBytes(it) }
            open(encrypt(plain)) { src ->
                val node = src.children(src.root()).single()
                assertEquals("Manual.pdf", node.name)
                assertEquals("size $size", size.toLong(), node.size)
                src.open(node).use { h ->
                    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(65_537); var off = 0L
                    while (true) { val n = h.read(off, buf, buf.size); if (n == 0) break; out.write(buf, 0, n); off += n }
                    assertArrayEquals("size $size", plain, out.toByteArray())
                }
            }
        }
    }

    @Test
    fun `random reads at any offset decrypt the right bytes`() {
        val plain = ByteArray(300_007).also { java.util.Random(9).nextBytes(it) }
        open(encrypt(plain)) { src ->
            src.open(src.children(src.root()).single()).use { h ->
                val buf = ByteArray(5000)
                for (off in listOf(299_000L, 0L, 17L, 15L, 16L, 150_001L, 300_006L, 7L)) {
                    val n = h.read(off, buf, 5000)
                    assertEquals("at $off", minOf(5000L, plain.size - off).toInt(), n)
                    assertArrayEquals("at $off", plain.copyOfRange(off.toInt(), off.toInt() + n), buf.copyOf(n))
                }
                assertEquals(0, h.read(plain.size.toLong(), buf, 10))
            }
        }
    }

    @Test
    fun `a wrong password is refused before anything is decrypted`() {
        val f = encrypt(ByteArray(1000))
        assertThrows(AesCryptSource.WrongPasswordException::class.java) { open(f, "not-it") { } }
    }

    @Test
    fun `an altered file serves nothing`() {
        val f = encrypt(ByteArray(100_000) { 7 })
        val bytes = f.readBytes(); bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte(); f.writeBytes(bytes)
        assertTrue(assertThrows(IOException::class.java) { open(f) { } }.message!!.contains("HMAC"))
    }

    @Test
    fun `a version 3 file is refused with its reason`() {
        val f = encrypt(ByteArray(100))
        val bytes = f.readBytes(); bytes[3] = 3; f.writeBytes(bytes)
        assertTrue(assertThrows(IOException::class.java) { open(f) { } }.message!!.contains("version 3"))
    }
}

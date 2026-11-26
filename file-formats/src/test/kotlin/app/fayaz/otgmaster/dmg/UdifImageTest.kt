package app.fayaz.otgmaster.dmg

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

/**
 * The UDIF reader against images scripts/make_udif.py writes (no Mac here), each checked first
 * with 7-Zip, which reads UDIF with code of its own: the image is valid before the reader is
 * judged by it.
 */
class UdifImageTest {

    private lateinit var dir: File
    private lateinit var disk: ByteArray

    private fun run(vararg cmd: String, cwd: File = if (::dir.isInitialized) dir else File(".")): Int =
        ProcessBuilder(*cmd).directory(cwd).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()

    private fun has(tool: String) = run("sh", "-c", "command -v $tool") == 0

    private val script: File get() = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "scripts/make_udif.py") }.first { it.exists() }

    @Before
    fun setUp() {
        assumeTrue("python3 is not installed", has("python3"))
        dir = Files.createTempDirectory("udif").toFile()
        val r = java.util.Random(5)
        // 9 MiB and three sectors: random data, a long zero run (zero-fill chunks), a pattern, and a short last chunk.
        disk = ByteArray(9 * 1024 * 1024 + 512 * 3)
        val rnd = ByteArray(3 * 1024 * 1024).also { r.nextBytes(it) }
        System.arraycopy(rnd, 0, disk, 0, rnd.size)
        for (i in 0 until 100_000) disk[5 * 1024 * 1024 + i] = "pattern!"[i % 8].code.toByte()
        for (i in disk.size - 1000 until disk.size) disk[i] = 0x5A
        File(dir, "in.img").writeBytes(disk)
    }

    private fun dmg(types: String): File {
        val out = File(dir, "out-${types.replace(',', '_')}.dmg")
        assertEquals(0, run("python3", script.path, "in.img", out.name, "--types", types, "--name", "disk image"))
        if (has("7z")) {
            val x = File(dir, "x-${out.name}").apply { mkdirs() }
            run("7z", "x", "-tdmg", "-y", out.path, cwd = x)
            assertArrayEquals("7-Zip does not read the fixture back as the disk", disk, x.listFiles()!!.single().readBytes())
        }
        return out
    }

    private fun <R> withImage(f: File, block: (UdifImage) -> R): R = RandomAccessFile(f, "r").use { block(UdifImage.open(it.channel)) }

    @Test
    fun `every chunk type reads back as the disk, whole and at odd offsets`() {
        for (types in listOf("zlib,raw,bzip2", "raw", "zlib", "bzip2")) withImage(dmg(types)) { img ->
            assertEquals(disk.size / 512L, img.blockCount)
            assertArrayEquals(types, disk, img.readBlocks(0, disk.size / 512))
            for ((s, n) in listOf(2047L to 3, 4095L to 2050, 10_240L to 1, img.blockCount - 2 to 2)) {
                assertArrayEquals("$types at $s", disk.copyOfRange((s * 512).toInt(), ((s + n) * 512).toInt()), img.readBlocks(s, n))
            }
            assertEquals(listOf("disk image"), img.partitions.map { it.name })
            assertEquals(0L, img.partitions.single().firstSector)
        }
    }

    @Test
    fun `a damaged zlib chunk is an error, not wrong bytes`() {
        val f = dmg("zlib")
        RandomAccessFile(f, "rw").use { it.seek(200); it.write(ByteArray(64) { 0x33 }) }   // inside the first chunk's compressed data
        withImage(f) { img -> assertThrows(IOException::class.java) { img.readBlocks(0, 8) } }
    }

    @Test
    fun `not an image, and an encrypted one, are refused with a reason`() {
        val plain = File(dir, "in.img")
        assertThrows(UdifImage.UdifFormatException::class.java) { withImage(plain) { } }
        val enc = File(dir, "enc.dmg").apply { writeBytes("encrcdsa".toByteArray() + ByteArray(4096)) }
        val e = assertThrows(UdifImage.UdifFormatException::class.java) { withImage(enc) { } }
        assert("encrypted" in e.message!!) { e.message!! }
    }

    @Test
    fun `nothing can be written`() {
        withImage(dmg("raw")) { img -> assertThrows(IOException::class.java) { img.writeBlocks(0, ByteArray(512)) } }
    }
}

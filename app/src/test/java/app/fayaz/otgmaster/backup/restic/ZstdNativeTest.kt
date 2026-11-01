package app.fayaz.otgmaster.backup.restic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.Random

/**
 * The native decoder against frames from the zstd tool. Bad input must throw: restic checks a
 * blob's hash but an index or snapshot has only the decoder between it and the JSON parser.
 */
class ZstdNativeTest {
    companion object {
        private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "scripts/build_host_native.sh").exists() }

        /** Builds and selects the host library. Also called by the tests that read compressed repositories. */
        @JvmStatic
        @BeforeClass
        fun loadHostLibrary() {
            val lib = File(repo, "app/build/host-native/libotg-host.so")
            val p = ProcessBuilder(File(repo, "scripts/build_host_native.sh").path, lib.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "host build of the native code failed:\n$out" }
            System.setProperty("otg.native.hostlib", lib.absolutePath)
        }
    }

    private val plain = ByteArray(3 shl 20).also { Random(2026).nextBytes(it) } + ByteArray(1 shl 20) { 'x'.code.toByte() }

    private fun zstdTool(data: ByteArray, vararg options: String): ByteArray {
        val tool = System.getenv("PATH").split(':').map { File(it, "zstd") }.firstOrNull { it.canExecute() }
        assumeTrue("the zstd tool is not installed", tool != null)
        val p = ProcessBuilder(tool!!.path, "-c", "-q", *options).start()
        val writer = Thread { p.outputStream.use { it.write(data) } }.also { it.start() }
        val out = p.inputStream.readBytes()
        writer.join()
        check(p.waitFor() == 0) { "zstd failed" }
        return out
    }

    private fun decode(frame: ByteArray, expected: Int = -1) = ZstdNative.decompress(frame, 0, frame.size, expected)

    @Test
    fun aFrameStatingItsSize() {
        val frame = zstdTool(plain, "--stream-size=${plain.size}")
        assertArrayEquals(plain, decode(frame))
        assertArrayEquals(plain, decode(frame, plain.size))
    }

    @Test
    fun aFrameNotStatingItsSizeIsDecodedInPieces() {
        assertArrayEquals(plain, decode(zstdTool(plain, "--no-content-size")))
    }

    @Test
    fun anOffsetIntoTheArray() {
        val frame = zstdTool(plain, "--no-content-size")
        val padded = byteArrayOf(2) + frame + byteArrayOf(9, 9)
        assertArrayEquals(plain, ZstdNative.decompress(padded, 1, frame.size, -1))
    }

    @Test
    fun framesOneAfterAnother() {
        val two = zstdTool(plain, "--no-content-size") + zstdTool(plain, "--no-content-size")
        assertArrayEquals(plain + plain, decode(two))
    }

    @Test
    fun aFrameCutShortThrows() {
        val frame = zstdTool(plain, "--no-content-size")
        assertThrows(IOException::class.java) { decode(frame.copyOf(frame.size / 2)) }
        assertThrows(IOException::class.java) { decode(frame.copyOf(frame.size / 2), plain.size) }
    }

    @Test
    fun anExpectedSizeThatIsWrongThrows() {
        val frame = zstdTool(plain)
        assertThrows(IOException::class.java) { decode(frame, plain.size - 1) }
        assertThrows(IOException::class.java) { decode(frame, plain.size + 1) }
    }

    @Test
    fun corruptDataThrows() {
        val frame = zstdTool(plain, "--check")
        frame[frame.size / 2] = (frame[frame.size / 2] + 1).toByte()
        assertThrows(IOException::class.java) { decode(frame) }
        assertThrows(IOException::class.java) { decode(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)) }
    }
}

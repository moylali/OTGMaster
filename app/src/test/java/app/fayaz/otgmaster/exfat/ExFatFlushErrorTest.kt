package app.fayaz.otgmaster.exfat

import app.fayaz.otgmaster.block.RawBlockDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * A metadata write that fails must reach the caller. Review finding 6, 2026-11-09: the
 * JNI flush dropped the results of exfat_flush_nodes() and exfat_flush() and returned
 * exfat_fsync(), which always returns 0, and ExFatFile.flush() ignored even that. So an
 * fsync() from Files, or the close at the end of a copy, reported success while the
 * directory entry or the allocation bitmap had not reached the drive.
 */
class ExFatFlushErrorTest {

    /** A loopback image whose writes can be made to fail, as a drive that went away. */
    private class FlakyDevice(private val file: File) : RawBlockDevice {
        private val raf = RandomAccessFile(file, "rw")
        @Volatile var failWrites = false
        override val blockSize = 512
        override val blockCount get() = file.length() / blockSize
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            val out = ByteArray(blockCount * blockSize)
            raf.seek(startBlock * blockSize); raf.readFully(out); return out
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) {
            if (failWrites) throw IOException("injected write failure")
            raf.seek(startBlock * blockSize); raf.write(data)
        }
        override fun close() = raf.close()
    }

    companion object {
        private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "scripts/build_host_native.sh").exists() }

        private fun run(vararg cmd: String): Pair<Int, String> {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            return p.waitFor() to out
        }

        private fun toolMissing(tool: String) = run("sh", "-c", "command -v $tool").first != 0

        private var hostLib: File? = null

        @BeforeClass
        @JvmStatic
        fun buildHostLibrary() {
            if (toolMissing("gcc") || toolMissing("g++")) return
            val lib = File(repo, "app/build/host-native/libotg-host.so")
            val (rc, out) = run(File(repo, "scripts/build_host_native.sh").path, lib.path)
            if (rc != 0) throw AssertionError("host build of the native code failed:\n$out")
            System.setProperty("otg.native.hostlib", lib.absolutePath)
            hostLib = lib
        }
    }

    private lateinit var img: File

    @Before
    fun setUp() {
        assumeTrue("no host C toolchain", hostLib != null)
        assumeTrue("mkfs.exfat not available", !toolMissing("mkfs.exfat"))
        img = File.createTempFile("exfatflush", ".img")
        RandomAccessFile(img, "rw").use { it.setLength(64L shl 20) }
        val (rc, out) = run("mkfs.exfat", "-L", "FLUSH", img.path)
        assertEquals("mkfs.exfat failed: $out", 0, rc)
    }

    @After
    fun tearDown() {
        if (::img.isInitialized) img.delete()
    }

    @Test
    fun `a flush whose metadata write fails throws, and succeeds once the drive is back`() {
        val dev = FlakyDevice(img)
        val ptr = ExFatNative.mount(dev, false)
        assertNotEquals("libexfat refused the image", 0L, ptr)
        val fs = ExFatFileSystem(dev, ptr)
        try {
            val f = fs.rootDirectory.createFile("data.bin")
            f.write(0, ByteBuffer.wrap(ByteArray(64 * 1024) { 7 }))
            // The data is on the drive; the directory entry with the new size is not.
            dev.failWrites = true
            try {
                f.flush()
                fail("flush reported success although every metadata write failed")
            } catch (e: IOException) {
                assertTrue(e.message ?: "", (e.message ?: "").contains("exFAT"))
            }
            // The node stayed dirty in libexfat, so a retry writes it.
            dev.failWrites = false
            f.flush()
            f.close()
        } finally {
            dev.failWrites = false
            fs.unmount()
            dev.close()
        }
        val (rc, out) = run("fsck.exfat", "-n", img.path)
        assertEquals("fsck.exfat after the retried flush:\n$out", 0, rc)
    }
}

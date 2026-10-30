package app.fayaz.otgmaster.exfat

import app.fayaz.otgmaster.block.RawBlockDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * libexfat's own reasons reach the app (docs/VENDOR_FIXES.md E3).
 *
 * They went to logcat only. Issue #98's read-write mount failed as "Failed to mount
 * exFAT: null" with the reason left on the phone, so nothing in the report said why.
 * ExFatNative.takeMessages() is what ExFatFileSystemCreator and every libexfat call
 * now drain into the app's log.
 */
class ExFatMessagesTest {

    private class ImageDevice(file: File) : RawBlockDevice {
        private val raf = RandomAccessFile(file, "rw")
        override val blockSize = 512
        override val blockCount = file.length() / blockSize
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            val out = ByteArray(blockCount * blockSize)
            raf.seek(startBlock * blockSize); raf.readFully(out); return out
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) {
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
        img = File.createTempFile("exfatmsgs", ".img")
        RandomAccessFile(img, "rw").use { it.setLength(64L shl 20) }
        val (rc, out) = run("mkfs.exfat", "-L", "MSGTEST", img.path)
        assertEquals("mkfs.exfat failed: $out", 0, rc)
        ExFatNative.takeMessages()
    }

    @After
    fun tearDown() {
        if (::img.isInitialized) img.delete()
    }

    private fun poke(offset: Long, value: Int) = RandomAccessFile(img, "rw").use { it.seek(offset); it.write(value) }

    @Test
    fun `a clean volume mounts read-write with nothing to say`() {
        val dev = ImageDevice(img)
        val ptr = ExFatNative.mount(dev, false)
        assertNotEquals(0L, ptr)
        assertNull(ExFatNative.takeMessages())
        ExFatNative.unmount(ptr); dev.close()
    }

    @Test
    fun `a refused mount says why, and the buffer is empty after`() {
        // FileSystemRevision (0x68) 2.0: libexfat supports 1.0 only. The VBR checksum
        // covers that byte, so libexfat reports the checksum first; either is the reason.
        poke(0x69, 2)
        val dev = ImageDevice(img)
        assertEquals(0L, ExFatNative.mount(dev, false))
        dev.close()
        val text = ExFatNative.takeMessages()
        assertTrue("got: $text", text != null && text.startsWith("ERROR: ") &&
            ("VBR checksum" in text || "unsupported exFAT version" in text))
        assertNull("taken once", ExFatNative.takeMessages())
    }

    @Test
    fun `a warning on a volume that does mount is kept too`() {
        // VolumeFlags (0x6A) bit 1, VolumeDirty: excluded from the VBR checksum, as
        // Windows sets it on a volume unplugged without "Safely remove".
        poke(0x6A, 0x02)
        val dev = ImageDevice(img)
        val ptr = ExFatNative.mount(dev, false)
        assertNotEquals(0L, ptr)
        val text = ExFatNative.takeMessages()
        assertTrue("got: $text", text != null && "WARN: volume was not unmounted cleanly" in text)
        ExFatNative.unmount(ptr); dev.close()
    }
}

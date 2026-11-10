package app.fayaz.otgmaster.provider

import app.fayaz.otgmaster.MountedDrive
import app.fayaz.otgmaster.OtgMasterState
import me.jahnen.libaums.core.fs.AbstractUsbFile
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Review finding 8 (2026-11-09): openDocument() resolved the file, truncated it and
 * registered its writer outside the drive lock, so a "wt" open freed clusters while a
 * write on the same FAT32 volume held the lock, and a delete could miss an open that
 * had already resolved the file.
 */
class ProviderOpenLockTest {

    private class FakeFile : AbstractUsbFile() {
        val truncated = AtomicBoolean(false)
        var closed = false
        var writes = 0
        override val isDirectory = false
        override var name = "a.bin"
        override val parent: UsbFile? = null
        override var length: Long = 4096
            set(v) { if (v == 0L) truncated.set(true); field = v }
        override val isRoot = false
        override fun createdAt() = 0L
        override fun lastModified() = 0L
        override fun lastAccessed() = 0L
        override fun list(): Array<String> = emptyArray()
        override fun listFiles(): Array<UsbFile> = emptyArray()
        override fun read(offset: Long, destination: ByteBuffer) {}
        override fun write(offset: Long, source: ByteBuffer) { writes++ }
        override fun flush() {}
        override fun close() { closed = true }
        override fun createDirectory(name: String): UsbFile = throw UnsupportedOperationException()
        override fun createFile(name: String): UsbFile = throw UnsupportedOperationException()
        override fun moveTo(destination: UsbFile) {}
        override fun delete() {}
    }

    @Test
    fun `a truncating open waits for the drive lock, then registers its writer`() {
        val doc = "lockdrive:/a.bin"
        val file = FakeFile()
        val resolvedWhileHeld = AtomicBoolean(false)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holding = AtomicBoolean(false)

        // Stands in for a write on the proxy thread, which holds the drive's lock.
        val writer = Thread {
            VeraCryptDocumentProvider.onDrive(doc, "write") {
                holding.set(true)
                held.countDown()
                release.await(5, TimeUnit.SECONDS)
                holding.set(false)
            }
        }
        writer.start()
        assertTrue(held.await(5, TimeUnit.SECONDS))

        val opener = Thread {
            VeraCryptDocumentProvider.openUnderLock(doc, write = true, truncate = true) {
                if (holding.get()) resolvedWhileHeld.set(true)
                file
            }
        }
        opener.start()
        Thread.sleep(300)
        assertFalse("the open truncated the file while another thread held the drive lock",
            file.truncated.get())
        assertTrue("the open did not wait for the lock", opener.isAlive)

        release.countDown()
        writer.join(5_000)
        opener.join(5_000)
        assertFalse("the file was resolved while the lock was held", resolvedWhileHeld.get())
        assertTrue("mode t must truncate", file.truncated.get())
        assertEquals(0L, file.length)
        // The writer is registered, so a delete under the same lock sees it.
        assertFalse("the writer was not registered", VeraCryptDocumentProvider.awaitWriters(doc, waitMs = 0))
    }

    /** A filesystem the handle code only compares by identity. */
    private fun fs(): FileSystem = java.lang.reflect.Proxy.newProxyInstance(
        FileSystem::class.java.classLoader, arrayOf(FileSystem::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            else -> throw AssertionError("file system touched: ${method.name}")
        }
    } as FileSystem

    private fun mount(id: String, fs: FileSystem) =
        OtgMasterState.addDrive(MountedDrive(id = id, name = id, fileSystem = fs, blockDevice = null))

    /**
     * Review finding 7: after Format replaced a partition's filesystem, a descriptor
     * opened before it kept writing through the old one, and its close wrote the old
     * directory table over the new.
     */
    @Test
    fun `a handle from before Format can neither write nor close onto the new filesystem`() {
        val id = "fmtdrive"
        val old = fs()
        mount(id, old)
        try {
            val file = FakeFile()
            val handle = VeraCryptDocumentProvider.openUnderLock("$id:/a.bin", write = true, truncate = false) { file }
            handle.use("write") { it.write(0, ByteBuffer.allocate(1)) }
            assertEquals(1, file.writes)

            // What formatDrive does: retire, drop the drive, mount the new filesystem.
            VeraCryptDocumentProvider.retire(old)
            OtgMasterState.removeDrive(id)
            mount(id, fs())

            try {
                handle.use("write") { it.write(0, ByteBuffer.allocate(1)) }
                fail("a write through the old filesystem reached the reformatted partition")
            } catch (e: java.io.IOException) {
                assertTrue(e.message ?: "", (e.message ?: "").contains("formatted"))
            }
            assertEquals(1, file.writes)
            handle.release()
            assertFalse("the close wrote the old filesystem's metadata onto the new one", file.closed)
        } finally {
            OtgMasterState.removeDrive(id)
            VeraCryptDocumentProvider.awaitWriters("$id:/a.bin", waitMs = 0)
        }
    }

    /** A plain unmount still lets the late close write the file's size, as before. */
    @Test
    fun `after a plain unmount writes fail but the close still runs`() {
        val id = "umdrive"
        mount(id, fs())
        try {
            val file = FakeFile()
            val handle = VeraCryptDocumentProvider.openUnderLock("$id:/b.bin", write = true, truncate = false) { file }
            OtgMasterState.removeDrive(id)
            try {
                handle.use("write") { it.write(0, ByteBuffer.allocate(1)) }
                fail("a write after unmount was accepted")
            } catch (e: java.io.IOException) { /* expected */ }
            handle.release()
            assertTrue(file.closed)
        } finally {
            OtgMasterState.removeDrive(id)
        }
    }
}

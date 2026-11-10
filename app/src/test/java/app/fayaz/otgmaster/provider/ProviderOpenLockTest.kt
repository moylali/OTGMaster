package app.fayaz.otgmaster.provider

import me.jahnen.libaums.core.fs.AbstractUsbFile
import me.jahnen.libaums.core.fs.UsbFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        override fun write(offset: Long, source: ByteBuffer) {}
        override fun flush() {}
        override fun close() {}
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
}

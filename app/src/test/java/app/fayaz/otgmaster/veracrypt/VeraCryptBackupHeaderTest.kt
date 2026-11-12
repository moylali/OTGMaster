package app.fayaz.otgmaster.veracrypt

import app.fayaz.otgmaster.veracrypt.VeraCryptImages.ImageDevice
import app.fayaz.otgmaster.veracrypt.VeraCryptImages.candidate
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Review of 2026-11-11, V05: only the main header was ever read, so a volume whose main
 * header was damaged looked like a wrong password although VeraCrypt keeps a backup in the
 * last 128 KiB. The backup is read only when asked (the owner's choice: each header costs
 * a key derivation), on a volume desktop VeraCrypt made.
 */
class VeraCryptBackupHeaderTest {
    private val password = "backup-header-password-2026"
    private val images = mutableListOf<File>()
    @After fun tearDown() { images.forEach { it.delete() } }

    private fun volume(): File {
        assumeTrue("needs gcc and desktop VeraCrypt", VeraCryptImages.ready)
        return File.createTempFile("vcbackup", ".img").also { it.delete(); images += it }
            .also { VeraCryptImages.create(it, "8M", password) }
    }

    private fun unlock(f: File, backup: Boolean): NativeDecryptedBlockDevice {
        val dev = ImageDevice(f)
        return VeraCryptUnlocker().unlock(dev, candidate(dev), password.toCharArray(), pim = 1, useBackupHeader = backup)
    }

    @Test fun `with the main header damaged, the backup header opens the same data`() {
        val f = volume()
        val pattern = ByteArray(512 * 8) { (it * 13 + 1).toByte() }
        unlock(f, backup = false).apply { writeBlocks(100, pattern); close() }

        VeraCryptImages.clobber(f, 0, 512)                 // the main standard header
        try {
            unlock(f, backup = false).close()
            fail("a damaged main header opened")
        } catch (_: IllegalArgumentException) { /* reads as a wrong password, as VeraCrypt's does */ }

        val v = unlock(f, backup = true)
        assertArrayEquals("the backup header opened different data", pattern, v.readBlocks(100, 8))
        v.close()
    }

    @Test fun `the backup header option still needs the right password`() {
        val f = volume()
        val dev = ImageDevice(f)
        try {
            VeraCryptUnlocker().unlock(dev, candidate(dev), "wrong".toCharArray(), pim = 1, useBackupHeader = true).close()
            fail("a wrong password opened the backup header")
        } catch (_: IllegalArgumentException) {}
    }
}

package app.fayaz.otgmaster.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NoSpaceTest {
    @Test
    fun `every filesystem's way of saying full is recognised`() {
        assertTrue(NoSpace.isNoSpace(NoSpaceException("Ext4: out of usable blocks")))
        // libaums' FAT32 allocator, vendored, keeps its own message.
        assertTrue(NoSpace.isNoSpace(IOException("FAT32 volume is full: 3 more cluster(s) needed")))
        // Wrapped, as the provider's proxy callbacks and the copy flows rethrow it.
        assertTrue(NoSpace.isNoSpace(RuntimeException("copy failed", NoSpaceException("full"))))
        assertFalse(NoSpace.isNoSpace(IOException("MAX_RECOVERY_ATTEMPTS Exceeded")))
        assertFalse(NoSpace.isNoSpace(null))
    }

    @Test
    fun `only ENOSPC becomes a NoSpaceException`() {
        assertEquals("exFAT: full", NoSpace.fromErrno(-28, "exFAT: full")!!.message)
        assertNull(NoSpace.fromErrno(-5, "EIO"))
    }

    @Test
    fun `20,000 small files on 32 KiB clusters need far more than their bytes`() {
        // The 0.5.3 D5 p7 case in outline: 3 KB files on a 2 GiB exFAT partition.
        val need = NoSpace.neededOnVolume(List(20_000) { 3_000L }, folders = 1, cluster = 32 * 1024)
        assertEquals(20_001L * 32 * 1024, need)
        assertTrue(need > 10 * 20_000L * 3_000L)
    }
}

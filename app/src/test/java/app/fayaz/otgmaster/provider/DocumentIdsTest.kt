package app.fayaz.otgmaster.provider

import app.fayaz.otgmaster.fs.NoSpaceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class DocumentIdsTest {

    private fun id(s: String) = DocumentIds.parse(s)!!

    @Test fun `shipped IDs parse to the same drive and path`() {
        assertEquals("/", id("abc123:/").path)
        assertEquals("/", id("abc123:").path)
        assertEquals("/Photos/a.jpg", id("abc123:/Photos/a.jpg").path)
        assertEquals("/Photos", id("abc123:/Photos/").path)
        assertEquals("/Photos", id("abc123:Photos").path)
        assertEquals("abc123-2", id("abc123-2:/x").driveId)
        assertEquals("abc123:/Photos/a.jpg", id("abc123:/Photos/a.jpg").toString())
        assertEquals("abc123:/", id("abc123:/").toString())
    }

    @Test fun `IDs that are not canonical are refused`() {
        for (bad in listOf(null, "", ":/x", "nodrive", "d:/a//b", "d:/a/./b", "d:/a/../b", "d:/..",
                "d:/.", "d:/a\u0000b", "d/x:/a")) {
            assertNull("accepted ${bad?.replace("\u0000", "\\0")}", DocumentIds.parse(bad))
        }
    }

    @Test fun `ancestry compares whole components on the same drive`() {
        val photos = id("d:/Photos")
        assertTrue(DocumentIds.isSameOrUnder(photos, id("d:/Photos")))
        assertTrue(DocumentIds.isSameOrUnder(photos, id("d:/Photos/2024/a.jpg")))
        assertTrue(DocumentIds.isSameOrUnder(id("d:/"), id("d:/Photos")))
        assertFalse(DocumentIds.isSameOrUnder(photos, id("d:/Photos2/a.jpg")))
        assertFalse(DocumentIds.isSameOrUnder(photos, id("d:/")))
        assertFalse(DocumentIds.isSameOrUnder(photos, id("e:/Photos/a.jpg")))
        // The string-prefix test this replaced counted this as inside /Photos.
        assertNull(DocumentIds.parse("d:/Photos/../secret"))
    }

    @Test fun `the chain runs from the root down to the document`() {
        assertEquals(listOf("d:/", "d:/a", "d:/a/b"), DocumentIds.chain(id("d:/a/b")).map { it.toString() })
        assertEquals(listOf("d:/"), DocumentIds.chain(id("d:/")).map { it.toString() })
        assertEquals("d:/a/b c", DocumentIds.child(id("d:/a"), "b c").toString())
        assertEquals("d:/x", DocumentIds.child(id("d:/"), "x").toString())
    }

    @Test fun `names that cannot be a single entry are refused`() {
        for (bad in listOf(null, "", ".", "..", "a/b", "/", "a\u0000")) {
            assertTrue("accepted $bad", DocumentNames.problem(bad) != null)
        }
        for (good in listOf("a", "a b.txt", ".hidden", "..x", "x..", "日本語.pdf", "a\\b")) {
            assertNull("refused $good", DocumentNames.problem(good))
        }
    }

    @Test fun `failures reach the client as the errno that describes them`() {
        assertEquals(ProxyErrors.ENOSPC, ProxyErrors.errnoFor(NoSpaceException("Not enough space on D")))
        assertEquals(ProxyErrors.ENOSPC, ProxyErrors.errnoFor(IOException("write failed", NoSpaceException("full"))))
        assertEquals(ProxyErrors.EROFS, ProxyErrors.errnoFor(IOException("ext4 volume is read-only: journal needs recovery")))
        assertEquals(ProxyErrors.EROFS, ProxyErrors.errnoFor(IOException("exFAT volume is mounted read-only")))
        assertEquals(ProxyErrors.ENOENT, ProxyErrors.errnoFor(FileNotFoundException("gone")))
        assertEquals(ProxyErrors.EIO, ProxyErrors.errnoFor(IOException(
            "The drive this file was opened on was unmounted, formatted or encrypted since")))
        assertEquals(ProxyErrors.EIO, ProxyErrors.errnoFor(IllegalStateException("anything else")))
    }

    @Test fun `an ID-only root query does not ask for free space`() {
        assertFalse(VeraCryptDocumentProvider.wantsAvailableBytes(arrayOf("root_id")))
        assertFalse(VeraCryptDocumentProvider.wantsAvailableBytes(arrayOf("root_id", "title", "flags")))
        assertTrue(VeraCryptDocumentProvider.wantsAvailableBytes(arrayOf("root_id", "available_bytes")))
        assertTrue(VeraCryptDocumentProvider.wantsAvailableBytes(null))
    }
}

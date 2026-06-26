package app.fayaz.otgmaster.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class VeraCryptDocumentProviderTest {
    @Test
    fun testRootIdForDrive() {
        val driveId = "drive123"
        assertEquals("root_drive123", VeraCryptDocumentProvider.rootIdForDrive(driveId))
        assertEquals("drive123:/", VeraCryptDocumentProvider.rootDocIdForDrive(driveId))
    }
}

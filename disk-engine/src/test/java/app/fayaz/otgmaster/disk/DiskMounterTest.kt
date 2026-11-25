package app.fayaz.otgmaster.disk

import org.junit.Assert.assertEquals
import org.junit.Test

class DiskMounterTest {

    /**
     * MainActivity registered the four drivers on every onCreate, and libaums keeps every
     * registration, so a recreated activity left duplicates that probed a volume again.
     */
    @Test
    fun `the drivers are registered once, however often it is asked`() {
        repeat(5) { DiskMounter.ensureRegistered() }
        assertEquals(4, DiskMounter.registrations)
    }
}

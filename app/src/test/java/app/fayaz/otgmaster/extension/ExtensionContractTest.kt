package app.fayaz.otgmaster.extension

import app.fayaz.otgmaster.veracrypt.VeraCryptVolumeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtensionContractTest {

    @Test
    fun driveIdIsStableAcrossMounts() {
        val a = ExtensionContract.stableDriveId("2385:5734:ABC123", 2048, emptyList())
        val b = ExtensionContract.stableDriveId("2385:5734:ABC123", 2048, emptyList())
        assertEquals(a, b)
        assertEquals(10, a.length)
    }

    @Test
    fun partitionsOfOneDeviceGetDistinctIds() {
        val p1 = ExtensionContract.stableDriveId("2385:5734:ABC123", 2048, emptyList())
        val p2 = ExtensionContract.stableDriveId("2385:5734:ABC123", 1050624, emptyList())
        assertNotEquals(p1, p2)
    }

    /**
     * Review finding 1, 2026-11-09: the outer and hidden volume of one partition got the
     * same id, so a folder grant on the outer volume opened the hidden one's files.
     */
    @Test
    fun aHiddenVolumeDoesNotShareTheOuterVolumesId() {
        val key = "2385:5734:ABC123"
        val outer = ExtensionContract.veraCryptDriveId(key, 2048, VeraCryptVolumeType.OUTER, emptyList())
        val normal = ExtensionContract.veraCryptDriveId(key, 2048, VeraCryptVolumeType.NORMAL, emptyList())
        val hidden = ExtensionContract.veraCryptDriveId(key, 2048, VeraCryptVolumeType.HIDDEN, emptyList())
        assertNotEquals("the hidden volume reused the outer volume's id", outer, hidden)
        assertNotEquals(normal, hidden)
        // Outer and normal volumes keep the id they always had, so grants on them survive.
        assertEquals(ExtensionContract.stableDriveId(key, 2048, emptyList()), outer)
        assertEquals(outer, normal)
        // And the hidden volume's id is stable across mounts, as grants need.
        assertEquals(hidden, ExtensionContract.veraCryptDriveId(key, 2048, VeraCryptVolumeType.HIDDEN, emptyList()))
    }

    @Test
    fun takenIdIsNeverReused() {
        val base = ExtensionContract.stableDriveId("k", 0, emptyList())
        val second = ExtensionContract.stableDriveId("k", 0, listOf(base))
        assertEquals("$base-2", second)
    }

    @Test
    fun deviceKeyParses() {
        val k = ExtensionContract.parseDeviceKey("2385:5734:ABC123")
        assertEquals(2385, k.vendorId); assertEquals(5734, k.productId); assertEquals("ABC123", k.serial)
        assertNull(ExtensionContract.parseDeviceKey("1:2:/dev/bus/usb/001/004").serial)
    }
}

package app.fayaz.otgmaster.extension

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

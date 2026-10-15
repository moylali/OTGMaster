package app.fayaz.otgmaster

import me.jahnen.libaums.core.fs.FileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The disconnected-card bookkeeping (issue #50): a dropped partition leaves a card, the same
 * partition mounting again clears it, and its history survives that.
 */
class DisconnectStateTest {

    // Never called: the bookkeeping must not touch the file system of a drive that is gone.
    private val deadFs = java.lang.reflect.Proxy.newProxyInstance(
        FileSystem::class.java.classLoader, arrayOf(FileSystem::class.java)
    ) { _, method, _ -> throw AssertionError("file system touched: ${method.name}") } as FileSystem

    private fun drive(id: String, serialKey: String, start: Long?) = MountedDrive(
        id = id, name = "PNY", fileSystem = deadFs, blockDevice = null,
        sourceDeviceName = serialKey, partitionLabel = "Partition 2", partitionStartBlock = start,
    )

    private fun gone(d: MountedDrive, at: Long) = DisconnectedDrive(
        partitionKey = d.partitionKey, title = "PNY · Partition 2", filesystemName = "exFAT",
        containerType = null, event = DisconnectEvent(at, batteryPercent = 33, externalPower = false),
    )

    @Before
    fun reset() {
        OtgMasterState.disconnectedDrives.value = emptyList()
        OtgMasterState.disconnectHistory.value = emptyMap()
    }

    @Test
    fun remountOfTheSamePartitionClearsTheCardButKeepsTheHistory() {
        val before = drive("vc-1", "5404:4199:071C48F5FB0EF449", 2048)
        OtgMasterState.recordDisconnect(gone(before, 1_000))

        // A fresh mount gets a new id; the partition key must still match.
        val after = drive("vc-7", "5404:4199:071C48F5FB0EF449", 2048)
        OtgMasterState.clearDisconnectedFor(listOf(after))

        assertTrue(OtgMasterState.disconnectedDrives.value.isEmpty())
        assertEquals(listOf(1_000L), OtgMasterState.disconnectHistory.value[after.partitionKey]?.map { it.atMillis })
    }

    @Test
    fun anotherStickOfTheSameModelDoesNotClearIt() {
        // Drive 5's sticks share vendor:product and differ only by serial.
        val a = drive("a", "5404:4199:071C48F5FB0EF449", 2048)
        val b = drive("b", "5404:4199:071C48F5FB0EF450", 2048)
        assertNotEquals(a.partitionKey, b.partitionKey)
        OtgMasterState.recordDisconnect(gone(a, 1_000))
        OtgMasterState.clearDisconnectedFor(listOf(b))
        assertEquals(1, OtgMasterState.disconnectedDrives.value.size)
    }

    @Test
    fun anotherPartitionOfTheSameStickDoesNotClearIt() {
        val p2 = drive("a", "5404:4199:071C48F5FB0EF449", 2048)
        val p3 = drive("b", "5404:4199:071C48F5FB0EF449", 16_779_264)
        OtgMasterState.recordDisconnect(gone(p2, 1_000))
        OtgMasterState.clearDisconnectedFor(listOf(p3))
        assertEquals(1, OtgMasterState.disconnectedDrives.value.size)
    }

    @Test
    fun repeatedDropsKeepOneCardAndListNewestFirstCapped() {
        val d = drive("a", "5404:4199:071C48F5FB0EF449", 2048)
        (1..12).forEach { OtgMasterState.recordDisconnect(gone(d, it * 1_000L)) }
        assertEquals(1, OtgMasterState.disconnectedDrives.value.size)
        assertEquals(12_000L, OtgMasterState.disconnectedDrives.value.single().event.atMillis)
        val history = OtgMasterState.disconnectHistory.value.getValue(d.partitionKey)
        assertEquals(10, history.size)
        assertEquals((12 downTo 3).map { it * 1_000L }, history.map { it.atMillis })
    }

    @Test
    fun dismissRemovesTheCardButNotTheHistory() {
        val d = drive("a", "5404:4199:071C48F5FB0EF449", 2048)
        OtgMasterState.recordDisconnect(gone(d, 1_000))
        OtgMasterState.dismissDisconnected(d.partitionKey)
        assertTrue(OtgMasterState.disconnectedDrives.value.isEmpty())
        assertEquals(1, OtgMasterState.disconnectHistory.value.getValue(d.partitionKey).size)
    }
}

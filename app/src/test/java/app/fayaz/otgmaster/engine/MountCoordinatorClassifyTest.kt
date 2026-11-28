package app.fayaz.otgmaster.engine

import app.fayaz.otgmaster.PlainPartition
import app.fayaz.otgmaster.UsbDeviceCandidate
import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.veracrypt.ContainerType
import app.fayaz.otgmaster.veracrypt.VolumeCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What happens to newly probed drives (2b step 2, slice 3): the split moved from MainActivity's
 * openAndProbeUsb to the engine, and the form's order and hidden counts must not change with it.
 */
class MountCoordinatorClassifyTest {
    private object NoDevice : RawBlockDevice {
        override val blockSize = 512
        override val blockCount = 0L
        override fun readBlocks(startBlock: Long, blockCount: Int) = ByteArray(0)
        override fun writeBlocks(startBlock: Long, data: ByteArray) {}
        override fun close() {}
    }

    private fun locked(start: Long, apfs: Int = 0) = VolumeCandidate("p$start", start, 100, ContainerType.LUKS2, apfs)
    private fun plain(start: Long) = PlainPartition("p$start", start, 100, "FAT32")
    private fun drive(name: String, candidates: List<VolumeCandidate>, plains: List<PlainPartition> = emptyList(), hidden: Int = 0) =
        UsbDeviceCandidate(name, name, NoDevice, candidates, plains, hidden)

    @Test
    fun `a drive of plain partitions only mounts them and is not offered`() {
        val d = drive("plain", emptyList(), listOf(plain(2048), plain(4096)))
        val c = MountCoordinator.classify(listOf(d))
        assertEquals(listOf(d), c.withPlain)
        assertEquals(emptyList<UsbDeviceCandidate>(), c.encrypted)
    }

    @Test
    fun `a mixed drive mounts its plain partitions and offers the rest, counting the plain ones as hidden`() {
        // An APFS volume inside a container at a plain start is not the plain partition.
        val d = drive("mixed", listOf(locked(2048), locked(4096), locked(2048, apfs = 1)), listOf(plain(2048)), hidden = 1)
        val c = MountCoordinator.classify(listOf(d))
        assertEquals(listOf(d), c.withPlain)
        assertEquals(listOf(locked(4096), locked(2048, apfs = 1)), c.encrypted.single().candidates)
        assertEquals(2, c.encrypted.single().hiddenPartitions)
    }

    @Test
    fun `drives with nothing plain come before mixed ones on the form`() {
        val mixed = drive("mixed", listOf(locked(2048), locked(4096)), listOf(plain(2048)))
        val enc = drive("enc", listOf(locked(2048)))
        val c = MountCoordinator.classify(listOf(mixed, enc))
        assertEquals(listOf("enc", "mixed"), c.encrypted.map { it.deviceName })
        assertEquals(listOf("mixed"), c.withPlain.map { it.deviceName })
    }
}

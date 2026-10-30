package app.fayaz.otgmaster.fs

import app.fayaz.otgmaster.veracrypt.ContainerType
import app.fayaz.otgmaster.veracrypt.VolumeCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

class SkippedPartitionsTest {

    private val size: (Long) -> String = { "${it / (1024 * 1024)} MiB" }

    @Test
    fun `each partition neither mounted nor offered gets one line saying why`() {
        val candidates = listOf(
            VolumeCandidate("Partition 1", 2048, 2048, ContainerType.UNENCRYPTED),   // mounted
            VolumeCandidate("Partition 2", 4096, 4096, ContainerType.UNENCRYPTED),   // unsupported fs
            VolumeCandidate("Partition 3", 8192, 2048, ContainerType.UNENCRYPTED),   // nothing recognisable
            VolumeCandidate("Partition 4", 10240, 2048, ContainerType.VERACRYPT),    // offered in the form
            VolumeCandidate("Partition 5", 12288, null, ContainerType.UNKNOWN),      // unreadable
        )
        val fs = mapOf(
            4096L to DetectedFilesystem.Unsupported("HFS+", "no driver for it"),
            8192L to DetectedFilesystem.Unknown,
        )
        val lines = SkippedPartitions.lines(candidates, setOf(2048L), 512, size) { fs.getValue(it.startBlock) }
        assertEquals(
            listOf(
                "Partition 2 (2 MiB): not mounted, HFS+ is not supported (no driver for it)",
                "Partition 3 (1 MiB): not mounted, no filesystem the app recognises",
                "Partition 5: not offered, it could not be read or identified",
            ),
            lines,
        )
    }

    @Test
    fun `nothing is logged when every partition is mounted or offered`() {
        val candidates = listOf(
            VolumeCandidate("Partition 1", 2048, 2048, ContainerType.UNENCRYPTED),
            VolumeCandidate("Partition 2", 4096, 2048, ContainerType.LUKS2),
        )
        val lines = SkippedPartitions.lines(candidates, setOf(2048L), 512, size) { error("not read: ${it.label}") }
        assertEquals(emptyList<String>(), lines)
    }
}

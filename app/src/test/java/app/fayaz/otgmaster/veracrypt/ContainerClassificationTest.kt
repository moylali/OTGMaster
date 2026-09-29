package app.fayaz.otgmaster.veracrypt

import app.fayaz.otgmaster.block.RawBlockDevice
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

/**
 * `classifyCandidate` used to return VERACRYPT for anything that was not LUKS.
 * A VeraCrypt header is indistinguishable from random data without the password,
 * so that guess is unavoidable for an *unidentifiable* volume — but it was also
 * applied to plain partitions whose first sector says plainly what they are.
 *
 * The visible symptom on drive D: its NTFS partition appeared in the unlock
 * picker tagged VERACRYPT, and the benchmark's auto-mount ran 16,000 PBKDF2
 * iterations against the string "NTFS    " before reporting a failed unlock.
 */
class ContainerClassificationTest {

    private class MemoryDevice(private val data: ByteArray) : RawBlockDevice {
        override val blockSize = 512
        override val blockCount = (data.size / 512).toLong()
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            val off = (startBlock * 512).toInt()
            return data.copyOfRange(off, off + blockCount * 512)
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) = throw UnsupportedOperationException()
        override fun close() {}
    }

    /** Partition starts, in sectors — one per fixture below. */
    private val ntfsAt = 2048L
    private val ext4At = 4096L
    private val luks1At = 6144L
    private val randomAt = 8192L

    private fun buildDevice(): MemoryDevice {
        val disk = ByteArray(10240 * 512)

        // MBR: four primary entries, 2048 sectors each.
        fun entry(index: Int, type: Int, firstLba: Long) {
            val o = 446 + index * 16
            disk[o + 4] = type.toByte()
            for (b in 0..3) disk[o + 8 + b] = ((firstLba shr (8 * b)) and 0xFF).toByte()
            for (b in 0..3) disk[o + 12 + b] = ((2048L shr (8 * b)) and 0xFF).toByte()
        }
        entry(0, 0x07, ntfsAt)
        entry(1, 0x83, ext4At)
        entry(2, 0x83, luks1At)
        entry(3, 0x83, randomAt)
        disk[510] = 0x55.toByte()
        disk[511] = 0xAA.toByte()

        // NTFS: jump instruction then the OEM name at byte 3, boot signature at 510.
        val n = (ntfsAt * 512).toInt()
        disk[n] = 0xEB.toByte(); disk[n + 1] = 0x52; disk[n + 2] = 0x90.toByte()
        "NTFS    ".toByteArray(Charsets.US_ASCII).copyInto(disk, n + 3)
        disk[n + 510] = 0x55.toByte(); disk[n + 511] = 0xAA.toByte()

        // ext4: superblock magic 0xEF53 at byte 1080, EXTENTS incompat flag at 1120.
        // This is the case that needs four sectors read rather than one.
        val e = (ext4At * 512).toInt()
        disk[e + 1080] = 0x53; disk[e + 1081] = 0xEF.toByte()
        disk[e + 1120] = 0x40

        // LUKS1: "LUKS" 0xBA 0xBE, then the version as a big-endian u16.
        val l = (luks1At * 512).toInt()
        "LUKS".toByteArray(Charsets.US_ASCII).copyInto(disk, l)
        disk[l + 4] = 0xBA.toByte(); disk[l + 5] = 0xBE.toByte()
        disk[l + 6] = 0x00; disk[l + 7] = 0x01

        // Unidentifiable: what a real VeraCrypt header looks like from outside.
        val r = (randomAt * 512).toInt()
        val rnd = ByteArray(2048)
        Random(42).nextBytes(rnd)
        rnd.copyInto(disk, r)

        return MemoryDevice(disk)
    }

    private fun typeAt(startBlock: Long): ContainerType =
        VeraCryptUnlocker().probeCandidates(buildDevice())
            .single { it.startBlock == startBlock }
            .containerType

    @Test
    fun anNtfsPartitionIsNotClassifiedAsVeraCrypt() {
        assertEquals(ContainerType.UNENCRYPTED, typeAt(ntfsAt))
    }

    @Test
    fun anExt4PartitionIsNotClassifiedAsVeraCrypt() {
        // Regression guard for the read length: the ext4 magic is at byte 1080,
        // so a one-sector probe cannot see it and falls through to VERACRYPT.
        assertEquals(ContainerType.UNENCRYPTED, typeAt(ext4At))
    }

    @Test
    fun aLuks1PartitionIsStillDetected() {
        assertEquals(ContainerType.LUKS1, typeAt(luks1At))
    }

    @Test
    fun anUnidentifiablePartitionRemainsTheVeraCryptGuess() {
        // Random data is exactly what an encrypted VeraCrypt header looks like,
        // so VERACRYPT stays the residual answer. Narrowing this to UNKNOWN
        // would make every real VeraCrypt volume unopenable.
        assertEquals(ContainerType.VERACRYPT, typeAt(randomAt))
    }

    @Test
    fun allFourPartitionsAreStillEnumerated() {
        assertEquals(4, VeraCryptUnlocker().probeCandidates(buildDevice()).size)
    }
}

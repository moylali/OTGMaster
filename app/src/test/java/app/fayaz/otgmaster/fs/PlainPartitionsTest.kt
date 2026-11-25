package app.fayaz.otgmaster.fs

import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.veracrypt.ContainerType
import app.fayaz.otgmaster.veracrypt.VeraCryptUnlocker
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * A BitLocker To Go drive must be offered for unlocking, not mounted as plain FAT32.
 *
 * Issue #184 (OPPO PJY110, 0.5.3): a BitLocker drive logged "Mounting FAT32 … invalid
 * fs info structure!" and the app never asked for its password. A To Go volume starts
 * with a FAT32 discovery volume, so its first sector is a real FAT32 boot sector. The
 * probe classified it as BITLOCKER, but the plain-partition pass checked the bytes
 * again and took it for FAT32, which both mounted it as FAT32 and removed it from the
 * unlock form.
 *
 * The image is cryptsetup's `bitlk-togo-aes-xts-128` (made by Windows), the one
 * BitLockerCompatTest unlocks. Only its first sectors matter here.
 */
class PlainPartitionsTest {

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

    companion object {
        /** The first 64 KiB of the To Go image, or null when it cannot be extracted. */
        private var toGoHead: ByteArray? = null

        @BeforeClass @JvmStatic
        fun setUp() {
            val repo = generateSequence(File("").absoluteFile) { it.parentFile }
                .first { File(it, "scripts/build_host_native.sh").exists() }
            val dir = File(repo, "app/build/bitlk-images")
            val img = File(dir, "bitlk-images/bitlk-togo-aes-xts-128.img")
            if (!img.exists()) {
                dir.mkdirs()
                val tar = File(repo, "app/src/test/resources/bitlk/bitlk-images.tar.xz")
                val p = ProcessBuilder("tar", "xJf", tar.path, "-C", dir.path).redirectErrorStream(true).start()
                p.inputStream.readBytes()
                if (p.waitFor() != 0) return
            }
            toGoHead = img.inputStream().use { it.readNBytes(64 * 1024) }
        }

        private const val PART_START = 2048
    }

    /** The volume at sector 0, as cryptsetup's image is laid out. */
    private fun wholeDevice(volume: ByteArray) = MemoryDevice(volume.copyOf(PART_START * 2 * 512))

    /** One MBR partition (type 0x0C, FAT32 LBA) at sector 2048, as Windows lays out a USB stick. */
    private fun partitioned(volume: ByteArray): MemoryDevice {
        val disk = ByteArray(PART_START * 512 + volume.size + PART_START * 512)
        val e = 446
        disk[e + 4] = 0x0C
        putLe32(disk, e + 8, PART_START.toLong())
        putLe32(disk, e + 12, ((disk.size / 512) - PART_START).toLong())
        disk[510] = 0x55; disk[511] = 0xAA.toByte()
        volume.copyInto(disk, PART_START * 512)
        return MemoryDevice(disk)
    }

    private fun putLe32(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 4) b[off + i] = (v ushr (8 * i)).toByte()
    }

    /** The same boot sector with BitLocker's GUID at byte 424 wiped: an ordinary FAT32 volume. */
    private fun withoutBitLockerGuid(volume: ByteArray) = volume.copyOf().also { it.fill(0, 424, 440) }

    private fun head(): ByteArray {
        assumeTrue("tar could not extract the BitLocker test images", toGoHead != null)
        return toGoHead!!
    }

    @Test
    fun `a To Go volume at sector 0 is offered for unlocking, not mounted as FAT32`() {
        val dev = wholeDevice(head())
        val candidates = VeraCryptUnlocker().probeCandidates(dev)
        // One volume, the whole drive: its boot code is not a partition table.
        assertEquals(listOf("Whole device" to ContainerType.BITLOCKER), candidates.map { it.label to it.containerType })
        assertEquals(0L, candidates.single().startBlock)
        assertEquals(dev.blockCount, candidates.single().blockCount)
        assertEquals(emptyList<String>(), PlainPartitions.detect(dev, candidates).map { it.label })
    }

    @Test
    fun `a To Go volume in an MBR partition is offered for unlocking, not mounted as FAT32`() {
        val dev = partitioned(head())
        val candidates = VeraCryptUnlocker().probeCandidates(dev)
        assertEquals(listOf(ContainerType.BITLOCKER), candidates.map { it.containerType })
        assertEquals(emptyList<String>(), PlainPartitions.detect(dev, candidates).map { it.label })
    }

    @Test
    fun `the same FAT32 boot sector without the BitLocker GUID still mounts as plain FAT32`() {
        val dev = partitioned(withoutBitLockerGuid(head()))
        val candidates = VeraCryptUnlocker().probeCandidates(dev)
        assertEquals(listOf(ContainerType.UNENCRYPTED), candidates.map { it.containerType })
        val plain = PlainPartitions.detect(dev, candidates)
        assertEquals(listOf("FAT32"), plain.map { it.filesystemName })
        assertEquals(PART_START.toLong(), plain.single().startBlock)
    }
}

package app.fayaz.otgmaster.ext4

import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.IOException

/**
 * Read-only ext2/3/4 filesystem driver backed by [RawBlockDevice].
 *
 * Supports:
 *  - ext4 extent trees (EXT4_EXTENTS_FL)
 *  - ext2/3 block maps (direct + single/double/triple indirect)
 *  - Linear and hash-tree (htree/dx) directories
 *  - Inline data (EXT4_INLINE_DATA_FL) for tiny files
 *
 * Write operations throw [UnsupportedOperationException] — the driver is intentionally
 * read-only. Adding write support would require journaling awareness to avoid
 * corrupting the volume on an unclean dismount.
 */
class Ext4FileSystem private constructor(
    internal val device: RawBlockDevice,
    internal val blockSize: Int,
    internal val inodesPerGroup: Int,
    internal val blocksPerGroup: Int,
    internal val inodeSize: Int,
    internal val groupDescSize: Int,
    internal val bgdTableBlock: Long,
    private val label: String,
    internal val totalBlocks: Long,
    internal val totalInodes: Long,
    /** CRC32c running state used as the base seed for all checksum computations. */
    internal val csumSeed: Int,
    internal val hasMetadataCsum: Boolean,
) : FileSystem {

    override val volumeLabel: String get() = label
    override val capacity: Long get() = totalBlocks * blockSize
    override val occupiedSpace: Long get() = (capacity - freeSpace)
    override val freeSpace: Long get() = readSuperblockFreeBlocks() * blockSize
    override val chunkSize: Int get() = blockSize
    override val type: Int get() = 0x83  // Linux partition type

    // Root directory is always inode 2 in ext2/3/4.
    override val rootDirectory: UsbFile get() = Ext4File.forInode(this, 2L, "/", null)

    internal val allocator: Ext4Allocator by lazy { Ext4Allocator(this) }

    // -----------------------------------------------------------------------
    // Block I/O — translate ext4 logical block number to device sectors
    // -----------------------------------------------------------------------

    internal fun readBlock(blockNum: Long): ByteArray {
        val sectorsPerBlock = blockSize / device.blockSize
        return device.readBlocks(blockNum * sectorsPerBlock, sectorsPerBlock)
    }

    internal fun writeBlock(blockNum: Long, data: ByteArray) {
        require(data.size == blockSize) { "writeBlock: data must be exactly $blockSize bytes" }
        val sectorsPerBlock = blockSize / device.blockSize
        device.writeBlocks(blockNum * sectorsPerBlock, data)
    }

    // -----------------------------------------------------------------------
    // Inode table write (read-modify-write the sector containing it)
    // -----------------------------------------------------------------------

    internal fun writeInode(inodeNum: Long, data: ByteArray) {
        if (hasMetadataCsum) {
            // Compute and embed checksum before writing.
            val gen = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(100)
            val zeroed = data.copyOf()
            if (zeroed.size > 124) { zeroed[124] = 0; zeroed[125] = 0 }  // l_i_checksum_lo
            if (zeroed.size > 130) { zeroed[130] = 0; zeroed[131] = 0 }  // i_checksum_hi
            val csum = Ext4Crc.update(
                Ext4Crc.update(Ext4Crc.update(csumSeed, Ext4Crc.leInt(inodeNum.toInt())),
                    Ext4Crc.leInt(gen)), zeroed)
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).run {
                if (data.size > 124) putShort(124, (csum and 0xFFFF).toShort())
                if (data.size > 130) putShort(130, (csum ushr 16 and 0xFFFF).toShort())
            }
        }
        val inodeIdxInGroup = ((inodeNum - 1) % inodesPerGroup).toInt()
        val itBlock = inodeTableBlock(inodeNum)
        val inodeByteOffset = itBlock * blockSize + inodeIdxInGroup.toLong() * inodeSize
        val sectorSize = device.blockSize
        val startSector = inodeByteOffset / sectorSize
        val endSector = (inodeByteOffset + inodeSize - 1) / sectorSize
        val sectorCount = (endSector - startSector + 1).toInt()
        val sectors = device.readBlocks(startSector, sectorCount).copyOf()
        val off = (inodeByteOffset % sectorSize).toInt()
        System.arraycopy(data, 0, sectors, off, inodeSize)
        device.writeBlocks(startSector, sectors)
    }

    // -----------------------------------------------------------------------
    // Superblock management
    // -----------------------------------------------------------------------

    /** Superblock offset from filesystem start, in bytes. */
    private val sbOffsetBytes = 1024L

    private fun readSuperblockRaw(): Pair<ByteArray, Int> {
        // Read the sectors that contain the superblock (1024 bytes at offset 1024).
        val sectorSize = device.blockSize
        val startSector = sbOffsetBytes / sectorSize
        val offInSector = (sbOffsetBytes % sectorSize).toInt()
        val sectorCount = ((offInSector + 1024 + sectorSize - 1) / sectorSize).toInt()
        return device.readBlocks(startSector, sectorCount) to offInSector
    }

    internal fun readSuperblockBytes(): ByteArray {
        val (sectors, off) = readSuperblockRaw()
        return sectors.copyOfRange(off, (off + 1024).coerceAtMost(sectors.size))
    }

    internal fun writeSuperblockBytes(sb: ByteArray) {
        val sectorSize = device.blockSize
        val startSector = sbOffsetBytes / sectorSize
        val offInSector = (sbOffsetBytes % sectorSize).toInt()
        val sectorCount = ((offInSector + 1024 + sectorSize - 1) / sectorSize).toInt()
        val sectors = device.readBlocks(startSector, sectorCount).copyOf()
        System.arraycopy(sb, 0, sectors, offInSector, sb.size.coerceAtMost(sectors.size - offInSector))
        device.writeBlocks(startSector, sectors)
    }

    /** Mark the filesystem as needing fsck (clears EXT2_VALID_FS in s_state). */
    fun markDirty() {
        val sb = readSuperblockBytes()
        ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(58, (ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN).getShort(58).toInt() and 0xFFFE).toShort())
        writeSuperblockBytes(sb)
    }

    /** Mark the filesystem as clean after a successful write sequence. */
    fun markClean() {
        val sb = readSuperblockBytes()
        ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(58, (ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN).getShort(58).toInt() or 0x0001).toShort())
        writeSuperblockBytes(sb)
    }

    /** Read current free block count from the superblock. */
    private fun readSuperblockFreeBlocks(): Long {
        val sb = readSuperblockBytes()
        val bb = ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN)
        val lo = bb.getInt(12).toLong() and 0xFFFFFFFFL
        val hi = if (sb.size >= 344) (bb.getInt(340).toLong() and 0xFFFFFFFFL) else 0L
        return (hi shl 32) or lo
    }

    internal fun updateSuperblockFreeBlocks(delta: Int) {
        val sb = readSuperblockBytes()
        val bb = ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN)
        val lo = bb.getInt(12).toLong() and 0xFFFFFFFFL
        val hi = if (sb.size >= 344) (bb.getInt(340).toLong() and 0xFFFFFFFFL) else 0L
        val current = (hi shl 32) or lo
        val updated = (current + delta).coerceAtLeast(0L)
        bb.putInt(12, (updated and 0xFFFFFFFFL).toInt())
        if (sb.size >= 344) bb.putInt(340, (updated ushr 32 and 0xFFFFFFFFL).toInt())
        writeSuperblockBytes(sb)
    }

    internal fun updateSuperblockFreeInodes(delta: Int) {
        val sb = readSuperblockBytes()
        val bb = ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN)
        val current = bb.getInt(16).toLong() and 0xFFFFFFFFL
        val updated = (current + delta).coerceAtLeast(0L)
        bb.putInt(16, (updated and 0xFFFFFFFFL).toInt())
        writeSuperblockBytes(sb)
    }

    // -----------------------------------------------------------------------
    // Inode checksum computation (used by Ext4File)
    // -----------------------------------------------------------------------

    /**
     * Compute the inode checksum running state for [inodeNum] over [inodeData]
     * (with checksum fields already zeroed).  Callers write the low 16 bits to
     * inode offset 124 and (for large inodes) the high 16 bits to offset 130.
     */
    internal fun csumInode(inodeNum: Long, inodeData: ByteArray): Int {
        val gen = ByteBuffer.wrap(inodeData).order(ByteOrder.LITTLE_ENDIAN).getInt(100)
        return Ext4Crc.update(
            Ext4Crc.update(Ext4Crc.update(csumSeed, Ext4Crc.leInt(inodeNum.toInt())),
                Ext4Crc.leInt(gen)), inodeData)
    }

    /**
     * Compute the directory block tail checksum (CRC32c running state) for a
     * directory block belonging to inode [dirIno], using the inode's generation
     * from [inodeData], over the block content [blockData] with the tail's
     * checksum field zeroed.
     */
    internal fun csumDirBlock(dirIno: Long, inodeData: ByteArray, blockData: ByteArray): Int {
        val gen = ByteBuffer.wrap(inodeData).order(ByteOrder.LITTLE_ENDIAN).getInt(100)
        return Ext4Crc.update(
            Ext4Crc.update(Ext4Crc.update(csumSeed, Ext4Crc.leInt(dirIno.toInt())),
                Ext4Crc.leInt(gen)), blockData)
    }

    // -----------------------------------------------------------------------
    // Block group descriptor → inode table block for a given inode
    // -----------------------------------------------------------------------

    internal fun inodeTableBlock(inodeNum: Long): Long {
        val groupIdx = (inodeNum - 1) / inodesPerGroup
        val bgdByteOffset = bgdTableBlock * blockSize + groupIdx * groupDescSize

        // The BGD may span block boundaries; read the block that contains it.
        val bgdBlockNum = bgdByteOffset / blockSize
        val bgdOffInBlock = (bgdByteOffset % blockSize).toInt()
        val bgdData = readBlock(bgdBlockNum)

        val bgd = ByteBuffer.wrap(bgdData).order(ByteOrder.LITTLE_ENDIAN)
        val lo = bgd.getInt(bgdOffInBlock + 8).toLong() and 0xFFFFFFFFL
        val hi = if (groupDescSize >= 44)
            (bgd.getInt(bgdOffInBlock + 40).toLong() and 0xFFFFFFFFL) else 0L
        return (hi shl 32) or lo
    }

    // -----------------------------------------------------------------------
    // Inode table read
    // -----------------------------------------------------------------------

    internal fun readInode(inodeNum: Long): ByteArray {
        val inodeIdxInGroup = ((inodeNum - 1) % inodesPerGroup).toInt()
        val itBlock = inodeTableBlock(inodeNum)

        // Inode byte offset inside the raw device
        val inodeByteOffset = itBlock * blockSize + inodeIdxInGroup.toLong() * inodeSize
        val sectorSize = device.blockSize
        val startSector = inodeByteOffset / sectorSize
        val endSector = (inodeByteOffset + inodeSize - 1) / sectorSize
        val data = device.readBlocks(startSector, (endSector - startSector + 1).toInt())
        val off = (inodeByteOffset % sectorSize).toInt()
        return data.copyOfRange(off, off + inodeSize)
    }

    companion object {
        private const val EXT4_SUPER_MAGIC = 0xEF53

        fun create(device: RawBlockDevice): Ext4FileSystem {
            // Superblock sits at byte offset 1024 from the filesystem start.
            // Our device block 0 is the first payload byte, so the superblock
            // is at sector 2 for 512-byte sectors.  Read the first 4 KiB to
            // be safe across all block sizes, then slice out the 1024-byte SB.
            val sectors = minOf(8, device.blockCount.toInt())
            val raw = device.readBlocks(0, sectors)
            require(raw.size >= 2048) { "Device too small to contain an ext superblock" }

            val sb = raw.copyOfRange(1024, minOf(2048, raw.size))
            val bb = ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN)

            val magic = bb.getShort(56).toInt() and 0xFFFF
            require(magic == EXT4_SUPER_MAGIC) {
                "Not an ext2/3/4 volume (magic=0x${magic.toString(16)})"
            }

            val logBlockSize = bb.getInt(24)
            val blockSize = 1024 shl logBlockSize

            val inodesPerGroup = bb.getInt(40)
            val blocksPerGroup = bb.getInt(32)
            val inodeSize = bb.getShort(88).toInt() and 0xFFFF
            val featIncompat = bb.getInt(100)
            val featROCompat = bb.getInt(104)
            val is64bit = (featIncompat and 0x80) != 0

            val groupDescSize = if (is64bit) {
                val s = bb.getShort(254).toInt() and 0xFFFF
                if (s < 32) 32 else s
            } else 32

            val labelBytes = sb.copyOfRange(120, 136)
            val label = String(labelBytes, Charsets.ISO_8859_1).trimEnd('\u0000')

            // BGD table starts at the block immediately after the superblock block.
            // Block size 1024: SB is in block 1, BGD starts at block 2.
            // Block size > 1024: SB fits in block 0, BGD starts at block 1.
            val bgdTableBlock = if (blockSize == 1024) 2L else 1L

            val totalBlocksLo = bb.getInt(4).toLong() and 0xFFFFFFFFL
            val totalBlocksHi = if (is64bit && sb.size >= 340)
                (bb.getInt(336).toLong() and 0xFFFFFFFFL) else 0L
            val totalBlocks = (totalBlocksHi shl 32) or totalBlocksLo

            val totalInodes = bb.getInt(0).toLong() and 0xFFFFFFFFL

            // Checksum seed: prefer the pre-computed value stored in the superblock
            // (metadata_csum_seed feature, bit 14 of featROCompat = 0x400 is metadata_csum,
            //  metadata_csum_seed stores the seed at SB offset 252).
            // Fall back to computing it from the UUID.
            val hasMetadataCsum = (featROCompat and 0x400) != 0
            val hasMetadataCsumSeed = (featROCompat and 0x4000) != 0
            val csumSeed: Int = when {
                !hasMetadataCsum -> Ext4Crc.SEED
                hasMetadataCsumSeed && sb.size >= 256 ->
                    bb.getInt(252)  // stored running state, already incorporates UUID
                else -> {
                    // Compute from UUID (16 bytes at SB offset 108).
                    val uuid = sb.copyOfRange(108, 124)
                    Ext4Crc.update(Ext4Crc.SEED, uuid)
                }
            }

            return Ext4FileSystem(
                device          = device,
                blockSize       = blockSize,
                inodesPerGroup  = inodesPerGroup,
                blocksPerGroup  = blocksPerGroup,
                inodeSize       = inodeSize,
                groupDescSize   = groupDescSize,
                bgdTableBlock   = bgdTableBlock,
                label           = label,
                totalBlocks     = totalBlocks,
                totalInodes     = totalInodes,
                csumSeed        = csumSeed,
                hasMetadataCsum = hasMetadataCsum,
            )
        }

        /** True if the first bytes of [data] look like an ext2/3/4 superblock. */
        fun hasExtMagic(data: ByteArray): Boolean {
            if (data.size < 1082) return false
            val magic = ((data[1080].toInt() and 0xFF)) or ((data[1081].toInt() and 0xFF) shl 8)
            return magic == EXT4_SUPER_MAGIC
        }
    }
}

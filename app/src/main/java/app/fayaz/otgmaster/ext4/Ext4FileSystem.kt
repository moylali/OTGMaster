package app.fayaz.otgmaster.ext4

import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    private val bgdTableBlock: Long,
    private val label: String,
    private val totalBlocks: Long,
    private val freeBlocks: Long,
) : FileSystem {

    override val volumeLabel: String get() = label
    override val capacity: Long get() = totalBlocks * blockSize
    override val occupiedSpace: Long get() = (totalBlocks - freeBlocks) * blockSize
    override val freeSpace: Long get() = freeBlocks * blockSize
    override val chunkSize: Int get() = blockSize
    override val type: Int get() = 0x83  // Linux partition type

    // Root directory is always inode 2 in ext2/3/4.
    override val rootDirectory: UsbFile get() = Ext4File.forInode(this, 2L, "/", null)

    // -----------------------------------------------------------------------
    // Block I/O — translate ext4 logical block number to device sectors
    // -----------------------------------------------------------------------

    internal fun readBlock(blockNum: Long): ByteArray {
        val sectorsPerBlock = blockSize / device.blockSize
        return device.readBlocks(blockNum * sectorsPerBlock, sectorsPerBlock)
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
            val featIncompat = bb.getInt(96)
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

            val freeBlocksLo = bb.getInt(12).toLong() and 0xFFFFFFFFL
            val freeBlocksHi = if (is64bit && sb.size >= 344)
                (bb.getInt(340).toLong() and 0xFFFFFFFFL) else 0L
            val freeBlocks = (freeBlocksHi shl 32) or freeBlocksLo

            return Ext4FileSystem(
                device        = device,
                blockSize     = blockSize,
                inodesPerGroup = inodesPerGroup,
                blocksPerGroup = blocksPerGroup,
                inodeSize     = inodeSize,
                groupDescSize = groupDescSize,
                bgdTableBlock = bgdTableBlock,
                label         = label,
                totalBlocks   = totalBlocks,
                freeBlocks    = freeBlocks,
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

package app.fayaz.otgmaster.ext4

import app.fayaz.otgmaster.block.RawBlockDevice
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.IOException

/**
 * ext2/3/4 filesystem driver backed by [RawBlockDevice], readable and writable.
 *
 * Supports:
 *  - ext4 extent trees (EXT4_EXTENTS_FL)
 *  - ext2/3 block maps (direct + single/double/triple indirect), read only
 *  - Linear and hash-tree (htree/dx) directories
 *  - Inline data (EXT4_INLINE_DATA_FL) for tiny files, read only
 *
 * Writes bypass the journal: the volume is marked dirty in s_state for the
 * duration and data is written before the metadata that references it, so an
 * unclean disconnect leaves a filesystem fsck can recover rather than one with
 * live data hanging off unwritten metadata.
 *
 * Every metadata write must refresh its checksum when metadata_csum is set, and
 * the checksums do not share one convention — the superblock's covers
 * sb[0..1019] under crc32c(~0), a bitmap's covers only the bytes its group uses
 * and folds in no group number, a group descriptor's does fold in the group
 * number, and a directory or extent block's uses a per-inode seed.  Getting the
 * seed alone wrong (it lives at s_checksum_seed, 0x270) invalidated all of them
 * at once and destroyed a test drive while content hashes still matched, so
 * [app.fayaz.otgmaster.ext4.Ext4WriteFsckTest] checks each write path against
 * `e2fsck` rather than against a hash of the bytes just written.
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
    /**
     * Why this volume must not be written, or null if it may be.
     *
     * Set when the journal holds changes not yet applied (INCOMPAT_RECOVER,
     * `needs_recovery`). This driver bypasses the journal, so writing here puts
     * new metadata underneath older journalled metadata, and the next Linux mount
     * replays the journal over it. Linux refuses to write such a volume without
     * replaying it first; this driver cannot replay a journal, so it refuses to
     * write at all.
     *
     * Observed on a OnePlus 7 (Android 16): Android mounts ext4 USB volumes itself,
     * and when OTG Master claimed the USB interface a second later, that mount was
     * cut off with the flag still set. The app then wrote to the volume, and a
     * laptop's read-only mount replayed the stale journal on top of those writes.
     */
    val readOnlyReason: String?,
) : FileSystem {

    /** True when every write is refused; see [readOnlyReason]. */
    val isReadOnly: Boolean get() = readOnlyReason != null

    private fun checkWritable() {
        readOnlyReason?.let { throw IOException("ext4 volume is read-only: $it") }
    }

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
        checkWritable()
        require(data.size == blockSize) { "writeBlock: data must be exactly $blockSize bytes" }
        val sectorsPerBlock = blockSize / device.blockSize
        device.writeBlocks(blockNum * sectorsPerBlock, data)
    }

    // -----------------------------------------------------------------------
    // Inode table write (read-modify-write the sector containing it)
    // -----------------------------------------------------------------------

    internal fun writeInode(inodeNum: Long, data: ByteArray) {
        checkWritable()
        if (hasMetadataCsum) {
            // Compute and embed checksum before writing.
            val bbIn = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val gen = bbIn.getInt(100)
            // i_checksum_hi only exists when i_extra_isize (at 128) reaches it.
            // Writing it regardless left every deleted inode mismatched, because
            // zeroing an inode also zeroes i_extra_isize.
            val extraIsize = if (data.size > 129) bbIn.getShort(128).toInt() and 0xFFFF else 0
            val hasHi = data.size > 131 && extraIsize >= 4
            val zeroed = data.copyOf()
            zeroed[124] = 0; zeroed[125] = 0  // l_i_checksum_lo
            if (hasHi) { zeroed[130] = 0; zeroed[131] = 0 }  // i_checksum_hi
            val csum = Ext4Crc.update(
                Ext4Crc.update(Ext4Crc.update(csumSeed, Ext4Crc.leInt(inodeNum.toInt())),
                    Ext4Crc.leInt(gen)), zeroed)
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).run {
                putShort(124, (csum and 0xFFFF).toShort())
                if (hasHi) putShort(130, (csum ushr 16 and 0xFFFF).toShort())
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
        checkWritable()
        // s_checksum (0x3FC = 1020) is crc32c(~0, sb[0..1019]) — a running
        // state, not inverted, and it does not use s_checksum_seed.  Every
        // superblock write has to refresh it or the primary superblock is
        // rejected outright: "Superblock checksum does not match superblock".
        if (hasMetadataCsum && sb.size >= 1024) {
            val csum = Ext4Crc.update(Ext4Crc.SEED, sb.copyOfRange(0, 1020))
            ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN).putInt(1020, csum)
        }
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
    internal fun csumDirBlock(dirIno: Long, inodeData: ByteArray, blockData: ByteArray): Int =
        csumWithInodeSeed(dirIno, inodeData, blockData)

    /**
     * CRC32c over [payload] under the per-inode seed, which every block owned by
     * an inode (directory blocks, extent tree blocks) is checksummed with:
     * crc32c(crc32c(crc32c(s_csum_seed, inode), i_generation), payload).
     */
    internal fun csumWithInodeSeed(inodeNum: Long, inodeData: ByteArray, payload: ByteArray): Int {
        val gen = ByteBuffer.wrap(inodeData).order(ByteOrder.LITTLE_ENDIAN).getInt(100)
        return Ext4Crc.update(
            Ext4Crc.update(Ext4Crc.update(csumSeed, Ext4Crc.leInt(inodeNum.toInt())),
                Ext4Crc.leInt(gen)), payload)
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
        /** s_feature_incompat bit for a journal that needs replaying (`needs_recovery`). */
        private const val INCOMPAT_RECOVER = 0x4

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
            // s_feature_compat 0x5C=92, s_feature_incompat 0x60=96,
            // s_feature_ro_compat 0x64=100.  These were previously read one
            // word late, so featROCompat came out of s_uuid and is64bit tested
            // the wrong word — which made groupDescSize 32 on a 64bit volume
            // whose descriptors are 64 bytes, and every group descriptor past
            // group 0 was then read at the wrong stride.
            val featIncompat = bb.getInt(96)
            val featROCompat = bb.getInt(100)
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

            // metadata_csum is RO_COMPAT 0x400; metadata_csum_seed is
            // INCOMPAT 0x2000 and stores the seed in s_checksum_seed at
            // 0x270 = 624 — not at 252, which is s_def_hash_version /
            // s_jnl_backup_type / s_desc_size.  Reading 252 yielded a seed of
            // 0x00400101 on a volume whose real seed was 0xdef1e86e, so every
            // metadata checksum written (superblock, inodes, bitmaps, group
            // descriptors, directory tails) was wrong and e2fsck rejected the
            // filesystem outright.
            val hasMetadataCsum = (featROCompat and 0x400) != 0
            val hasMetadataCsumSeed = (featIncompat and 0x2000) != 0
            val csumSeed: Int = when {
                !hasMetadataCsum -> Ext4Crc.SEED
                hasMetadataCsumSeed && sb.size >= 628 ->
                    bb.getInt(624)  // s_checksum_seed: running state over the UUID
                else -> {
                    // Compute from UUID (16 bytes at SB offset 104).
                    val uuid = sb.copyOfRange(104, 120)
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
                readOnlyReason  = if ((featIncompat and INCOMPAT_RECOVER) != 0)
                    "its journal needs recovery — it was not unmounted cleanly. " +
                        "Check it on a computer (e2fsck) before writing to it."
                else null,
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

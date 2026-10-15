package app.fayaz.otgmaster.ext4

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Block and inode allocator for ext4 write operations.
 *
 * Every allocation or free updates:
 *  - the relevant block/inode bitmap block on disk
 *  - the free count in the block group descriptor
 *  - the BGD checksum (CRC32c or none, depending on fs features)
 *  - the superblock free counts
 *
 * Each group touched costs one bitmap write and one descriptor write: the
 * descriptor's counts, flags and bitmap checksum are edited together and written
 * once ([updateBgd]). They used to be written field by field, four writes of the
 * same descriptor block per allocation (#36).
 *
 * Block allocation starts at a goal — just past the file's last block, or past the
 * previous allocation for a file that has none — so a file written in many calls
 * stays contiguous and its extents merge. It used to be first-fit from group 0 on
 * every call, which re-scanned the bitmaps of every part-used group at the front of
 * the volume and scattered a file into whatever holes they had.
 */
internal class Ext4Allocator(private val fs: Ext4FileSystem) {

    private companion object {
        /** bg_flags lives at BGD offset 18. */
        const val BGD_FLAGS = 18
        const val BGD_FREE_BLOCKS = 12
        const val BGD_FREE_INODES = 14
        const val BGD_USED_DIRS = 16
        const val BGD_ITABLE_UNUSED = 28
        const val BGD_CHECKSUM = 30
        const val EXT4_BG_INODE_UNINIT = 0x1
        const val EXT4_BG_BLOCK_UNINIT = 0x2
    }

    private val numGroups: Int =
        ((fs.totalBlocks + fs.blocksPerGroup - 1) / fs.blocksPerGroup).toInt()

    /** s_first_data_block — 1 on 1 KiB-block volumes, 0 otherwise. */
    private val firstDataBlock: Long by lazy {
        ByteBuffer.wrap(fs.readSuperblockBytes()).order(ByteOrder.LITTLE_ENDIAN)
            .getInt(20).toLong() and 0xFFFFFFFFL
    }

    /**
     * Where the next allocation with no goal of its own starts: just past the last
     * run handed out. Kept in memory only; a fresh mount starts from the front.
     */
    @Volatile private var streamGoal: Long = -1L

    /**
     * True when this group's on-disk bitmap block still holds no meaningful data.
     *
     * mke2fs leaves BLOCK_UNINIT/INODE_UNINIT set on most groups and never
     * writes their bitmap block, so what is on the media is stale content from
     * whatever used those sectors before; the kernel synthesises the real bitmap
     * from bg_flags instead of reading it.  `lazy_itable_init=0` zeroes the inode
     * *tables* and does not clear these flags — on a freshly prepared 59 GiB test
     * drive 50 of 65 groups are BLOCK_UNINIT and 63 of 65 are INODE_UNINIT.
     *
     * First-fitting over such a bitmap hands out blocks and inodes that are
     * really in use.  That is not a lost-space bug, it is silent corruption: an
     * inode allocated this way collided with a fixture directory's inode and
     * createNew() overwrote its mode with 0644, turning the directory into a
     * regular file.  [initialiseGroup] now builds the real bitmap before a group
     * is used, so this is a guard against allocating from one that somehow was
     * not initialised, not the normal path.
     */
    private fun groupUninit(groupIdx: Int, forBlocks: Boolean): Boolean {
        val flags = readBgdShort(groupIdx, BGD_FLAGS)
        val bit = if (forBlocks) EXT4_BG_BLOCK_UNINIT else EXT4_BG_INODE_UNINIT
        return (flags and bit) != 0
    }

    /** A 64-bit BGD field from its lo half at [loOff] and hi half at [hiOff]. */
    private fun readBgd64(groupIdx: Int, loOff: Int, hiOff: Int): Long {
        val lo = readBgdInt(groupIdx, loOff)
        val hi = if (fs.groupDescSize >= hiOff + 4) readBgdInt(groupIdx, hiOff) else 0L
        return (hi shl 32) or lo
    }

    /** Groups carrying a superblock backup: all of them, or the sparse_super set. */
    private fun bgHasSuper(g: Int, sparseSuper: Boolean): Boolean {
        if (!sparseSuper) return true
        if (g == 0 || g == 1) return true
        for (base in intArrayOf(3, 5, 7)) {
            var p = base
            while (p < g) p *= base
            if (p == g) return true
        }
        return false
    }

    /**
     * Build the real bitmaps for a group mke2fs left uninitialised, then clear
     * the flag so the group becomes ordinary usable space.
     *
     * Skipping these groups is safe but leaves most of a freshly formatted
     * volume unreachable — on a 6 GiB image only 15 blocks were allocatable.
     * The kernel synthesises the same content in ext4_init_block_bitmap(): a
     * group's in-use blocks are its superblock backup and descriptor table (if
     * it carries one), plus whatever bitmap and inode-table blocks physically
     * land inside it, which under flex_bg belong to other groups.  Those are
     * all discoverable from the descriptors, so they are derived here rather
     * than assumed.
     */
    private fun initialiseGroup(g: Int) {
        val flags0 = readBgdShort(g, BGD_FLAGS)
        if ((flags0 and (EXT4_BG_BLOCK_UNINIT or EXT4_BG_INODE_UNINIT)) == 0) return

        val sb = fs.readSuperblockBytes()
        val sbb = ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN)
        val reservedGdt = sbb.getShort(206).toInt() and 0xFFFF
        val sparseSuper = (sbb.getInt(100) and 0x1) != 0

        if ((flags0 and EXT4_BG_INODE_UNINIT) != 0) {
            // An INODE_UNINIT group has no inodes in use; only the bits past
            // inodesPerGroup are padding and must read as allocated.
            val bm = ByteArray(fs.blockSize)
            for (i in fs.inodesPerGroup until fs.blockSize * 8) {
                bm[i / 8] = (bm[i / 8].toInt() or (1 shl (i % 8))).toByte()
            }
            fs.writeBlock(readBgd64(g, 4, 36), bm)
            updateBgd(g) { d ->
                setInodeBitmapCsum(d, bm)
                d.putShort(BGD_FREE_INODES, fs.inodesPerGroup.toShort())
                d.putShort(BGD_FLAGS, (d.getShort(BGD_FLAGS).toInt() and EXT4_BG_INODE_UNINIT.inv()).toShort())
            }
        }

        if ((flags0 and EXT4_BG_BLOCK_UNINIT) != 0) {
            val bm = ByteArray(fs.blockSize)
            val groupStart = firstDataBlock + g.toLong() * fs.blocksPerGroup
            var used = 0
            fun mark(block: Long) {
                val i = block - groupStart
                if (i < 0 || i >= fs.blocksPerGroup) return
                val idx = i.toInt()
                if ((bm[idx / 8].toInt() and (1 shl (idx % 8))) != 0) return
                bm[idx / 8] = (bm[idx / 8].toInt() or (1 shl (idx % 8))).toByte()
                used++
            }

            if (bgHasSuper(g, sparseSuper)) {
                val gdtBlocks =
                    (numGroups.toLong() * fs.groupDescSize + fs.blockSize - 1) / fs.blockSize
                // superblock, then the descriptor table, then the growth reserve
                for (b in 0..gdtBlocks + reservedGdt) mark(groupStart + b)
            }

            val itableBlocks =
                (fs.inodesPerGroup.toLong() * fs.inodeSize + fs.blockSize - 1) / fs.blockSize
            for (g2 in 0 until numGroups) {
                mark(readBgd64(g2, 0, 32))
                mark(readBgd64(g2, 4, 36))
                val it0 = readBgd64(g2, 8, 40)
                for (k in 0 until itableBlocks) mark(it0 + k)
            }

            // The last group may end before the group boundary; the remainder is
            // not real storage and must not be handed out.
            var b = fs.totalBlocks
            while (b < groupStart + fs.blocksPerGroup) { mark(b); b++ }

            fs.writeBlock(readBgd64(g, 0, 32), bm)
            updateBgd(g) { d ->
                setBlockBitmapCsum(d, bm)
                d.putShort(BGD_FREE_BLOCKS, (fs.blocksPerGroup - used).toShort())
                d.putShort(BGD_FLAGS, (d.getShort(BGD_FLAGS).toInt() and EXT4_BG_BLOCK_UNINIT.inv()).toShort())
            }
        }
    }

    // -----------------------------------------------------------------------
    // Block allocation / free
    // -----------------------------------------------------------------------

    /** Allocate [count] blocks, one by one in the order handed out. See [allocateRuns]. */
    fun allocateBlocks(count: Int, goal: Long = -1L): List<Long> {
        val out = ArrayList<Long>(count)
        for (r in allocateRuns(count, goal)) for (k in 0 until r[1]) out += r[0] + k
        return out
    }

    /**
     * Allocate [count] blocks as runs of (start, length), in the order handed out.
     * Throws [IOException], having allocated nothing, if there is not enough space.
     *
     * The search starts at [goal] (or just past the previous allocation when there
     * is none), runs to the end of the volume, then wraps round to the front. Full
     * groups are skipped on their descriptor's free count without reading the
     * bitmap. Within a group each free bit found is taken, so a run breaks only
     * where something already holds the block.
     */
    fun allocateRuns(count: Int, goal: Long = -1L): List<LongArray> {
        require(count >= 0)
        val runs = mutableListOf<LongArray>()
        if (count == 0) return runs
        val start = when {
            goal in firstDataBlock until fs.totalBlocks -> goal
            streamGoal in firstDataBlock until fs.totalBlocks -> streamGoal
            else -> firstDataBlock
        }
        val g0 = ((start - firstDataBlock) / fs.blocksPerGroup).toInt()
        val off0 = ((start - firstDataBlock) % fs.blocksPerGroup).toInt()
        var need = count

        // The goal's group from the goal on, every later group, every earlier one,
        // and finally the part of the goal's group before the goal.
        fun scan(g: Int, from: Int, until: Int) {
            if (need == 0 || from >= until) return
            initialiseGroup(g)
            val free = readBgdShort(g, BGD_FREE_BLOCKS)
            if (free == 0) return
            if (groupUninit(g, forBlocks = true)) return

            val bitmapBlock = readBgd64(g, 0, 32)
            val bitmap = fs.readBlock(bitmapBlock).copyOf()
            val groupStart = firstDataBlock + g.toLong() * fs.blocksPerGroup
            val end = minOf(until, fs.blocksPerGroup, bitmap.size * 8)
            var taken = 0
            var bit = from
            while (bit < end && need > 0) {
                val byteIdx = bit ushr 3
                if ((bit and 7) == 0 && bitmap[byteIdx] == 0xFF.toByte()) { bit += 8; continue }
                val mask = 1 shl (bit and 7)
                if ((bitmap[byteIdx].toInt() and mask) == 0) {
                    val blockNum = groupStart + bit
                    if (blockNum >= fs.totalBlocks) break
                    bitmap[byteIdx] = (bitmap[byteIdx].toInt() or mask).toByte()
                    val last = runs.lastOrNull()
                    if (last != null && last[0] + last[1] == blockNum) last[1]++
                    else runs += longArrayOf(blockNum, 1)
                    taken++
                    need--
                }
                bit++
            }
            if (taken > 0) {
                fs.writeBlock(bitmapBlock, bitmap)
                updateBgd(g) { d ->
                    setBlockBitmapCsum(d, bitmap)
                    d.putShort(BGD_FREE_BLOCKS, (free - taken).coerceAtLeast(0).toShort())
                }
            }
        }

        scan(g0, off0, fs.blocksPerGroup)
        for (g in g0 + 1 until numGroups) scan(g, 0, fs.blocksPerGroup)
        for (g in 0 until g0) scan(g, 0, fs.blocksPerGroup)
        scan(g0, 0, off0)

        if (need > 0) {
            // Not enough space — roll back what we allocated.
            freeRuns(runs, updateSuperblock = false)
            throw IOException(
                "Ext4: out of usable blocks — need $count, got ${count - need}. " +
                "Groups still flagged BLOCK_UNINIT are skipped because their " +
                "on-disk bitmap is not initialised, so the volume's reported " +
                "free space is not all reachable yet."
            )
        }
        fs.updateSuperblockFreeBlocks(-count)
        runs.last().let { streamGoal = it[0] + it[1] }
        return runs
    }

    /**
     * Free every run of (start, count) blocks in [runs], one block group at a time:
     * one bitmap write, one group-descriptor update and one superblock update per
     * group touched. Freeing a block at a time cost all of those per block, so
     * deleting a 2.2 GB file was ~537,000 rounds of them, and still running after
     * 77 minutes on a Huawei P20 Lite (#33). Blocks already free are skipped.
     */
    fun freeRuns(runs: List<LongArray>, updateSuperblock: Boolean = true) {
        val byGroup = sortedMapOf<Int, MutableList<Int>>()
        for (r in runs) {
            for (blockNum in r[0] until r[0] + r[1]) {
                val g = ((blockNum - firstDataBlock) / fs.blocksPerGroup).toInt()
                byGroup.getOrPut(g) { mutableListOf() } +=
                    ((blockNum - firstDataBlock) % fs.blocksPerGroup).toInt()
            }
        }
        var total = 0
        for ((g, bits) in byGroup) {
            val bitmapBlock = readBgd64(g, 0, 32)
            val bitmap = fs.readBlock(bitmapBlock).copyOf()
            var n = 0
            for (blockInGroup in bits) {
                val byteIdx = blockInGroup / 8
                val mask = 1 shl (blockInGroup % 8)
                if ((bitmap[byteIdx].toInt() and mask) == 0) continue  // already free
                bitmap[byteIdx] = (bitmap[byteIdx].toInt() and mask.inv()).toByte()
                n++
            }
            if (n == 0) continue
            fs.writeBlock(bitmapBlock, bitmap)
            updateBgd(g) { d ->
                setBlockBitmapCsum(d, bitmap)
                d.putShort(BGD_FREE_BLOCKS, ((d.getShort(BGD_FREE_BLOCKS).toInt() and 0xFFFF) + n).toShort())
            }
            total += n
        }
        if (total > 0 && updateSuperblock) fs.updateSuperblockFreeBlocks(+total)
    }

    // -----------------------------------------------------------------------
    // Inode allocation / free
    // -----------------------------------------------------------------------

    /**
     * Allocate a new inode.  Returns the inode number (1-based).
     * [isDir] is used to update the used-directory count in the BGD.
     */
    fun allocateInode(isDir: Boolean): Long {
        val totalInodes = fs.totalInodes
        for (g in 0 until numGroups) {
            initialiseGroup(g)
            val free = readBgdShort(g, BGD_FREE_INODES)
            if (free == 0) continue
            if (groupUninit(g, forBlocks = false)) continue

            val bitmapBlock = readBgd64(g, 4, 36)
            val bitmap = fs.readBlock(bitmapBlock).copyOf()

            // The bitmap block holds blockSize*8 bits but the group only owns
            // inodesPerGroup of them; the remainder is padding that mke2fs
            // happens to set, and allocating from it would return an inode
            // belonging to the next group.
            val groupBytes = minOf(bitmap.size, fs.inodesPerGroup / 8)
            for (byteIdx in 0 until groupBytes) {
                val b = bitmap[byteIdx].toInt() and 0xFF
                if (b == 0xFF) continue
                for (bitIdx in 0..7) {
                    if ((b and (1 shl bitIdx)) == 0) {
                        val inodeInGroup = byteIdx * 8 + bitIdx
                        val inodeNum = g.toLong() * fs.inodesPerGroup + inodeInGroup + 1
                        if (inodeNum > totalInodes) continue
                        bitmap[byteIdx] = (bitmap[byteIdx].toInt() or (1 shl bitIdx)).toByte()
                        fs.writeBlock(bitmapBlock, bitmap)
                        updateBgd(g) { d ->
                            setInodeBitmapCsum(d, bitmap)
                            d.putShort(BGD_FREE_INODES, (free - 1).toShort())
                            if (isDir) d.putShort(BGD_USED_DIRS,
                                ((d.getShort(BGD_USED_DIRS).toInt() and 0xFFFF) + 1).toShort())
                            // bg_itable_unused counts never-used inodes at the END of
                            // the table.  Allocating an inode inside that region
                            // without shrinking it makes e2fsck treat the inode as
                            // unused and clear the directory entry pointing at it:
                            // "references inode 13 found in group 0's unused inodes
                            // area".  Keep it no larger than what follows this inode.
                            val maxUnused = fs.inodesPerGroup - (inodeInGroup + 1)
                            if ((d.getShort(BGD_ITABLE_UNUSED).toInt() and 0xFFFF) > maxUnused) {
                                d.putShort(BGD_ITABLE_UNUSED, maxUnused.toShort())
                            }
                        }
                        fs.updateSuperblockFreeInodes(-1)
                        return inodeNum
                    }
                }
            }
        }
        throw IOException("Ext4: no free inodes")
    }

    fun freeInode(inodeNum: Long, isDir: Boolean) {
        val g = ((inodeNum - 1) / fs.inodesPerGroup).toInt()
        val inodeInGroup = ((inodeNum - 1) % fs.inodesPerGroup).toInt()
        val bitmapBlock = readBgd64(g, 4, 36)
        val bitmap = fs.readBlock(bitmapBlock).copyOf()
        val byteIdx = inodeInGroup / 8
        val bitIdx = inodeInGroup % 8
        if ((bitmap[byteIdx].toInt() and (1 shl bitIdx)) == 0) return
        bitmap[byteIdx] = (bitmap[byteIdx].toInt() and (1 shl bitIdx).inv()).toByte()
        fs.writeBlock(bitmapBlock, bitmap)
        updateBgd(g) { d ->
            setInodeBitmapCsum(d, bitmap)
            d.putShort(BGD_FREE_INODES, ((d.getShort(BGD_FREE_INODES).toInt() and 0xFFFF) + 1).toShort())
            if (isDir) d.putShort(BGD_USED_DIRS,
                ((d.getShort(BGD_USED_DIRS).toInt() and 0xFFFF) - 1).coerceAtLeast(0).toShort())
        }
        fs.updateSuperblockFreeInodes(+1)
    }

    // -----------------------------------------------------------------------
    // BGD accessors — all calls go through fs.readBlock/writeBlock so the
    // cache is always consistent with what we write.
    // -----------------------------------------------------------------------

    internal fun readBgdInt(groupIdx: Int, fieldOffset: Int): Long {
        val byteOff = fs.bgdTableBlock * fs.blockSize + groupIdx.toLong() * fs.groupDescSize
        val blockNum = byteOff / fs.blockSize
        val offInBlock = (byteOff % fs.blockSize).toInt()
        val data = fs.readBlock(blockNum)
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            .getInt(offInBlock + fieldOffset).toLong() and 0xFFFFFFFFL
    }

    internal fun readBgdShort(groupIdx: Int, fieldOffset: Int): Int {
        val byteOff = fs.bgdTableBlock * fs.blockSize + groupIdx.toLong() * fs.groupDescSize
        val blockNum = byteOff / fs.blockSize
        val offInBlock = (byteOff % fs.blockSize).toInt()
        val data = fs.readBlock(blockNum)
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            .getShort(offInBlock + fieldOffset).toInt() and 0xFFFF
    }

    /**
     * Edit group [groupIdx]'s descriptor in one write: [edit] gets a buffer over the
     * descriptor alone (offsets are field offsets), and the descriptor checksum is
     * refreshed over the result before the block goes back to disk.
     */
    private inline fun updateBgd(groupIdx: Int, edit: (ByteBuffer) -> Unit) {
        val byteOff = fs.bgdTableBlock * fs.blockSize + groupIdx.toLong() * fs.groupDescSize
        val blockNum = byteOff / fs.blockSize
        val offInBlock = (byteOff % fs.blockSize).toInt()
        val data = fs.readBlock(blockNum).copyOf()
        val desc = ByteBuffer.wrap(data, offInBlock, fs.groupDescSize).slice()
            .order(ByteOrder.LITTLE_ENDIAN)
        edit(desc)
        if (fs.hasMetadataCsum) {
            desc.putShort(BGD_CHECKSUM, 0)
            val bgd = data.copyOfRange(offInBlock, offInBlock + fs.groupDescSize)
            val csum = Ext4Crc.update(Ext4Crc.update(fs.csumSeed, Ext4Crc.leInt(groupIdx)), bgd)
            desc.putShort(BGD_CHECKSUM, (csum and 0xFFFF).toShort())
        }
        fs.writeBlock(blockNum, data)
    }

    // -----------------------------------------------------------------------
    // Checksum helpers
    // -----------------------------------------------------------------------

    /*
     * A bitmap's checksum lives in its group descriptor, so it is set inside the
     * same [updateBgd] as the counts it goes with.
     *
     * The checksum covers only the bytes the group actually uses, not the whole
     * block: blocksPerGroup/8 for the block bitmap and (inodesPerGroup+7)/8 for the
     * inode bitmap.  Hashing the full 4 KiB block made every inode bitmap checksum
     * wrong, because a group has 8192 inodes = 1024 bytes, not 4096.  Unlike the
     * group descriptor checksum, a bitmap checksum does NOT fold in the group number
     * — it is crc32c(s_csum_seed, bitmap[0..sz]).  bg_block_bitmap_csum_hi is at
     * 0x38 = 56 and bg_inode_bitmap_csum_hi at 0x3A = 58 — not 60/62.
     */

    private fun setBlockBitmapCsum(desc: ByteBuffer, bitmap: ByteArray) =
        setBitmapCsum(desc, bitmap, minOf(bitmap.size, fs.blocksPerGroup / 8), loOff = 24, hiOff = 56)

    private fun setInodeBitmapCsum(desc: ByteBuffer, bitmap: ByteArray) =
        setBitmapCsum(desc, bitmap, minOf(bitmap.size, (fs.inodesPerGroup + 7) / 8), loOff = 26, hiOff = 58)

    private fun setBitmapCsum(desc: ByteBuffer, bitmap: ByteArray, len: Int, loOff: Int, hiOff: Int) {
        if (!fs.hasMetadataCsum) return
        val csum = Ext4Crc.update(fs.csumSeed, bitmap.copyOfRange(0, len))
        desc.putShort(loOff, (csum and 0xFFFF).toShort())
        if (fs.groupDescSize >= 64) desc.putShort(hiOff, (csum ushr 16 and 0xFFFF).toShort())
    }
}

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
 * Allocation is first-fit within block groups.  No attempt is made to keep
 * extents contiguous across groups — that optimisation matters for
 * performance on spinning disks and is irrelevant for USB flash media.
 */
internal class Ext4Allocator(private val fs: Ext4FileSystem) {

    private companion object {
        /** bg_flags lives at BGD offset 18. */
        const val BGD_FLAGS = 18
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
            writeBitmap(readBgd64(g, 4, 36), bm, g, isBitmap = false)
            writeBgdShort(g, 14, fs.inodesPerGroup.toShort())
            writeBgdShort(g, BGD_FLAGS,
                (readBgdShort(g, BGD_FLAGS) and EXT4_BG_INODE_UNINIT.inv()).toShort())
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

            writeBitmap(readBgd64(g, 0, 32), bm, g, isBitmap = true)
            writeBgdShort(g, 12, (fs.blocksPerGroup - used).toShort())
            writeBgdShort(g, BGD_FLAGS,
                (readBgdShort(g, BGD_FLAGS) and EXT4_BG_BLOCK_UNINIT.inv()).toShort())
        }
        writeBgdChecksum(g)
    }

    // -----------------------------------------------------------------------
    // Block allocation / free
    // -----------------------------------------------------------------------

    /**
     * Allocate [count] blocks.  Returns physical block numbers.
     * Throws [IOException] if there is not enough free space.
     *
     * Blocks are allocated group-by-group; within each group they are
     * contiguous where possible but the returned list may span groups.
     */
    fun allocateBlocks(count: Int): List<Long> {
        val result = mutableListOf<Long>()
        for (g in 0 until numGroups) {
            if (result.size == count) break
            initialiseGroup(g)
            val free = readBgdShort(g, 12)
            if (free == 0) continue
            if (groupUninit(g, forBlocks = true)) continue

            val bitmapBlock = readBgd64(g, 0, 32)
            val bitmap = fs.readBlock(bitmapBlock).copyOf()
            val groupStart = firstDataBlock + g.toLong() * fs.blocksPerGroup

            var changed = false
            outer@ for (byteIdx in bitmap.indices) {
                if (result.size == count) break
                val b = bitmap[byteIdx].toInt() and 0xFF
                if (b == 0xFF) continue
                for (bitIdx in 0..7) {
                    if (result.size == count) break@outer
                    if ((b and (1 shl bitIdx)) == 0) {
                        val blockInGroup = byteIdx * 8 + bitIdx
                        val blockNum = groupStart + blockInGroup
                        if (blockNum >= fs.totalBlocks) break@outer
                        bitmap[byteIdx] = (bitmap[byteIdx].toInt() or (1 shl bitIdx)).toByte()
                        result.add(blockNum)
                        changed = true
                    }
                }
            }

            if (changed) {
                val allocated = result.count {
                    (it - firstDataBlock) / fs.blocksPerGroup == g.toLong()
                }
                writeBitmap(bitmapBlock, bitmap, g, isBitmap = true)
                writeBgdShort(g, 12, (free - allocated).coerceAtLeast(0).toShort())
                writeBgdChecksum(g)
            }
        }

        if (result.size < count) {
            // Not enough space — roll back what we allocated.
            result.forEach { freeBlock(it) }
            throw IOException(
                "Ext4: out of usable blocks — need $count, got ${result.size}. " +
                "Groups still flagged BLOCK_UNINIT are skipped because their " +
                "on-disk bitmap is not initialised, so the volume's reported " +
                "free space is not all reachable yet."
            )
        }
        fs.updateSuperblockFreeBlocks(-count)
        return result
    }

    fun freeBlock(blockNum: Long) {
        val g = ((blockNum - firstDataBlock) / fs.blocksPerGroup).toInt()
        val blockInGroup = ((blockNum - firstDataBlock) % fs.blocksPerGroup).toInt()
        val bitmapBlock = readBgd64(g, 0, 32)
        val bitmap = fs.readBlock(bitmapBlock).copyOf()
        val byteIdx = blockInGroup / 8
        val bitIdx = blockInGroup % 8
        if ((bitmap[byteIdx].toInt() and (1 shl bitIdx)) == 0) return  // already free
        bitmap[byteIdx] = (bitmap[byteIdx].toInt() and (1 shl bitIdx).inv()).toByte()
        writeBitmap(bitmapBlock, bitmap, g, isBitmap = true)
        writeBgdShort(g, 12, (readBgdShort(g, 12) + 1).toShort())
        writeBgdChecksum(g)
        fs.updateSuperblockFreeBlocks(+1)
    }

    fun freeBlocks(blocks: Iterable<Long>) = blocks.forEach { freeBlock(it) }

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
            val free = readBgdShort(g, 14)
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
                        writeBitmap(bitmapBlock, bitmap, g, isBitmap = false)
                        writeBgdShort(g, 14, (free - 1).toShort())
                        if (isDir) writeBgdShort(g, 16, (readBgdShort(g, 16) + 1).toShort())
                        // bg_itable_unused counts never-used inodes at the END of
                        // the table.  Allocating an inode inside that region
                        // without shrinking it makes e2fsck treat the inode as
                        // unused and clear the directory entry pointing at it:
                        // "references inode 13 found in group 0's unused inodes
                        // area".  Keep it no larger than what follows this inode.
                        val maxUnused = fs.inodesPerGroup - (inodeInGroup + 1)
                        if (readBgdShort(g, 28) > maxUnused) {
                            writeBgdShort(g, 28, maxUnused.toShort())
                        }
                        writeBgdChecksum(g)
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
        writeBitmap(bitmapBlock, bitmap, g, isBitmap = false)
        writeBgdShort(g, 14, (readBgdShort(g, 14) + 1).toShort())
        if (isDir) writeBgdShort(g, 16, (readBgdShort(g, 16) - 1).coerceAtLeast(0).toShort())
        writeBgdChecksum(g)
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

    private fun writeBgdInt(groupIdx: Int, fieldOffset: Int, value: Int) {
        val byteOff = fs.bgdTableBlock * fs.blockSize + groupIdx.toLong() * fs.groupDescSize
        val blockNum = byteOff / fs.blockSize
        val offInBlock = (byteOff % fs.blockSize).toInt()
        val data = fs.readBlock(blockNum).copyOf()
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(offInBlock + fieldOffset, value)
        fs.writeBlock(blockNum, data)
    }

    internal fun writeBgdShort(groupIdx: Int, fieldOffset: Int, value: Short) {
        val byteOff = fs.bgdTableBlock * fs.blockSize + groupIdx.toLong() * fs.groupDescSize
        val blockNum = byteOff / fs.blockSize
        val offInBlock = (byteOff % fs.blockSize).toInt()
        val data = fs.readBlock(blockNum).copyOf()
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putShort(offInBlock + fieldOffset, value)
        fs.writeBlock(blockNum, data)
    }

    /** Read a complete BGD as a raw ByteArray of length [fs.groupDescSize]. */
    private fun readBgdRaw(groupIdx: Int): ByteArray {
        val byteOff = fs.bgdTableBlock * fs.blockSize + groupIdx.toLong() * fs.groupDescSize
        val blockNum = byteOff / fs.blockSize
        val offInBlock = (byteOff % fs.blockSize).toInt()
        val data = fs.readBlock(blockNum)
        return data.copyOfRange(offInBlock, offInBlock + fs.groupDescSize)
    }

    private fun writeBgdRaw(groupIdx: Int, bgd: ByteArray) {
        val byteOff = fs.bgdTableBlock * fs.blockSize + groupIdx.toLong() * fs.groupDescSize
        val blockNum = byteOff / fs.blockSize
        val offInBlock = (byteOff % fs.blockSize).toInt()
        val data = fs.readBlock(blockNum).copyOf()
        System.arraycopy(bgd, 0, data, offInBlock, bgd.size)
        fs.writeBlock(blockNum, data)
    }

    // -----------------------------------------------------------------------
    // Checksum helpers
    // -----------------------------------------------------------------------

    /**
     * Write bitmap block [bitmapBlock] with updated checksum stored in the BGD.
     *
     * [isBitmap] true = block bitmap (BGD offsets 24/60); false = inode bitmap
     * (BGD offsets 26/62).
     */
    private fun writeBitmap(bitmapBlock: Long, bitmap: ByteArray, groupIdx: Int, isBitmap: Boolean) {
        fs.writeBlock(bitmapBlock, bitmap)
        if (!fs.hasMetadataCsum) return
        // The bitmap checksum covers only the bytes the group actually uses,
        // not the whole block: blocksPerGroup/8 for the block bitmap and
        // (inodesPerGroup+7)/8 for the inode bitmap.  Hashing the full 4 KiB
        // block made every inode bitmap checksum wrong, because a group has
        // 8192 inodes = 1024 bytes, not 4096.
        val csumLen =
            if (isBitmap) minOf(bitmap.size, fs.blocksPerGroup / 8)
            else minOf(bitmap.size, (fs.inodesPerGroup + 7) / 8)
        // Unlike the group descriptor checksum, a bitmap checksum does NOT fold
        // in the group number — it is crc32c(s_csum_seed, bitmap[0..sz]).
        val csum = Ext4Crc.update(fs.csumSeed, bitmap.copyOfRange(0, csumLen))
        val lo = (csum and 0xFFFF).toShort()
        val hi = (csum ushr 16 and 0xFFFF).toShort()
        // bg_block_bitmap_csum_hi is at 0x38 = 56 and bg_inode_bitmap_csum_hi
        // at 0x3A = 58 — not 60/62.
        if (isBitmap) {
            writeBgdShort(groupIdx, 24, lo)
            if (fs.groupDescSize >= 64) writeBgdShort(groupIdx, 56, hi)
        } else {
            writeBgdShort(groupIdx, 26, lo)
            if (fs.groupDescSize >= 64) writeBgdShort(groupIdx, 58, hi)
        }
    }

    /** Recompute and write the BGD checksum for [groupIdx]. */
    fun writeBgdChecksum(groupIdx: Int) {
        if (!fs.hasMetadataCsum) return
        val bgd = readBgdRaw(groupIdx)
        // Zero the checksum field at offset 30 before computing.
        bgd[30] = 0; bgd[31] = 0
        val csum = Ext4Crc.update(
            Ext4Crc.update(fs.csumSeed, Ext4Crc.leInt(groupIdx)), bgd
        )
        ByteBuffer.wrap(bgd).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(30, (csum and 0xFFFF).toShort())
        writeBgdRaw(groupIdx, bgd)
    }
}

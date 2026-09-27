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

    private val numGroups: Int =
        ((fs.totalBlocks + fs.blocksPerGroup - 1) / fs.blocksPerGroup).toInt()

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
            val free = readBgdShort(g, 12)
            if (free == 0) continue

            val bitmapBlock = readBgdInt(g, 0)
            val bitmap = fs.readBlock(bitmapBlock).copyOf()
            val groupStart = g.toLong() * fs.blocksPerGroup

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
                val allocated = result.count { it / fs.blocksPerGroup == g.toLong() }
                writeBitmap(bitmapBlock, bitmap, g, isBitmap = true)
                writeBgdShort(g, 12, (free - allocated).coerceAtLeast(0).toShort())
                writeBgdChecksum(g)
            }
        }

        if (result.size < count) {
            // Not enough space — roll back what we allocated.
            result.forEach { freeBlock(it) }
            throw IOException("Ext4: disk full — need $count blocks, only ${result.size} available")
        }
        fs.updateSuperblockFreeBlocks(-count)
        return result
    }

    fun freeBlock(blockNum: Long) {
        val g = (blockNum / fs.blocksPerGroup).toInt()
        val blockInGroup = (blockNum % fs.blocksPerGroup).toInt()
        val bitmapBlock = readBgdInt(g, 0)
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
            val free = readBgdShort(g, 14)
            if (free == 0) continue

            val bitmapBlock = readBgdInt(g, 4)
            val bitmap = fs.readBlock(bitmapBlock).copyOf()

            for (byteIdx in bitmap.indices) {
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
        val bitmapBlock = readBgdInt(g, 4)
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
        val csum = Ext4Crc.update(
            Ext4Crc.update(fs.csumSeed, Ext4Crc.leInt(groupIdx)), bitmap
        )
        val lo = (csum and 0xFFFF).toShort()
        val hi = (csum ushr 16 and 0xFFFF).toShort()
        if (isBitmap) {
            writeBgdShort(groupIdx, 24, lo)
            if (fs.groupDescSize >= 64) writeBgdShort(groupIdx, 60, hi)
        } else {
            writeBgdShort(groupIdx, 26, lo)
            if (fs.groupDescSize >= 64) writeBgdShort(groupIdx, 62, hi)
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

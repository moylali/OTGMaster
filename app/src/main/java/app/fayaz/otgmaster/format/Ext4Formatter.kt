package app.fayaz.otgmaster.format

import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.ext4.Ext4Crc
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * Writes an empty ext4 filesystem, the way `mkfs.ext4` lays one out, with the feature set
 * this app's ext4 driver is tested against (the physical fixture drives and
 * Ext4WriteFsckTest are made by mke2fs 1.47 with the defaults below):
 *
 * - 4 KiB blocks, 32768 blocks per group, 256-byte inodes, one inode per 16 KiB;
 * - compat `has_journal ext_attr dir_index`; incompat `filetype extent 64bit`;
 *   ro_compat `sparse_super large_file huge_file dir_nlink extra_isize metadata_csum`;
 * - a jbd2 journal of mke2fs's default size, capped at 64 MiB so it fits group 0;
 * - no reserved blocks (`-m 0`): this is a removable drive, not a root filesystem.
 *
 * Left out, each because it adds layout this formatter would have to get right with
 * nothing gained on a USB drive: `resize_inode` (reserved GDT blocks for online growth),
 * `flex_bg` (metadata packed into the first group of each flex group), `orphan_file`,
 * `metadata_csum_seed`. Every one of them is optional; e2fsck and Linux accept a volume
 * without them.
 *
 * Inode tables past group 0 are not written on a quick format: those groups are flagged
 * INODE_UNINIT with every inode unused (bg_itable_unused = inodes per group), which is
 * what mke2fs's default `lazy_itable_init` produces. Linux zeroes them in the background
 * on first mount and e2fsck skips them. A full format zeroes the whole volume first, so
 * the groups are also flagged ITABLE_ZEROED.
 *
 * The root directory is mode 0777, not mke2fs's 0755: a USB drive formatted on a phone is
 * mounted on a desktop by a normal user, who could otherwise not write to it.
 */
object Ext4Formatter {

    const val BLOCK_SIZE = 4096
    private const val BLOCKS_PER_GROUP = 8 * BLOCK_SIZE
    private const val INODE_SIZE = 256
    private const val INODE_RATIO = 16384
    private const val DESC_SIZE = 64
    private const val FIRST_INO = 11
    private const val ROOT_INO = 2
    private const val JOURNAL_INO = 8
    private const val LOST_FOUND_INO = 11
    /** Below this ext4 needs 1 KiB blocks and other choices mke2fs makes for tiny volumes. */
    const val MIN_BYTES = 16L shl 20

    private const val COMPAT = 0x4 or 0x8 or 0x20
    private const val INCOMPAT = 0x2 or 0x40 or 0x80
    private const val RO_COMPAT = 0x1 or 0x2 or 0x8 or 0x20 or 0x40 or 0x400

    private const val BG_INODE_UNINIT = 0x1
    private const val BG_ITABLE_ZEROED = 0x4
    private const val EXTENTS_FL = 0x80000

    private val random = SecureRandom()

    /** Where everything goes, worked out before a byte is written. */
    class Layout(val totalBlocks: Long, val groups: Int, val inodesPerGroup: Int,
                 val gdtBlocks: Int, val inodeTableBlocks: Int, val journalBlocks: Int) {
        val totalInodes: Long get() = groups.toLong() * inodesPerGroup
        fun groupStart(g: Int): Long = g.toLong() * BLOCKS_PER_GROUP
        fun groupBlocks(g: Int): Int = minOf(BLOCKS_PER_GROUP.toLong(), totalBlocks - groupStart(g)).toInt()
        fun hasSuper(g: Int): Boolean = g == 0 || g == 1 || isPower(g, 3) || isPower(g, 5) || isPower(g, 7)
        /** Superblock (+GDT) blocks at the start of group [g]. */
        fun superBlocks(g: Int): Int = if (hasSuper(g)) 1 + gdtBlocks else 0
        fun blockBitmap(g: Int): Long = groupStart(g) + superBlocks(g)
        fun inodeBitmap(g: Int): Long = blockBitmap(g) + 1
        fun inodeTable(g: Int): Long = inodeBitmap(g) + 1
        /** The first block of group [g] after its own metadata. */
        fun firstData(g: Int): Long = inodeTable(g) + inodeTableBlocks
        val rootBlock: Long get() = firstData(0)
        val lostFoundBlock: Long get() = rootBlock + 1
        val journalStart: Long get() = lostFoundBlock + 1

        private fun isPower(n: Int, base: Int): Boolean {
            var x = base
            while (x < n) x *= base
            return x == n
        }
    }

    /** mke2fs's ext2fs_default_journal_size, capped at 16384 blocks so it fits in group 0. */
    private fun journalBlocks(totalBlocks: Long): Int = when {
        totalBlocks < 2048 -> 0
        totalBlocks < 32768 -> 1024
        totalBlocks < 256 * 1024 -> 4096
        totalBlocks < 512 * 1024 -> 8192
        else -> 16384
    }

    /** The layout for [bytes], or why ext4 cannot be laid out there. Nothing is written. */
    fun layout(bytes: Long): Layout {
        require(bytes >= MIN_BYTES) { "ext4 needs at least ${MIN_BYTES shr 20} MiB; this volume has ${bytes shr 20} MiB" }
        var totalBlocks = bytes / BLOCK_SIZE
        require(totalBlocks < (1L shl 32) * BLOCKS_PER_GROUP) { "volume too large for ext4" }
        while (true) {
            val groups = ((totalBlocks + BLOCKS_PER_GROUP - 1) / BLOCKS_PER_GROUP).toInt()
            val inodes = bytes / INODE_RATIO
            // A multiple of 16 inodes (one 4 KiB block of 256-byte inodes), at most 8 per bitmap bit.
            var ipg = ((inodes + groups - 1) / groups).toInt()
            ipg = ((ipg + 15) / 16 * 16).coerceIn(16, BLOCKS_PER_GROUP)
            val gdtBlocks = (groups * DESC_SIZE + BLOCK_SIZE - 1) / BLOCK_SIZE
            val itb = ipg * INODE_SIZE / BLOCK_SIZE
            val l = Layout(totalBlocks, groups, ipg, gdtBlocks, itb, journalBlocks(totalBlocks))
            // As mke2fs does: a last group too small for its own metadata plus some data is dropped.
            val last = groups - 1
            if (last > 0 && l.groupBlocks(last) < l.superBlocks(last) + 2 + itb + 50) {
                totalBlocks = l.groupStart(last)
                continue
            }
            val group0Free = l.groupBlocks(0) - (l.journalStart - l.groupStart(0)) - l.journalBlocks
            require(group0Free >= 0) { "volume too small for an ext4 journal" }
            return l
        }
    }

    /**
     * Writes an empty ext4 over the first [totalSectors] sectors of [device] (the whole of a
     * volume, or of a container's data area). [full] means the caller has already zeroed it.
     */
    fun format(device: RawBlockDevice, totalSectors: Long, label: String, full: Boolean,
               progress: (Float) -> Unit = {}) {
        require(BLOCK_SIZE % device.blockSize == 0) { "ext4 4 KiB blocks on a ${device.blockSize}-byte device" }
        val l = layout(totalSectors * device.blockSize)
        val w = BlockWriter(device)
        val uuid = ByteArray(16).also { random.nextBytes(it) }
        uuid[6] = ((uuid[6].toInt() and 0x0F) or 0x40).toByte()   // version 4
        uuid[8] = ((uuid[8].toInt() and 0x3F) or 0x80).toByte()   // RFC 4122 variant
        val seed = Ext4Crc.update(Ext4Crc.SEED, uuid)
        val now = (System.currentTimeMillis() / 1000).toInt()

        // --- group descriptors and bitmaps -------------------------------------------------
        val gdt = ByteArray(l.gdtBlocks * BLOCK_SIZE)
        var freeBlocksTotal = 0L
        val group0Used = (l.journalStart - l.groupStart(0) + l.journalBlocks).toInt()
        for (g in 0 until l.groups) {
            val blocks = l.groupBlocks(g)
            val used = if (g == 0) group0Used else l.superBlocks(g) + 2 + l.inodeTableBlocks
            val blockBitmap = ByteArray(BLOCK_SIZE)
            for (i in 0 until used) setBit(blockBitmap, i)
            for (i in blocks until BLOCKS_PER_GROUP) setBit(blockBitmap, i)   // past the end of the volume
            val inodeBitmap = ByteArray(BLOCK_SIZE)
            if (g == 0) for (i in 0 until FIRST_INO) setBit(inodeBitmap, i)
            for (i in l.inodesPerGroup until BLOCK_SIZE * 8) setBit(inodeBitmap, i)   // padding, as Linux sets it
            val usedInodes = if (g == 0) FIRST_INO else 0
            val free = blocks - used
            freeBlocksTotal += free

            val d = ByteBuffer.wrap(gdt, g * DESC_SIZE, DESC_SIZE).slice().order(ByteOrder.LITTLE_ENDIAN)
            putLong(d, 0, 32, l.blockBitmap(g))
            putLong(d, 4, 36, l.inodeBitmap(g))
            putLong(d, 8, 40, l.inodeTable(g))
            putCount(d, 12, 44, free.toLong())
            putCount(d, 14, 46, (l.inodesPerGroup - usedInodes).toLong())
            putCount(d, 16, 48, if (g == 0) 2L else 0L)   // root and lost+found
            putCount(d, 28, 50, (l.inodesPerGroup - usedInodes).toLong())   // bg_itable_unused
            var flags = if (g == 0 || full) BG_ITABLE_ZEROED else 0
            if (g != 0) flags = flags or BG_INODE_UNINIT
            d.putShort(18, flags.toShort())
            val bcs = Ext4Crc.update(seed, blockBitmap, 0, BLOCKS_PER_GROUP / 8)
            d.putShort(24, (bcs and 0xFFFF).toShort()); d.putShort(56, (bcs ushr 16).toShort())
            val ics = Ext4Crc.update(seed, inodeBitmap, 0, (l.inodesPerGroup + 7) / 8)
            d.putShort(26, (ics and 0xFFFF).toShort()); d.putShort(58, (ics ushr 16).toShort())
            val desc = ByteArray(DESC_SIZE).also { d.position(0); d.get(it) }
            val gcs = Ext4Crc.update(Ext4Crc.update(seed, Ext4Crc.leInt(g)), desc)
            d.putShort(30, (gcs and 0xFFFF).toShort())

            w.write(l.blockBitmap(g), blockBitmap + inodeBitmap)
            progress(0.6f * (g + 1) / l.groups)
        }

        // --- group 0's inode table, root, lost+found and the journal --------------------------
        val inodeTable0 = ByteArray(l.inodeTableBlocks * BLOCK_SIZE)
        fun putInode(ino: Int, bytes: ByteArray) {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val csum = Ext4Crc.update(Ext4Crc.update(Ext4Crc.update(seed, Ext4Crc.leInt(ino)),
                Ext4Crc.leInt(b.getInt(100))), bytes)   // checksum fields are still zero here
            b.putShort(124, (csum and 0xFFFF).toShort()); b.putShort(130, (csum ushr 16).toShort())
            System.arraycopy(bytes, 0, inodeTable0, (ino - 1) * INODE_SIZE, INODE_SIZE)
        }
        val rootInode = inode(0x4000 or 0x1FF, links = 3, sizeBytes = BLOCK_SIZE.toLong(), now = now,
            extents = listOf(l.rootBlock to 1))
        val lostInode = inode(0x4000 or 0x1C0, links = 2, sizeBytes = BLOCK_SIZE.toLong(), now = now,
            extents = listOf(l.lostFoundBlock to 1))
        val journalInode = if (l.journalBlocks > 0) inode(0x8000 or 0x180, links = 1,
            sizeBytes = l.journalBlocks.toLong() * BLOCK_SIZE, now = now,
            extents = listOf(l.journalStart to l.journalBlocks)) else null
        putInode(ROOT_INO, rootInode)
        putInode(LOST_FOUND_INO, lostInode)
        journalInode?.let { putInode(JOURNAL_INO, it) }
        w.write(l.inodeTable(0), inodeTable0)

        w.write(l.rootBlock, dirBlock(seed, ROOT_INO, rootInode, listOf(
            Triple(ROOT_INO, ".", 2), Triple(ROOT_INO, "..", 2), Triple(LOST_FOUND_INO, "lost+found", 2))))
        w.write(l.lostFoundBlock, dirBlock(seed, LOST_FOUND_INO, lostInode, listOf(
            Triple(LOST_FOUND_INO, ".", 2), Triple(ROOT_INO, "..", 2))))
        if (l.journalBlocks > 0) {
            if (!full) {
                // A journal of stale bytes is never replayed (s_start = 0 says it is empty),
                // but mke2fs zeroes it and so does this: 64 MiB at most.
                val zero = ByteArray(256 * BLOCK_SIZE)
                var b = 1
                while (b < l.journalBlocks) {
                    val n = minOf(256, l.journalBlocks - b)
                    w.write(l.journalStart + b, if (n == 256) zero else ByteArray(n * BLOCK_SIZE))
                    b += n
                    progress(0.6f + 0.3f * b / l.journalBlocks)
                }
            }
            w.write(l.journalStart, journalSuperblock(uuid, l.journalBlocks))
        }

        // --- superblock and its backups -----------------------------------------------------
        val freeInodes = l.totalInodes - FIRST_INO
        for (g in 0 until l.groups) {
            if (!l.hasSuper(g)) continue
            val sb = superblock(l, uuid, label, now, freeBlocksTotal, freeInodes, g, journalInode)
            if (g == 0) {
                // Block 0: 1 KiB of zeros (where a boot sector would be) and then the superblock.
                w.write(0, ByteArray(1024) + sb + ByteArray(BLOCK_SIZE - 2048))
            } else {
                w.write(l.groupStart(g), sb + ByteArray(BLOCK_SIZE - 1024))
            }
            w.write(l.groupStart(g) + 1, gdt)
        }
        // A stale signature past the last group (an old NTFS backup boot sector, say) could
        // still be found by a tool probing the end of the partition.
        val tail = totalSectors * device.blockSize / BLOCK_SIZE - l.totalBlocks
        if (!full && tail > 0) w.write(l.totalBlocks, ByteArray((minOf(tail, 32L) * BLOCK_SIZE).toInt()))
        progress(1f)
    }

    private fun superblock(l: Layout, uuid: ByteArray, label: String, now: Int, freeBlocks: Long,
                           freeInodes: Long, group: Int, journalInode: ByteArray?): ByteArray {
        val sb = ByteArray(1024)
        val b = ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, l.totalInodes.toInt())
        b.putInt(4, l.totalBlocks.toInt()); b.putInt(336, (l.totalBlocks ushr 32).toInt())
        b.putInt(12, freeBlocks.toInt()); b.putInt(344, (freeBlocks ushr 32).toInt())
        b.putInt(16, freeInodes.toInt())
        b.putInt(20, 0)                               // s_first_data_block
        b.putInt(24, 2); b.putInt(28, 2)              // log2(block size / 1024), same for clusters
        b.putInt(32, BLOCKS_PER_GROUP); b.putInt(36, BLOCKS_PER_GROUP)
        b.putInt(40, l.inodesPerGroup)
        b.putInt(48, now)                             // s_wtime
        b.putShort(54, (-1).toShort())                // s_max_mnt_count: no forced checks
        b.putShort(56, 0xEF53.toShort())
        b.putShort(58, 1)                             // s_state: clean
        b.putShort(60, 1)                             // s_errors: continue
        b.putInt(64, now)                             // s_lastcheck
        b.putInt(76, 1)                               // s_rev_level: dynamic
        b.putInt(84, FIRST_INO)
        b.putShort(88, INODE_SIZE.toShort())
        b.putShort(90, group.toShort())               // s_block_group_nr
        b.putInt(92, COMPAT); b.putInt(96, INCOMPAT); b.putInt(100, RO_COMPAT)
        System.arraycopy(uuid, 0, sb, 104, 16)
        // 16 bytes of UTF-8, cut at a character boundary rather than through one.
        var cut = label
        while (cut.toByteArray(Charsets.UTF_8).size > 16) cut = cut.dropLast(1)
        val name = cut.toByteArray(Charsets.UTF_8)
        System.arraycopy(name, 0, sb, 120, name.size)
        if (journalInode != null) {
            b.putInt(224, JOURNAL_INO)
            sb[253] = 1                               // s_jnl_backup_type: EXT3_JNL_BACKUP_BLOCKS
            System.arraycopy(journalInode, 40, sb, 268, 60)   // i_block
            b.putInt(328, ByteBuffer.wrap(journalInode).order(ByteOrder.LITTLE_ENDIAN).getInt(108))
            b.putInt(332, ByteBuffer.wrap(journalInode).order(ByteOrder.LITTLE_ENDIAN).getInt(4))
        }
        ByteArray(16).also { random.nextBytes(it) }.copyInto(sb, 236)   // s_hash_seed
        sb[252] = 1                                   // s_def_hash_version: half_md4
        b.putShort(254, DESC_SIZE.toShort())
        b.putInt(256, 0x0C)                           // default mount options: user_xattr, acl
        b.putInt(264, now)                            // s_mkfs_time
        b.putShort(348, 32); b.putShort(350, 32)      // s_min/want_extra_isize
        b.putInt(352, 0x1)                            // s_flags: signed directory hash
        sb[373] = 1                                   // s_checksum_type: crc32c
        b.putInt(1020, Ext4Crc.update(Ext4Crc.SEED, sb, 0, 1020))
        return sb
    }

    /** A 256-byte inode with an in-inode extent tree over [extents] (start block, length). */
    private fun inode(mode: Int, links: Int, sizeBytes: Long, now: Int, extents: List<Pair<Long, Int>>): ByteArray {
        val i = ByteArray(INODE_SIZE)
        val b = ByteBuffer.wrap(i).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(0, mode.toShort())
        b.putInt(4, sizeBytes.toInt()); b.putInt(108, (sizeBytes ushr 32).toInt())
        b.putInt(8, now); b.putInt(12, now); b.putInt(16, now); b.putInt(144, now)
        b.putShort(26, links.toShort())
        b.putInt(28, (extents.sumOf { it.second.toLong() } * (BLOCK_SIZE / 512)).toInt())
        b.putInt(32, EXTENTS_FL)
        // Extent header: magic, entries, max 4 in the inode, depth 0.
        b.putShort(40, 0xF30A.toShort()); b.putShort(42, extents.size.toShort()); b.putShort(44, 4)
        var logical = 0
        extents.forEachIndexed { n, (start, len) ->
            require(len <= 32768 && extents.size <= 4)
            val o = 52 + n * 12
            b.putInt(o, logical); b.putShort(o + 4, len.toShort())
            b.putShort(o + 6, (start ushr 32).toShort()); b.putInt(o + 8, start.toInt())
            logical += len
        }
        b.putShort(128, 32)                           // i_extra_isize
        return i
    }

    /** A directory block with [entries] (inode, name, file type) and its checksum tail. */
    private fun dirBlock(seed: Int, dirIno: Int, dirInode: ByteArray, entries: List<Triple<Int, String, Int>>): ByteArray {
        val block = ByteArray(BLOCK_SIZE)
        val b = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        var off = 0
        entries.forEachIndexed { n, (ino, name, type) ->
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val len = if (n == entries.lastIndex) BLOCK_SIZE - 12 - off else (8 + nameBytes.size + 3) / 4 * 4
            b.putInt(off, ino); b.putShort(off + 4, len.toShort())
            block[off + 6] = nameBytes.size.toByte(); block[off + 7] = type.toByte()
            System.arraycopy(nameBytes, 0, block, off + 8, nameBytes.size)
            off += len
        }
        val tail = BLOCK_SIZE - 12
        b.putShort(tail + 4, 12); block[tail + 7] = 0xDE.toByte()
        val gen = ByteBuffer.wrap(dirInode).order(ByteOrder.LITTLE_ENDIAN).getInt(100)
        val csum = Ext4Crc.update(Ext4Crc.update(Ext4Crc.update(seed, Ext4Crc.leInt(dirIno)),
            Ext4Crc.leInt(gen)), block, 0, tail)
        b.putInt(tail + 8, csum)
        return block
    }

    /** jbd2 v2 superblock for an empty internal journal (big-endian, unlike ext4). */
    private fun journalSuperblock(uuid: ByteArray, blocks: Int): ByteArray {
        val j = ByteArray(BLOCK_SIZE)
        val b = ByteBuffer.wrap(j).order(ByteOrder.BIG_ENDIAN)
        b.putInt(0, 0xC03B3998.toInt()); b.putInt(4, 4)
        b.putInt(12, BLOCK_SIZE); b.putInt(16, blocks); b.putInt(20, 1)
        b.putInt(24, 1)                               // s_sequence
        b.putInt(28, 0)                               // s_start: empty
        System.arraycopy(uuid, 0, j, 48, 16)
        b.putInt(64, 1)                               // s_nr_users
        return j
    }

    private fun setBit(bitmap: ByteArray, i: Int) {
        bitmap[i ushr 3] = (bitmap[i ushr 3].toInt() or (1 shl (i and 7))).toByte()
    }

    private fun putLong(d: ByteBuffer, lo: Int, hi: Int, v: Long) { d.putInt(lo, v.toInt()); d.putInt(hi, (v ushr 32).toInt()) }
    private fun putCount(d: ByteBuffer, lo: Int, hi: Int, v: Long) { d.putShort(lo, v.toShort()); d.putShort(hi, (v ushr 16).toShort()) }

    /** Writes whole ext4 blocks to a device with smaller (or equal) sectors. */
    private class BlockWriter(private val device: RawBlockDevice) {
        private val perBlock = BLOCK_SIZE / device.blockSize
        fun write(block: Long, data: ByteArray) {
            require(data.size % BLOCK_SIZE == 0)
            var off = 0
            val chunk = 256 * BLOCK_SIZE
            while (off < data.size) {
                val n = minOf(chunk, data.size - off)
                device.writeBlocks((block + off / BLOCK_SIZE) * perBlock, if (off == 0 && n == data.size) data else data.copyOfRange(off, off + n))
                off += n
            }
        }
    }
}

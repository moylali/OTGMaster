package app.fayaz.otgmaster.ext4

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Replays an ext4 volume's internal jbd2 journal, the way the kernel does at mount and
 * e2fsck does before a check (`recovery.c`, which the two share).
 *
 * Why this exists: a volume whose journal holds committed transactions not yet written
 * to their home blocks is flagged `needs_recovery`, and this driver refuses to write it
 * (see [Ext4FileSystem.readOnlyReason]). Android mounts ext4 USB volumes itself, so on a
 * phone that mount is routinely cut off when OTG Master claims the drive, and the volume
 * stays read-only until it goes back to a computer. Replaying the journal here is what
 * Linux would do on the next mount, so after it the volume is in the state Linux would
 * have left it in.
 *
 * Three passes over the log, as in recovery.c:
 *  1. **scan** from `s_start`: follow descriptor, revoke and commit blocks while their
 *     sequence number is the one expected. The log ends at the first block that is not
 *     the next one, and a transaction without its commit block is discarded;
 *  2. **revoke**: collect the revoke records of the committed transactions. A block
 *     revoked by transaction N is not replayed from any transaction up to N;
 *  3. **replay**: copy each logged block to its home location, oldest transaction first.
 *
 * Unlike recovery.c it refuses rather than skipping what it cannot trust. A descriptor
 * or revoke block, or a logged data block, that fails its checksum inside a committed
 * transaction stops the whole repair before anything is written, and the volume stays
 * read-only for e2fsck to handle. A commit block failing its checksum ends the log there,
 * as in recovery.c: that is a transaction cut off while it was being committed.
 *
 * jbd2 structures are big-endian, unlike the rest of ext4.
 */
object Ext4Journal {

    class Result(val transactions: Int, val blocksReplayed: Int, val blocksRevoked: Int)

    /** The journal cannot be replayed safely by this driver; nothing was written. */
    class NotReplayableException(message: String) : IOException(message)

    private const val JBD2_MAGIC = 0xC03B3998.toInt()

    private const val DESCRIPTOR_BLOCK = 1
    private const val COMMIT_BLOCK = 2
    private const val SUPERBLOCK_V1 = 3
    private const val SUPERBLOCK_V2 = 4
    private const val REVOKE_BLOCK = 5

    private const val INCOMPAT_REVOKE = 0x1
    private const val INCOMPAT_64BIT = 0x2
    private const val INCOMPAT_ASYNC_COMMIT = 0x4
    private const val INCOMPAT_CSUM_V2 = 0x8
    private const val INCOMPAT_CSUM_V3 = 0x10
    private const val INCOMPAT_FAST_COMMIT = 0x20
    private const val KNOWN_INCOMPAT = INCOMPAT_REVOKE or INCOMPAT_64BIT or
        INCOMPAT_ASYNC_COMMIT or INCOMPAT_CSUM_V2 or INCOMPAT_CSUM_V3

    private const val FLAG_ESCAPE = 0x1
    private const val FLAG_SAME_UUID = 0x2
    private const val FLAG_LAST_TAG = 0x8

    private const val CHECKSUM_TYPE_CRC32C = 4

    /** ext4 superblock fields: s_journal_inum and s_journal_dev. */
    private const val SB_JOURNAL_INUM = 0xE0
    private const val SB_JOURNAL_DEV = 0xE4

    /** One logged block: where it goes, where it sits in the log, and its tag's checksum. */
    private class Tag(val fsBlock: Long, val logBlock: Long, val flags: Int, val csum: Int)

    private class Transaction(val sequence: Int) {
        val tags = mutableListOf<Tag>()
        val revoked = mutableListOf<Long>()
    }

    /**
     * Replay [fs]'s journal and clear `needs_recovery`. Writes through [fs]'s own device,
     * so its block cache stays consistent with what lands on disk.
     */
    internal fun recover(fs: Ext4FileSystem): Result {
        val sb = fs.readSuperblockBytes()
        val sbb = ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN)
        val journalInum = sbb.getInt(SB_JOURNAL_INUM).toLong() and 0xFFFFFFFFL
        val journalDev = sbb.getInt(SB_JOURNAL_DEV)
        if (journalInum == 0L || journalDev != 0)
            throw NotReplayableException("the journal is on another device")

        val journal = Ext4File.forInode(fs, journalInum, "<journal>", null)
        fun phys(logBlock: Long): Long = journal.physicalBlock(logBlock)
            ?: throw NotReplayableException("journal block $logBlock is not mapped")
        fun read(logBlock: Long): ByteArray = fs.readBlock(phys(logBlock)).copyOf()

        // --- the journal superblock --------------------------------------------------
        val jsb = read(0)
        val j = ByteBuffer.wrap(jsb).order(ByteOrder.BIG_ENDIAN)
        if (j.getInt(0) != JBD2_MAGIC) throw NotReplayableException("no journal superblock")
        val sbType = j.getInt(4)
        if (sbType != SUPERBLOCK_V1 && sbType != SUPERBLOCK_V2)
            throw NotReplayableException("unknown journal superblock type $sbType")
        if (j.getInt(0xC) != fs.blockSize)
            throw NotReplayableException("journal block size ${j.getInt(0xC)} is not the volume's ${fs.blockSize}")
        val maxLen = j.getInt(0x10).toLong() and 0xFFFFFFFFL
        val first = j.getInt(0x14).toLong() and 0xFFFFFFFFL
        val firstSequence = j.getInt(0x18)
        val start = j.getInt(0x1C).toLong() and 0xFFFFFFFFL
        val incompat = if (sbType == SUPERBLOCK_V2) j.getInt(0x28) else 0
        if ((incompat and KNOWN_INCOMPAT.inv()) != 0) {
            val what = if ((incompat and INCOMPAT_FAST_COMMIT) != 0) "fast commit"
                else "feature 0x${(incompat and KNOWN_INCOMPAT.inv()).toString(16)}"
            throw NotReplayableException("the journal uses $what, which this app does not replay")
        }
        val is64 = (incompat and INCOMPAT_64BIT) != 0
        val csum3 = (incompat and INCOMPAT_CSUM_V3) != 0
        val csum2 = (incompat and INCOMPAT_CSUM_V2) != 0
        val hasCsum = csum2 || csum3
        val journalBlocks = (journal.length + fs.blockSize - 1) / fs.blockSize
        if (first < 1 || first >= maxLen || maxLen > journalBlocks)
            throw NotReplayableException("journal geometry first=$first maxlen=$maxLen does not fit its inode")

        var seed = 0
        if (hasCsum) {
            if ((jsb[0x50].toInt() and 0xFF) != CHECKSUM_TYPE_CRC32C)
                throw NotReplayableException("journal checksum type ${jsb[0x50]} is not crc32c")
            val stored = j.getInt(0xFC)
            j.putInt(0xFC, 0)
            val calc = Ext4Crc.update(Ext4Crc.SEED, jsb, 0, 1024)
            j.putInt(0xFC, stored)
            if (calc != stored) throw NotReplayableException("the journal superblock fails its checksum")
            seed = Ext4Crc.update(Ext4Crc.SEED, jsb, 0x30, 16)
        }

        if (start == 0L) {
            // An empty log: the flag outlived the journal's own record of it.
            clearNeedsRecovery(fs)
            return Result(0, 0, 0)
        }
        if (start < first || start >= maxLen)
            throw NotReplayableException("journal start $start is outside the log ($first..$maxLen)")

        fun next(b: Long): Long { val n = b + 1; return if (n >= maxLen) n - (maxLen - first) else n }

        fun blockCsumOk(buf: ByteArray): Boolean {
            val tail = buf.size - 4
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN)
            val stored = bb.getInt(tail)
            bb.putInt(tail, 0)
            val calc = Ext4Crc.update(seed, buf)
            bb.putInt(tail, stored)
            return calc == stored
        }

        fun commitCsumOk(buf: ByteArray): Boolean {
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN)
            val stored = bb.getInt(16)
            bb.putInt(16, 0)
            val calc = Ext4Crc.update(seed, buf)
            bb.putInt(16, stored)
            return calc == stored
        }

        val tagBytes = when {
            csum3 -> 16
            else -> 12 + (if (csum2) 2 else 0) - (if (is64) 0 else 4)
        }
        val tailBytes = if (hasCsum) 4 else 0

        // --- pass 1: scan ------------------------------------------------------------
        val committed = mutableListOf<Transaction>()
        var block = start
        var sequence = firstSequence
        var current = Transaction(sequence)
        var steps = 0L
        scan@ while (steps++ <= maxLen) {
            val buf = read(block)
            val h = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN)
            if (h.getInt(0) != JBD2_MAGIC || h.getInt(8) != sequence) break
            when (h.getInt(4)) {
                DESCRIPTOR_BLOCK -> {
                    if (hasCsum && !blockCsumOk(buf))
                        throw NotReplayableException("descriptor block at journal block $block fails its checksum")
                    var off = 12
                    var dataBlock = next(block)
                    while (off + tagBytes <= buf.size - tailBytes) {
                        val lo = h.getInt(off).toLong() and 0xFFFFFFFFL
                        val flags: Int
                        val csum: Int
                        var hi = 0L
                        if (csum3) {
                            flags = h.getInt(off + 4)
                            if (is64) hi = h.getInt(off + 8).toLong() and 0xFFFFFFFFL
                            csum = h.getInt(off + 12)
                        } else {
                            csum = h.getShort(off + 4).toInt() and 0xFFFF
                            flags = h.getShort(off + 6).toInt() and 0xFFFF
                            if (is64) hi = h.getInt(off + 8).toLong() and 0xFFFFFFFFL
                        }
                        current.tags += Tag((hi shl 32) or lo, dataBlock, flags, csum)
                        dataBlock = next(dataBlock)
                        off += tagBytes
                        if ((flags and FLAG_SAME_UUID) == 0) off += 16
                        if ((flags and FLAG_LAST_TAG) != 0) break
                    }
                    block = dataBlock
                }
                COMMIT_BLOCK -> {
                    // A commit that fails its checksum was being written when the
                    // volume went away: the log ends before it.
                    if (hasCsum && !commitCsumOk(buf)) break@scan
                    committed += current
                    sequence++
                    current = Transaction(sequence)
                    block = next(block)
                }
                REVOKE_BLOCK -> {
                    if (hasCsum && !blockCsumOk(buf))
                        throw NotReplayableException("revoke block at journal block $block fails its checksum")
                    val used = h.getInt(12)
                    if (used < 16 || used > buf.size - tailBytes)
                        throw NotReplayableException("revoke block at journal block $block is malformed")
                    val recordBytes = if (is64) 8 else 4
                    var off = 16
                    while (off + recordBytes <= used) {
                        current.revoked += if (is64) h.getLong(off) else (h.getInt(off).toLong() and 0xFFFFFFFFL)
                        off += recordBytes
                    }
                    block = next(block)
                }
                else -> break@scan
            }
        }

        // --- pass 2: revoke, and check everything before writing anything -----------
        val revokedUpTo = HashMap<Long, Int>()
        for (t in committed) for (b in t.revoked) {
            val prev = revokedUpTo[b]
            if (prev == null || t.sequence - prev > 0) revokedUpTo[b] = t.sequence
        }
        fun isRevoked(b: Long, seq: Int): Boolean = revokedUpTo[b]?.let { it - seq >= 0 } ?: false

        var revokedCount = 0
        for (t in committed) for (tag in t.tags) {
            if (isRevoked(tag.fsBlock, t.sequence)) { revokedCount++; continue }
            // Block 0 is legitimate: on a 4 KiB-block volume it holds the superblock,
            // which the kernel journals in nearly every transaction.
            if (tag.fsBlock < 0 || tag.fsBlock >= fs.totalBlocks)
                throw NotReplayableException("journal transaction ${t.sequence} logs block ${tag.fsBlock}, outside the volume")
            if (hasCsum) {
                val data = read(tag.logBlock)
                val seqBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(t.sequence).array()
                val calc = Ext4Crc.update(Ext4Crc.update(seed, seqBytes), data)
                val ok = if (csum3) calc == tag.csum else (calc and 0xFFFF) == tag.csum
                if (!ok) throw NotReplayableException(
                    "logged copy of block ${tag.fsBlock} in transaction ${t.sequence} fails its checksum")
            }
        }

        // --- pass 3: replay ----------------------------------------------------------
        var replayed = 0
        for (t in committed) for (tag in t.tags) {
            if (isRevoked(tag.fsBlock, t.sequence)) continue
            val data = read(tag.logBlock)
            if ((tag.flags and FLAG_ESCAPE) != 0)
                ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).putInt(0, JBD2_MAGIC)
            fs.writeBlocksUnchecked(tag.fsBlock, data)
            replayed++
        }

        // --- an empty log, then a volume that no longer needs recovery --------------
        // Same as recovery.c: the next transaction is one past the end of the scan.
        val jsbNow = read(0)
        val jn = ByteBuffer.wrap(jsbNow).order(ByteOrder.BIG_ENDIAN)
        jn.putInt(0x18, sequence + 1)
        jn.putInt(0x1C, 0)
        if (hasCsum) {
            jn.putInt(0xFC, 0)
            jn.putInt(0xFC, Ext4Crc.update(Ext4Crc.SEED, jsbNow, 0, 1024))
        }
        fs.writeBlocksUnchecked(phys(0), jsbNow)
        clearNeedsRecovery(fs)
        return Result(committed.size, replayed, revokedCount)
    }

    /** Clear `needs_recovery`, re-reading the superblock in case the replay rewrote it. */
    private fun clearNeedsRecovery(fs: Ext4FileSystem) {
        val sb = fs.readSuperblockBytes()
        val bb = ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(96, bb.getInt(96) and Ext4FileSystem.INCOMPAT_RECOVER.inv())
        fs.writeSuperblockBytes(sb, recovering = true)
    }
}

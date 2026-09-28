package app.fayaz.otgmaster.ext4

import me.jahnen.libaums.core.fs.UsbFile
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [UsbFile] backed by an ext2/3/4 inode.  Supports both read and write.
 *
 * Write safety:
 *  - The filesystem is marked dirty (s_state) before any write begins.
 *  - Data blocks are written before metadata (extent tree, inode size, bitmaps).
 *  - [write] and the [length] setter flush the inode before returning. The
 *    bitmap and superblock are made durable by the allocator as soon as blocks
 *    are taken, so an inode left dirty in memory describes blocks the volume
 *    already considers allocated — orphaned the moment the caller walks away or
 *    the drive is pulled. Metadata lands with the data it describes.
 *  - [flush] / [close] remain for callers that mutate through other paths.
 *  - The journal is bypassed entirely; on an unclean disconnect Linux will run
 *    fsck and recover correctly from the safe write order.
 *
 * Limitations:
 *  - Only extent-tree files are writable.  Legacy block-map files (ext2/3) can
 *    still be read but writes on them throw [UnsupportedOperationException].
 *  - No inline-data writes.
 *  - The extent tree grows to depth 1 only: four inline index entries, each
 *    pointing at one leaf block.  Sequential runs are merged into the preceding
 *    extent, so capacity is bounded by fragmentation rather than file size, but
 *    a sufficiently fragmented file still fails with "extent tree full".
 *  - Index entries in a depth-1 tree are appended unsorted, which breaks
 *    extentSearch's ei_block ordering assumption for out-of-order writes.
 */
class Ext4File private constructor(
    private val fs: Ext4FileSystem,
    internal val inodeNum: Long,
    @Volatile private var entryName: String,
    @Volatile override var parent: UsbFile?,
    private val isDir_: Boolean,
    @Volatile private var currentSize: Long,
    private val atimeMs: Long,
    @Volatile private var mtimeMs: Long,
    @Volatile private var ctimeMs: Long,
    private var inode: ByteArray,
) : UsbFile {

    @Volatile private var dirty = false

    companion object {
        private const val EXT4_EXTENTS_FL    = 0x00080000
        private const val EXT4_INLINE_DATA_FL = 0x10000000
        private const val EXTENT_MAGIC       = 0xF30A
        /** Max blocks in an initialised extent; above this ee_len flags it uninitialised. */
        private const val MAX_INIT_EXTENT_LEN = 32768
        private const val S_IFDIR            = 0x4000
        private const val S_IFLNK            = 0xA000
        private const val S_IFREG            = 0x8000

        // File-type codes used in directory entries.
        private const val FT_UNKNOWN  = 0
        private const val FT_REG_FILE = 1
        private const val FT_DIR      = 2

        fun forInode(fs: Ext4FileSystem, inodeNum: Long, name: String, parent: UsbFile?): Ext4File {
            val inode = fs.readInode(inodeNum)
            val bb = ByteBuffer.wrap(inode).order(ByteOrder.LITTLE_ENDIAN)

            val mode   = bb.getShort(0).toInt() and 0xFFFF
            val isDir  = (mode and 0xF000) == S_IFDIR
            val sizeLo = bb.getInt(4).toLong() and 0xFFFFFFFFL
            val sizeHi = if (!isDir) (bb.getInt(108).toLong() and 0xFFFFFFFFL) else 0L
            val size   = (sizeHi shl 32) or sizeLo

            val atime  = (bb.getInt(8).toLong()  and 0xFFFFFFFFL) * 1000L
            val ctime  = (bb.getInt(12).toLong() and 0xFFFFFFFFL) * 1000L
            val mtime  = (bb.getInt(16).toLong() and 0xFFFFFFFFL) * 1000L

            return Ext4File(fs, inodeNum, name, parent, isDir, size, atime, mtime, ctime, inode)
        }

        /** Allocate and initialise a new inode for a regular file. */
        fun createNew(
            fs: Ext4FileSystem,
            name: String,
            parent: UsbFile?,
            isDirectory: Boolean,
        ): Ext4File {
            val inodeNum = fs.allocator.allocateInode(isDirectory)
            val inode = ByteArray(fs.inodeSize)
            val now = (System.currentTimeMillis() / 1000L).toInt()
            ByteBuffer.wrap(inode).order(ByteOrder.LITTLE_ENDIAN).apply {
                val mode = if (isDirectory) 0x41ED else 0x81A4  // 0755 dir, 0644 file
                putShort(0, mode.toShort())
                putInt(4, 0)      // i_size_lo
                putInt(8, now)    // i_atime
                putInt(12, now)   // i_ctime
                putInt(16, now)   // i_mtime
                putInt(20, 0)     // i_dtime
                putShort(26, (if (isDirectory) 2 else 1).toShort())  // i_links_count
                putInt(32, EXT4_EXTENTS_FL)  // i_flags: extents
                if (inode.size > 128) putShort(128, 28.toShort())  // i_extra_isize
                // Initialise the extent tree header at inode offset 40.
                putShort(40, EXTENT_MAGIC.toShort())  // eh_magic
                putShort(42, 0.toShort())              // eh_entries = 0
                putShort(44, 4.toShort())              // eh_max = 4 (inline)
                putShort(46, 0.toShort())              // eh_depth = 0
                putInt(48, 0)                           // eh_generation
            }
            val f = Ext4File(fs, inodeNum, name, parent, isDirectory, 0L,
                now * 1000L, now * 1000L, now * 1000L, inode)
            f.dirty = true
            return f
        }
    }

    // -----------------------------------------------------------------------
    // UsbFile properties
    // -----------------------------------------------------------------------

    override val isDirectory: Boolean   get() = isDir_
    override var name: String
        get() = entryName
        @Throws(IOException::class)
        set(newName) { renameInParent(newName) }

    override val absolutePath: String   get() =
        if (parent == null) "/" else "${parent!!.absolutePath.trimEnd('/')}/$entryName"

    override var length: Long
        get() = currentSize
        @Throws(IOException::class)
        set(newLength) { truncateTo(newLength) }

    override val isRoot: Boolean        get() = parent == null

    override fun createdAt(): Long      = ctimeMs
    override fun lastModified(): Long   = mtimeMs
    override fun lastAccessed(): Long   = atimeMs

    // -----------------------------------------------------------------------
    // Directory listing (unchanged from read-only)
    // -----------------------------------------------------------------------

    override fun list(): Array<String>   = listFiles().map { it.name }.toTypedArray()

    override fun listFiles(): Array<UsbFile> {
        if (!isDir_) throw IOException("Not a directory: $entryName")
        val result = mutableListOf<UsbFile>()
        val flags = inodesFlags()

        if (flags and EXT4_INLINE_DATA_FL != 0) {
            val inline = inode.copyOfRange(40, 100)
            parseDirBlock(inline, result)
            return result.toTypedArray()
        }

        val numBlocks = (currentSize + fs.blockSize - 1) / fs.blockSize
        for (blk in 0 until numBlocks) {
            val phys = resolveBlock(blk) ?: continue
            val data = fs.readBlock(phys)
            parseDirBlock(data, result)
        }
        return result.toTypedArray()
    }

    private fun parseDirBlock(block: ByteArray, out: MutableList<UsbFile>) {
        var pos = 0
        val tailReserve = if (fs.hasMetadataCsum) 12 else 0
        while (pos + 8 <= block.size - tailReserve) {
            val inoLo = (block[pos].toInt() and 0xFF) or
                        ((block[pos+1].toInt() and 0xFF) shl 8) or
                        ((block[pos+2].toInt() and 0xFF) shl 16) or
                        ((block[pos+3].toInt() and 0xFF) shl 24)
            val ino    = inoLo.toLong() and 0xFFFFFFFFL
            val recLen = (block[pos+4].toInt() and 0xFF) or
                         ((block[pos+5].toInt() and 0xFF) shl 8)
            if (recLen < 8) break
            val nameLen = block[pos + 6].toInt() and 0xFF
            if (ino != 0L && nameLen > 0 && pos + 8 + nameLen <= block.size - tailReserve) {
                val n = String(block, pos + 8, nameLen, Charsets.UTF_8)
                if (n != "." && n != "..") {
                    out.add(forInode(fs, ino, n, this))
                }
            }
            pos += recLen
        }
    }

    // -----------------------------------------------------------------------
    // File reading (unchanged from read-only)
    // -----------------------------------------------------------------------

    override fun read(offset: Long, destination: ByteBuffer) {
        if (isDir_) throw IOException("Cannot read a directory as a file")
        val len = destination.remaining()
        val buf = readBytes(offset, len)
        destination.put(buf, 0, buf.size)
    }

    private fun readBytes(fileOffset: Long, len: Int): ByteArray {
        if (fileOffset >= currentSize) return ByteArray(0)
        val actualLen = minOf(len.toLong(), currentSize - fileOffset).toInt()

        val flags = inodesFlags()

        if (flags and EXT4_INLINE_DATA_FL != 0) {
            val inline = inode.copyOfRange(40, 100)
            val start  = fileOffset.toInt()
            val end    = minOf(start + actualLen, inline.size)
            return if (start >= inline.size) ByteArray(0) else inline.copyOfRange(start, end)
        }

        val result  = ByteArray(actualLen)
        var written = 0
        var pos     = fileOffset

        while (written < actualLen) {
            val logicalBlock = pos / fs.blockSize
            val blockOff     = (pos % fs.blockSize).toInt()
            val phys         = resolveBlock(logicalBlock)
            val toCopy       = minOf(actualLen - written, fs.blockSize - blockOff)

            if (phys != null) {
                val data = fs.readBlock(phys)
                System.arraycopy(data, blockOff, result, written, toCopy)
            }
            written += toCopy
            pos     += toCopy
        }
        return result
    }

    // -----------------------------------------------------------------------
    // File writing
    // -----------------------------------------------------------------------

    /**
     * Write [source] into the file at [offset].  Allocates new blocks as
     * needed and grows the extent tree.  Only extent-tree files are supported;
     * inline-data and legacy block-map files throw.
     */
    @Throws(IOException::class)
    override fun write(offset: Long, source: ByteBuffer) {
        if (isDir_) throw IOException("Cannot write to a directory")
        val flags = inodesFlags()
        if (flags and EXT4_EXTENTS_FL == 0)
            throw UnsupportedOperationException("Write not supported for legacy block-map inodes")
        if (flags and EXT4_INLINE_DATA_FL != 0)
            throw UnsupportedOperationException("Write not supported for inline-data inodes")

        fs.markDirty()

        val data = ByteArray(source.remaining()).also { source.get(it) }

        // Pre-scan: find every logical block that needs a fresh physical allocation.
        // Batch-allocating all of them at once lets the allocator return a contiguous
        // run of physical blocks, which can then be registered as a single extent
        // entry instead of one entry per block — keeping the extent tree shallow.
        val endOff = offset + data.size
        val newLogical = mutableListOf<Long>()
        var scanPos = offset
        while (scanPos < endOff) {
            val lb = scanPos / fs.blockSize
            if (resolveBlock(lb, mappedOnly = true) == null) newLogical.add(lb)
            scanPos = (lb + 1) * fs.blockSize
        }
        if (newLogical.isNotEmpty()) {
            val physBlocks = fs.allocator.allocateBlocks(newLogical.size)
            // Group consecutive (logical, physical) pairs into single extents.
            var i = 0
            while (i < newLogical.size) {
                val logStart  = newLogical[i]
                val physStart = physBlocks[i]
                // ee_len above 32768 marks an *uninitialized* extent of
                // (len - 32768) blocks, so a longer run must be split rather
                // than written as one entry — otherwise the tail of it is
                // invisible to every later walk of the tree, including the one
                // that frees blocks on delete.
                var run = 1
                while (i + run < newLogical.size &&
                       newLogical[i + run] == logStart + run &&
                       physBlocks[i + run] == physStart + run &&
                       run < MAX_INIT_EXTENT_LEN) run++
                appendExtent(logStart, physStart, run)
                i += run
            }
        }

        // Write the data block-by-block.  All needed blocks are now allocated.
        var written = 0
        var pos     = offset
        while (written < data.size) {
            val logicalBlock = pos / fs.blockSize
            val blockOff     = (pos % fs.blockSize).toInt()
            val phys         = resolveBlock(logicalBlock, mappedOnly = true)!!
            val toCopy       = minOf(data.size - written, fs.blockSize - blockOff)

            if (blockOff == 0 && toCopy == fs.blockSize) {
                fs.writeBlock(phys, data.copyOfRange(written, written + toCopy))
            } else {
                val block = fs.readBlock(phys).copyOf()
                System.arraycopy(data, written, block, blockOff, toCopy)
                fs.writeBlock(phys, block)
            }
            written += toCopy
            pos     += toCopy
        }

        val endPos = offset + data.size
        if (endPos > currentSize) {
            currentSize = endPos
            updateInodeSize(currentSize)
        }
        touchMtime()
        dirty = true
        // The bitmap and superblock were updated synchronously by allocateBlocks,
        // and the data blocks are already on the medium; only the inode still
        // knows where those blocks belong.  Leaving it dirty in memory means a
        // caller that never closes the file — or a drive pulled mid-write —
        // leaves blocks marked allocated that no inode claims, which is exactly
        // what e2fsck reports as a leak.  Metadata has to land with the data it
        // describes, not at the caller's convenience.
        flush()
    }

    @Throws(IOException::class)
    override fun flush() {
        if (!dirty) return
        val bb = ByteBuffer.wrap(inode).order(ByteOrder.LITTLE_ENDIAN)
        val nowSec = (System.currentTimeMillis() / 1000L).toInt()
        bb.putInt(12, nowSec)  // i_ctime
        fs.writeInode(inodeNum, inode)
        dirty = false
        fs.markClean()
    }

    @Throws(IOException::class)
    override fun close() {
        flush()
    }

    // -----------------------------------------------------------------------
    // Truncate / set length
    // -----------------------------------------------------------------------

    private fun truncateTo(newLength: Long) {
        if (newLength < 0) throw IllegalArgumentException("length must be >= 0")
        fs.markDirty()

        if (newLength > currentSize) {
            // Grow: allocate blocks to cover the new tail.  The bytes are already
            // zero (allocateBlocks zeroes new block bitmaps; the on-disk data of
            // freshly-allocated blocks is whatever was there before, but the caller
            // is expected to write before reading, which is the normal truncate-up
            // contract for empty pre-allocation).
            var pos = currentSize
            while (pos < newLength) {
                val logicalBlock = pos / fs.blockSize
                // mappedOnly: an uninitialised extent is already allocated, so
                // treating it as absent here would allocate over it and strand
                // the blocks it holds.
                if (resolveBlock(logicalBlock, mappedOnly = true) == null) {
                    val phys = fs.allocator.allocateBlocks(1)[0]
                    appendExtent(logicalBlock, phys, 1)
                    // Zero the newly-allocated block.
                    fs.writeBlock(phys, ByteArray(fs.blockSize))
                }
                pos += fs.blockSize
            }
        } else if (newLength < currentSize) {
            // Shrink: free blocks beyond the new end.
            val lastNeededBlock = if (newLength == 0L) -1L
                else (newLength - 1) / fs.blockSize
            freeBlocksAbove(lastNeededBlock)
        }

        currentSize = newLength
        updateInodeSize(newLength)
        touchMtime()
        dirty = true
        flush()  // same reasoning as write(): the freed/allocated bitmap is already durable
    }

    /**
     * Free all physical blocks mapped to logical blocks > [lastBlock], removing
     * the corresponding extents from the tree.
     *
     * Handles a depth-1 tree as well as the inline one: each leaf is trimmed in
     * place, and a leaf left with no extents is freed along with its index
     * entry.  Bailing out on depth > 0 leaked every block of any file large or
     * fragmented enough to have grown a tree — e2fsck reported them as still
     * allocated but owned by nothing.
     */
    private fun freeBlocksAbove(lastBlock: Long) {
        val bb = ByteBuffer.wrap(inode).order(ByteOrder.LITTLE_ENDIAN)
        val entries = bb.getShort(42).toInt() and 0xFFFF
        val depth   = bb.getShort(46).toInt() and 0xFFFF

        if (depth == 0) {
            bb.putShort(42, trimExtents(inode, 40, entries, lastBlock).toShort())
            dirty = true
            return
        }

        var keptIdx = 0
        for (i in 0 until entries) {
            val ix       = 52 + i * 12
            val leafLo   = bb.getInt(ix + 4).toLong() and 0xFFFFFFFFL
            val leafHi   = (bb.getShort(ix + 8).toInt() and 0xFFFF).toLong()
            val leafPhys = (leafHi shl 32) or leafLo
            val leafData = fs.readBlock(leafPhys).copyOf()
            val lb       = ByteBuffer.wrap(leafData).order(ByteOrder.LITTLE_ENDIAN)
            if ((lb.getShort(6).toInt() and 0xFFFF) != 0)
                throw IOException("Extent tree deeper than one level is not supported")

            val lEntries = lb.getShort(2).toInt() and 0xFFFF
            val kept = trimExtents(leafData, 0, lEntries, lastBlock)
            if (kept == 0) {
                fs.allocator.freeBlock(leafPhys)
                addToIBlocks(-1)  // the leaf block itself counted toward i_blocks
                continue
            }
            lb.putShort(2, kept.toShort())
            writeExtentBlock(leafPhys, leafData)
            if (keptIdx != i) System.arraycopy(inode, ix, inode, 52 + keptIdx * 12, 12)
            keptIdx++
        }

        bb.putShort(42, keptIdx.toShort())
        if (keptIdx == 0) {
            // Nothing left to index — collapse back to an empty inline tree so
            // the next write starts from depth 0 rather than a dangling index.
            bb.putShort(44, 4.toShort())
            bb.putShort(46, 0.toShort())
        }
        dirty = true
    }

    /**
     * Free every block above [lastBlock] in the extent array belonging to the
     * header at [offset] in [data], trimming an extent that straddles the
     * boundary.  Surviving extents are compacted to the front; returns how many
     * are left.
     */
    private fun trimExtents(data: ByteArray, offset: Int, entries: Int, lastBlock: Long): Int {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var kept = 0
        for (i in 0 until entries) {
            val e = offset + 12 + i * 12
            val eBlock = bb.getInt(e).toLong() and 0xFFFFFFFFL
            val eLen   = bb.getShort(e + 4).toInt() and 0xFFFF
            val count  = if (eLen > 32768) eLen - 32768 else eLen
            val startHi = (bb.getShort(e + 6).toInt() and 0xFFFF).toLong()
            val startLo = bb.getInt(e + 8).toLong() and 0xFFFFFFFFL
            val physStart = (startHi shl 32) or startLo

            if (eBlock > lastBlock) {
                // Entire extent is past the truncation point — free all its blocks.
                for (b in 0 until count) fs.allocator.freeBlock(physStart + b)
                addToIBlocks(-count)
                continue
            }
            if (eBlock + count - 1 > lastBlock) {
                // Extent straddles the boundary — keep the prefix, free the rest.
                val keepBlocks = (lastBlock - eBlock + 1).toInt()
                for (b in keepBlocks until count) fs.allocator.freeBlock(physStart + b)
                addToIBlocks(-(count - keepBlocks))
                bb.putShort(e + 4, keepBlocks.toShort())
            }
            if (kept != i) System.arraycopy(data, e, data, offset + 12 + kept * 12, 12)
            kept++
        }
        return kept
    }

    // -----------------------------------------------------------------------
    // Directory operations: create file, create directory, delete, move, rename
    // -----------------------------------------------------------------------

    @Throws(IOException::class)
    override fun createFile(name: String): UsbFile {
        if (!isDir_) throw IOException("Not a directory")
        if (listFiles().any { it.name == name })
            throw IOException("'$name' already exists")

        fs.markDirty()
        val child = createNew(fs, name, this, isDirectory = false)
        addDirEntry(child.inodeNum, name, FT_REG_FILE)
        child.flush()
        incrLinkCount(0)  // parent mtime update only
        touchMtime()
        dirty = true
        flush()  // the parent's own inode (size, i_blocks, mtime) must reach disk too
        return child
    }

    @Throws(IOException::class)
    override fun createDirectory(name: String): UsbFile {
        if (!isDir_) throw IOException("Not a directory")
        if (listFiles().any { it.name == name })
            throw IOException("'$name' already exists")

        fs.markDirty()
        val child = createNew(fs, name, this, isDirectory = true)

        // Add "." and ".." entries to the new directory's first block.
        val block = fs.allocator.allocateBlocks(1)[0]
        child.appendExtent(0L, block, 1)
        val blkData = ByteArray(fs.blockSize)
        var pos = 0

        fun writeDot(inoNum: Long, entName: String, ft: Int) {
            val nameBytes = entName.toByteArray(Charsets.UTF_8)
            val minLen    = 8 + nameBytes.size
            val recLen    = ((minLen + 3) and 3.inv())
            val bb = ByteBuffer.wrap(blkData, pos, recLen).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(inoNum.toInt())
            bb.putShort(recLen.toShort())
            bb.put(nameBytes.size.toByte())
            bb.put(ft.toByte())
            bb.put(nameBytes)
            pos += recLen
        }
        writeDot(child.inodeNum, ".", FT_DIR)
        writeDot(inodeNum, "..", FT_DIR)
        // Extend the last entry's rec_len to reach the end of the block (minus tail).
        val tailReserve = if (fs.hasMetadataCsum) 12 else 0
        // Find the position of the last entry and extend its rec_len.
        pos = 0
        var lastEntryPos = 0
        while (pos < blkData.size - tailReserve) {
            val entRecLen = (blkData[pos + 4].toInt() and 0xFF) or ((blkData[pos + 5].toInt() and 0xFF) shl 8)
            if (entRecLen == 0) break
            lastEntryPos = pos
            pos += entRecLen
        }
        val freeEnd = fs.blockSize - tailReserve - lastEntryPos
        ByteBuffer.wrap(blkData, lastEntryPos + 4, 2).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(freeEnd.toShort())

        writeDirBlock(child.inodeNum, child.inode, block, blkData)

        child.currentSize = fs.blockSize.toLong()
        child.updateInodeSize(fs.blockSize.toLong())
        child.flush()

        addDirEntry(child.inodeNum, name, FT_DIR)
        incrLinkCount(+1)  // parent link count +1 for the ".." back-reference
        touchMtime()
        dirty = true
        // Without this the bumped link count stays in memory and e2fsck reports
        // "Inode 2 ref count is 3, should be 4" for every directory created.
        flush()
        return child
    }

    @Throws(IOException::class)
    override fun delete() {
        val p = parent ?: throw IOException("Cannot delete root")

        fs.markDirty()

        if (isDir_) {
            // Must be empty (only "." and ".." entries remain).
            val children = listFiles()
            if (children.isNotEmpty())
                throw IOException("Directory not empty: $entryName")
            // Free directory blocks.
            freeBlocksAbove(-1L)
            // Decrement parent link count for the ".." entry.
            (p as? Ext4File)?.run { incrLinkCount(-1); touchMtime(); dirty = true; flush() }
        } else {
            freeBlocksAbove(-1L)
        }

        // Zero out the inode.
        inode.fill(0)
        ByteBuffer.wrap(inode).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(20, (System.currentTimeMillis() / 1000L).toInt())  // i_dtime
        fs.writeInode(inodeNum, inode)
        fs.allocator.freeInode(inodeNum, isDir_)

        // Remove this entry from the parent directory.
        (p as? Ext4File)?.removeDirEntry(inodeNum, entryName)
        (p as? Ext4File)?.run { touchMtime(); dirty = true; flush() }

        fs.markClean()
    }

    @Throws(IOException::class)
    override fun moveTo(destination: UsbFile) {
        val dst = destination as? Ext4File
            ?: throw IOException("moveTo: destination is not an ext4 directory")
        if (!dst.isDir_) throw IOException("moveTo: destination is not a directory")
        val src = parent as? Ext4File
            ?: throw IOException("moveTo: source parent unavailable")

        fs.markDirty()
        val ft = if (isDir_) FT_DIR else FT_REG_FILE
        dst.addDirEntry(inodeNum, entryName, ft)
        src.removeDirEntry(inodeNum, entryName)
        if (isDir_) {
            // Update ".." entry in the moved directory.
            updateDotDot(dst.inodeNum)
            src.incrLinkCount(-1)
            dst.incrLinkCount(+1)
            src.dirty = true
            dst.dirty = true
        }
        parent = dst
        touchMtime()
        dirty = true
        flush()
    }

    // -----------------------------------------------------------------------
    // Search
    // -----------------------------------------------------------------------

    override fun search(path: String): UsbFile? {
        var cur: UsbFile = this
        for (part in path.split("/").filter { it.isNotEmpty() }) {
            cur = (cur as? Ext4File)?.listFiles()?.firstOrNull { it.name == part } ?: return null
        }
        return cur
    }

    // -----------------------------------------------------------------------
    // Directory entry manipulation
    // -----------------------------------------------------------------------

    /**
     * Add a new directory entry for ([inoNum], [name]) to this directory.
     *
     * Finds the last existing entry and tries to split its slack space.  If
     * there is no slack, allocates a new directory block.
     */
    private fun addDirEntry(inoNum: Long, name: String, fileType: Int) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > 255) throw IOException("Name too long: $name")
        val needed = ((8 + nameBytes.size + 3) and 3.inv())
        val tailReserve = if (fs.hasMetadataCsum) 12 else 0

        val numBlocks = (currentSize + fs.blockSize - 1) / fs.blockSize
        for (blk in 0 until numBlocks) {
            val phys = resolveBlock(blk) ?: continue
            val data = fs.readBlock(phys).copyOf()
            if (addEntryToBlock(data, inoNum, nameBytes, needed, fileType, tailReserve)) {
                writeDirBlock(inodeNum, inode, phys, data)
                return
            }
        }

        // No space in existing blocks — allocate a new one.
        val newPhys = fs.allocator.allocateBlocks(1)[0]
        val newLogical = numBlocks
        appendExtent(newLogical, newPhys, 1)
        val data = ByteArray(fs.blockSize)
        // Write the new entry with rec_len = blockSize - tailReserve.
        val entryRecLen = fs.blockSize - tailReserve
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(inoNum.toInt())
            putShort(entryRecLen.toShort())
            put(nameBytes.size.toByte())
            put(fileType.toByte())
            position(8); put(nameBytes)
        }
        if (fs.hasMetadataCsum) writeTailChecksum(inodeNum, inode, data)
        writeDirBlock(inodeNum, inode, newPhys, data)

        currentSize += fs.blockSize
        updateInodeSize(currentSize)
        touchMtime()
        dirty = true
    }

    /**
     * Try to insert a new entry into [blockData].
     * Returns true if successful, false if there was no room.
     */
    private fun addEntryToBlock(
        blockData: ByteArray,
        inoNum: Long,
        nameBytes: ByteArray,
        needed: Int,
        fileType: Int,
        tailReserve: Int,
    ): Boolean {
        val bb = ByteBuffer.wrap(blockData).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos + 8 <= blockData.size - tailReserve) {
            val recLen  = bb.getShort(pos + 4).toInt() and 0xFFFF
            if (recLen == 0) break
            val nameLen = blockData[pos + 6].toInt() and 0xFF
            val minLen  = ((8 + nameLen + 3) and 3.inv())
            val slack   = recLen - minLen

            if (slack >= needed) {
                // Split: shrink existing entry to minLen, write new entry in the slack.
                bb.putShort(pos + 4, minLen.toShort())
                val newPos = pos + minLen
                bb.putInt(newPos, inoNum.toInt())
                bb.putShort(newPos + 4, slack.toShort())
                bb.put(newPos + 6, nameBytes.size.toByte())
                bb.put(newPos + 7, fileType.toByte())
                blockData.fill(0, newPos + 8, newPos + 8 + nameBytes.size)
                System.arraycopy(nameBytes, 0, blockData, newPos + 8, nameBytes.size)
                return true
            }
            pos += recLen
        }
        return false
    }

    /**
     * Remove the directory entry with ([inoNum], [name]) from this directory.
     */
    fun removeDirEntry(inoNum: Long, name: String) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val tailReserve = if (fs.hasMetadataCsum) 12 else 0
        val numBlocks = (currentSize + fs.blockSize - 1) / fs.blockSize

        for (blk in 0 until numBlocks) {
            val phys = resolveBlock(blk) ?: continue
            val data = fs.readBlock(phys).copyOf()
            val bb   = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            var pos  = 0
            var prevPos = -1

            while (pos + 8 <= data.size - tailReserve) {
                val ino     = bb.getInt(pos).toLong() and 0xFFFFFFFFL
                val recLen  = bb.getShort(pos + 4).toInt() and 0xFFFF
                if (recLen == 0) break
                val nameLen = data[pos + 6].toInt() and 0xFF
                if (ino == inoNum && nameLen == nameBytes.size &&
                    data.regionMatches(pos + 8, nameBytes, 0, nameLen)) {

                    if (prevPos >= 0) {
                        // Absorb into the previous entry's rec_len.
                        val prevRecLen = bb.getShort(prevPos + 4).toInt() and 0xFFFF
                        bb.putShort(prevPos + 4, (prevRecLen + recLen).toShort())
                    } else {
                        // First entry — zero the inode so it is skipped.
                        bb.putInt(pos, 0)
                    }
                    writeDirBlock(inodeNum, inode, phys, data)
                    return
                }
                prevPos = pos
                pos += recLen
            }
        }
    }

    /** Write a directory block with the tail checksum if required. */
    private fun writeDirBlock(dirIno: Long, dirInode: ByteArray, phys: Long, data: ByteArray) {
        if (fs.hasMetadataCsum) writeTailChecksum(dirIno, dirInode, data)
        fs.writeBlock(phys, data)
    }

    /**
     * Write an extent tree block (leaf or index), embedding its tail checksum.
     *
     * An extent block carries a 4-byte et_checksum immediately after the space
     * eh_max reserves for entries, over the bytes before it under the per-inode
     * seed.  Omitting it made e2fsck reject every extent block once a file grew
     * a tree of its own: "extent block passes checks, but checksum does not
     * match extent".
     */
    private fun writeExtentBlock(phys: Long, data: ByteArray) {
        if (fs.hasMetadataCsum) {
            val ehMax = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                .getShort(4).toInt() and 0xFFFF
            val tailOff = 12 + ehMax * 12
            if (tailOff + 4 <= data.size) {
                val csum = fs.csumWithInodeSeed(inodeNum, inode, data.copyOfRange(0, tailOff))
                ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(tailOff, csum)
            }
        }
        fs.writeBlock(phys, data)
    }

    /**
     * Write the CRC32c tail entry at the end of a directory block.
     * The tail is the last 12 bytes of the block.
     */
    private fun writeTailChecksum(dirIno: Long, dirInode: ByteArray, data: ByteArray) {
        val tailOff = data.size - 12
        // Zero the checksum field (last 4 bytes of the tail) before computing.
        data[tailOff + 8] = 0; data[tailOff + 9] = 0
        data[tailOff + 10] = 0; data[tailOff + 11] = 0
        // Write the tail header if not already present.
        ByteBuffer.wrap(data, tailOff, 12).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0)            // det_reserved_zero1
            putShort(12.toShort()) // det_rec_len
            put(0)               // det_reserved_name_len
            put(0xDE.toByte())   // det_reserved_ft
        }
        // Re-zero checksum after writing header (putInt above may have non-zero values).
        data[tailOff + 8] = 0; data[tailOff + 9] = 0
        data[tailOff + 10] = 0; data[tailOff + 11] = 0
        // The checksum covers the dirents only — the first blockSize-12 bytes,
        // excluding the tail itself.  Hashing the whole block made every
        // directory block fail: "directory passes checks but fails checksum".
        val csum = fs.csumDirBlock(dirIno, dirInode, data.copyOfRange(0, tailOff))
        ByteBuffer.wrap(data, tailOff + 8, 4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(csum)
    }

    // -----------------------------------------------------------------------
    // Extent tree helpers
    // -----------------------------------------------------------------------

    /**
     * Append a new extent covering [count] physical blocks starting at [physStart]
     * for logical block [logicalBlock].
     *
     * If the inline extent tree (up to 4 entries) is full, an index block is
     * allocated and the tree grows to depth 1.
     */
    private fun appendExtent(logicalBlock: Long, physStart: Long, count: Int) {
        addToIBlocks(count)
        val bb      = ByteBuffer.wrap(inode).order(ByteOrder.LITTLE_ENDIAN)
        val entries = bb.getShort(42).toInt() and 0xFFFF
        val maxInline = bb.getShort(44).toInt() and 0xFFFF
        val depth   = bb.getShort(46).toInt() and 0xFFFF

        // Grow the previous extent instead of adding a new one when this run
        // continues it both logically and physically.  A sequential write
        // arrives one call per chunk, so without merging a 4 GiB file needs
        // thousands of extents and overflows the tree ("Extent tree full");
        // merged, it needs one per 32767 blocks.
        if (depth == 0 && entries > 0) {
            val e = 52 + (entries - 1) * 12
            val pBlock = bb.getInt(e).toLong() and 0xFFFFFFFFL
            val pLen   = bb.getShort(e + 4).toInt() and 0xFFFF
            val pPhys  = ((bb.getShort(e + 6).toInt() and 0xFFFF).toLong() shl 32) or
                         (bb.getInt(e + 8).toLong() and 0xFFFFFFFFL)
            if (pLen in 1..32767 &&
                pBlock + pLen == logicalBlock &&
                pPhys + pLen == physStart &&
                pLen + count <= 32767
            ) {
                bb.putShort(e + 4, (pLen + count).toShort())
                return
            }
        }

        if (depth == 0 && entries < maxInline) {
            // Room in the inline leaf.
            val e = 52 + entries * 12
            bb.putInt(e, logicalBlock.toInt())
            bb.putShort(e + 4, count.toShort())
            bb.putShort(e + 6, (physStart ushr 32).toShort())
            bb.putInt(e + 8, (physStart and 0xFFFFFFFFL).toInt())
            bb.putShort(42, (entries + 1).toShort())
            return
        }

        if (depth == 0 && entries == maxInline) {
            // Inline tree full — allocate an index block, move extents there,
            // make the inline tree point to it (depth 1).
            val idxPhys = fs.allocator.allocateBlocks(1)[0]
            addToIBlocks(1)  // the extent tree's own blocks count toward i_blocks
            val idxData = ByteArray(fs.blockSize)
            val ib = ByteBuffer.wrap(idxData).order(ByteOrder.LITTLE_ENDIAN)
            // Write extent header for the leaf block.
            ib.putShort(0, EXTENT_MAGIC.toShort())
            ib.putShort(2, maxInline.toShort())                 // eh_entries = max (all moved)
            ib.putShort(4, ((fs.blockSize - 12) / 12).toShort()) // eh_max for a full block
            ib.putShort(6, 0.toShort())                          // depth 0
            ib.putInt(8, 0)
            // Copy existing inline extents.
            for (i in 0 until entries) {
                System.arraycopy(inode, 52 + i * 12, idxData, 12 + i * 12, 12)
            }
            // Add the new extent.
            val ne = 12 + entries * 12
            ib.putInt(ne, logicalBlock.toInt())
            ib.putShort(ne + 4, count.toShort())
            ib.putShort(ne + 6, (physStart ushr 32).toShort())
            ib.putInt(ne + 8, (physStart and 0xFFFFFFFFL).toInt())
            ib.putShort(2, (entries + 1).toShort())
            writeExtentBlock(idxPhys, idxData)

            // Rewrite the inode inline area as a depth-1 index with one entry.
            val firstBlock = bb.getInt(52).toLong() and 0xFFFFFFFFL  // first logical block
            inode.fill(0, 40, 40 + 60)
            bb.putShort(40, EXTENT_MAGIC.toShort())
            bb.putShort(42, 1.toShort())  // one index entry
            bb.putShort(44, 4.toShort())  // max 4 inline index entries
            bb.putShort(46, 1.toShort())  // depth = 1
            bb.putInt(48, 0)
            // Index entry: ei_block, ei_leaf_lo, ei_leaf_hi, ei_unused
            bb.putInt(52, firstBlock.toInt())
            bb.putInt(56, (idxPhys and 0xFFFFFFFFL).toInt())
            bb.putShort(60, (idxPhys ushr 32).toShort())
            bb.putShort(62, 0)
            return
        }

        if (depth == 1) {
            // Find the last index entry whose ei_block <= logicalBlock, read its leaf.
            val idxEntries = entries
            var targetIdx = 0
            for (i in 0 until idxEntries) {
                val ixBlock = bb.getInt(52 + i * 12).toLong() and 0xFFFFFFFFL
                if (ixBlock <= logicalBlock) targetIdx = i
            }
            val idxBase  = 52 + targetIdx * 12
            val leafLo   = bb.getInt(idxBase + 4).toLong() and 0xFFFFFFFFL
            val leafHi   = (bb.getShort(idxBase + 8).toInt() and 0xFFFF).toLong()
            val leafPhys = (leafHi shl 32) or leafLo
            val leafData = fs.readBlock(leafPhys).copyOf()
            val lb       = ByteBuffer.wrap(leafData).order(ByteOrder.LITTLE_ENDIAN)
            val lEntries = lb.getShort(2).toInt() and 0xFFFF
            val lMax     = lb.getShort(4).toInt() and 0xFFFF

            // Same merge as the inline case: a sequential write must not add one
            // extent per call once the tree has been promoted to depth 1, or a
            // 4 GiB file exhausts all four index entries.
            if (lEntries > 0) {
                val e = 12 + (lEntries - 1) * 12
                val pBlock = lb.getInt(e).toLong() and 0xFFFFFFFFL
                val pLen   = lb.getShort(e + 4).toInt() and 0xFFFF
                val pPhys  = ((lb.getShort(e + 6).toInt() and 0xFFFF).toLong() shl 32) or
                             (lb.getInt(e + 8).toLong() and 0xFFFFFFFFL)
                if (pLen in 1..32767 &&
                    pBlock + pLen == logicalBlock &&
                    pPhys + pLen == physStart &&
                    pLen + count <= 32767
                ) {
                    lb.putShort(e + 4, (pLen + count).toShort())
                    writeExtentBlock(leafPhys, leafData)
                    return
                }
            }

            if (lEntries < lMax) {
                val e = 12 + lEntries * 12
                lb.putInt(e, logicalBlock.toInt())
                lb.putShort(e + 4, count.toShort())
                lb.putShort(e + 6, (physStart ushr 32).toShort())
                lb.putInt(e + 8, (physStart and 0xFFFFFFFFL).toInt())
                lb.putShort(2, (lEntries + 1).toShort())
                writeExtentBlock(leafPhys, leafData)
                return
            }

            // Leaf is full — allocate a new leaf and add an index entry to the inline tree.
            if (idxEntries >= maxInline)
                throw IOException("Extent tree full (all $maxInline inline index entries used)")
            val newLeafPhys = fs.allocator.allocateBlocks(1)[0]
            addToIBlocks(1)  // the extent tree's own blocks count toward i_blocks
            val newLeafData = ByteArray(fs.blockSize)
            val nlb = ByteBuffer.wrap(newLeafData).order(ByteOrder.LITTLE_ENDIAN)
            nlb.putShort(0, EXTENT_MAGIC.toShort())
            nlb.putShort(2, 1.toShort())
            nlb.putShort(4, ((fs.blockSize - 12) / 12).toShort())
            nlb.putShort(6, 0.toShort())
            nlb.putInt(8, 0)
            nlb.putInt(12, logicalBlock.toInt())
            nlb.putShort(16, count.toShort())
            nlb.putShort(18, (physStart ushr 32).toShort())
            nlb.putInt(20, (physStart and 0xFFFFFFFFL).toInt())
            writeExtentBlock(newLeafPhys, newLeafData)

            val newIdxBase = 52 + idxEntries * 12
            bb.putInt(newIdxBase,     logicalBlock.toInt())
            bb.putInt(newIdxBase + 4, (newLeafPhys and 0xFFFFFFFFL).toInt())
            bb.putShort(newIdxBase + 8, (newLeafPhys ushr 32).toShort())
            bb.putShort(newIdxBase + 10, 0)
            bb.putShort(42, (idxEntries + 1).toShort())
            return
        }

        throw IOException("Extent tree depth $depth not supported for writes")
    }

    // -----------------------------------------------------------------------
    // Inode field helpers
    // -----------------------------------------------------------------------

    private fun updateInodeSize(size: Long) {
        val bb = ByteBuffer.wrap(inode).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(4, (size and 0xFFFFFFFFL).toInt())
        if (!isDir_) bb.putInt(108, (size ushr 32 and 0xFFFFFFFFL).toInt())
        dirty = true
    }

    /**
     * Adjust i_blocks_lo (inode offset 28) by [blockDelta] filesystem blocks.
     *
     * i_blocks counts 512-byte sectors, not filesystem blocks, and covers the
     * extent tree's own index and leaf blocks as well as data.  Leaving it at 0
     * is what made e2fsck report "i_blocks is 0, should be 8" for every inode
     * this code created.
     */
    private fun addToIBlocks(blockDelta: Int) {
        val sectorsPerBlock = fs.blockSize / 512
        val bb = ByteBuffer.wrap(inode).order(ByteOrder.LITTLE_ENDIAN)
        val current = bb.getInt(28).toLong() and 0xFFFFFFFFL
        val updated = (current + blockDelta.toLong() * sectorsPerBlock).coerceAtLeast(0L)
        bb.putInt(28, (updated and 0xFFFFFFFFL).toInt())
        dirty = true
    }

    private fun touchMtime() {
        val bb = ByteBuffer.wrap(inode).order(ByteOrder.LITTLE_ENDIAN)
        val now = (System.currentTimeMillis() / 1000L).toInt()
        bb.putInt(16, now)
        mtimeMs = now * 1000L
    }

    /** Adjust the inode's link count by [delta] (0 = only touch mtime). */
    private fun incrLinkCount(delta: Int) {
        if (delta == 0) { touchMtime(); return }
        val bb = ByteBuffer.wrap(inode).order(ByteOrder.LITTLE_ENDIAN)
        val links = (bb.getShort(26).toInt() and 0xFFFF) + delta
        bb.putShort(26, links.coerceAtLeast(0).toShort())
        touchMtime()
        dirty = true
    }

    /** Update the ".." entry in this directory to point to [newParentIno]. */
    private fun updateDotDot(newParentIno: Long) {
        val tailReserve = if (fs.hasMetadataCsum) 12 else 0
        val phys = resolveBlock(0) ?: return
        val data = fs.readBlock(phys).copyOf()
        val bb   = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var pos  = 0
        while (pos + 8 <= data.size - tailReserve) {
            val recLen  = bb.getShort(pos + 4).toInt() and 0xFFFF
            val nameLen = data[pos + 6].toInt() and 0xFF
            val n = String(data, pos + 8, nameLen, Charsets.UTF_8)
            if (n == "..") {
                bb.putInt(pos, newParentIno.toInt())
                writeDirBlock(inodeNum, inode, phys, data)
                return
            }
            pos += recLen
        }
    }

    /** Rename this entry in the parent directory. */
    private fun renameInParent(newName: String) {
        val p = parent as? Ext4File ?: throw IOException("Cannot rename: parent not available")
        fs.markDirty()
        p.addDirEntry(inodeNum, newName, if (isDir_) FT_DIR else FT_REG_FILE)
        p.removeDirEntry(inodeNum, entryName)
        entryName = newName
        touchMtime()
        dirty = true
        flush()
        p.touchMtime()
        p.dirty = true
        p.flush()
    }

    // -----------------------------------------------------------------------
    // Block resolution: extent tree or legacy block map (read, unchanged)
    // -----------------------------------------------------------------------

    /**
     * Physical block backing [logicalBlock], or null if nothing does.
     *
     * [mappedOnly] asks whether the block is *allocated*, which is what the
     * write path needs so it does not allocate over an existing mapping.  A
     * reader must leave it false: an extent flagged uninitialised is allocated
     * but its contents are undefined, and ext4 requires zeros to be returned
     * for it.  Reporting the physical block to a reader handed back whatever
     * those sectors still held, which on a volume with fallocate'd files —
     * torrent clients, VM images, databases all preallocate this way — means
     * serving the remains of previously deleted files.
     */
    private fun resolveBlock(logicalBlock: Long, mappedOnly: Boolean = false): Long? {
        return if (inodesFlags() and EXT4_EXTENTS_FL != 0)
            extentLookup(logicalBlock, mappedOnly)
        else
            blockMapLookup(logicalBlock)
    }

    private fun extentLookup(target: Long, mappedOnly: Boolean): Long? =
        extentSearch(inode, 40, target, mappedOnly)

    private fun extentSearch(
        data: ByteArray,
        offset: Int,
        target: Long,
        mappedOnly: Boolean,
    ): Long? {
        val bb      = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val magic   = bb.getShort(offset).toInt() and 0xFFFF
        if (magic != EXTENT_MAGIC) throw IOException(
            "Bad extent header magic 0x${magic.toString(16)} in inode $inodeNum"
        )
        val entries = bb.getShort(offset + 2).toInt() and 0xFFFF
        val depth   = bb.getShort(offset + 6).toInt() and 0xFFFF

        return if (depth == 0) {
            for (i in 0 until entries) {
                val e      = offset + 12 + i * 12
                val eBlock = bb.getInt(e).toLong() and 0xFFFFFFFFL
                val eLen   = bb.getShort(e + 4).toInt() and 0xFFFF
                val uninitialised = eLen > MAX_INIT_EXTENT_LEN
                val count  = if (uninitialised) eLen - MAX_INIT_EXTENT_LEN else eLen
                if (target >= eBlock && target < eBlock + count) {
                    // Allocated but undefined: a read must see zeros, which the
                    // caller produces by treating this as a hole.
                    if (uninitialised && !mappedOnly) return null
                    val startHi = (bb.getShort(e + 6).toInt() and 0xFFFF).toLong()
                    val startLo = bb.getInt(e + 8).toLong() and 0xFFFFFFFFL
                    return (startHi shl 32) or startLo + (target - eBlock)
                }
            }
            null
        } else {
            var childPhys = -1L
            for (i in 0 until entries) {
                val ix      = offset + 12 + i * 12
                val ixBlock = bb.getInt(ix).toLong() and 0xFFFFFFFFL
                if (ixBlock > target) break
                val leafLo  = bb.getInt(ix + 4).toLong() and 0xFFFFFFFFL
                val leafHi  = (bb.getShort(ix + 8).toInt() and 0xFFFF).toLong()
                childPhys   = (leafHi shl 32) or leafLo
            }
            if (childPhys < 0) return null
            val childData = fs.readBlock(childPhys)
            extentSearch(childData, 0, target, mappedOnly)
        }
    }

    private fun blockMapLookup(logical: Long): Long? {
        val bb            = ByteBuffer.wrap(inode).order(ByteOrder.LITTLE_ENDIAN)
        val pointersPerBlock = (fs.blockSize / 4).toLong()
        return when {
            logical < 12 -> {
                val ptr = bb.getInt(40 + logical.toInt() * 4).toLong() and 0xFFFFFFFFL
                if (ptr == 0L) null else ptr
            }
            logical < 12 + pointersPerBlock -> {
                val ind = bb.getInt(40 + 12 * 4).toLong() and 0xFFFFFFFFL
                if (ind == 0L) return null
                val indData = fs.readBlock(ind)
                val idx = (logical - 12).toInt()
                val ptr = ByteBuffer.wrap(indData).order(ByteOrder.LITTLE_ENDIAN)
                    .getInt(idx * 4).toLong() and 0xFFFFFFFFL
                if (ptr == 0L) null else ptr
            }
            logical < 12 + pointersPerBlock + pointersPerBlock * pointersPerBlock -> {
                val dind = bb.getInt(40 + 13 * 4).toLong() and 0xFFFFFFFFL
                if (dind == 0L) return null
                val offset1 = logical - 12 - pointersPerBlock
                val idx1 = (offset1 / pointersPerBlock).toInt()
                val idx2 = (offset1 % pointersPerBlock).toInt()
                val dindData = fs.readBlock(dind)
                val ind = ByteBuffer.wrap(dindData).order(ByteOrder.LITTLE_ENDIAN)
                    .getInt(idx1 * 4).toLong() and 0xFFFFFFFFL
                if (ind == 0L) return null
                val indData = fs.readBlock(ind)
                val ptr = ByteBuffer.wrap(indData).order(ByteOrder.LITTLE_ENDIAN)
                    .getInt(idx2 * 4).toLong() and 0xFFFFFFFFL
                if (ptr == 0L) null else ptr
            }
            else -> {
                val tind = bb.getInt(40 + 14 * 4).toLong() and 0xFFFFFFFFL
                if (tind == 0L) return null
                val offset1 = logical - 12 - pointersPerBlock - pointersPerBlock * pointersPerBlock
                val idx1 = (offset1 / (pointersPerBlock * pointersPerBlock)).toInt()
                val idx2 = ((offset1 / pointersPerBlock) % pointersPerBlock).toInt()
                val idx3 = (offset1 % pointersPerBlock).toInt()
                val tindData = fs.readBlock(tind)
                val dind = ByteBuffer.wrap(tindData).order(ByteOrder.LITTLE_ENDIAN)
                    .getInt(idx1 * 4).toLong() and 0xFFFFFFFFL
                if (dind == 0L) return null
                val dindData = fs.readBlock(dind)
                val ind = ByteBuffer.wrap(dindData).order(ByteOrder.LITTLE_ENDIAN)
                    .getInt(idx2 * 4).toLong() and 0xFFFFFFFFL
                if (ind == 0L) return null
                val indData = fs.readBlock(ind)
                val ptr = ByteBuffer.wrap(indData).order(ByteOrder.LITTLE_ENDIAN)
                    .getInt(idx3 * 4).toLong() and 0xFFFFFFFFL
                if (ptr == 0L) null else ptr
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun inodesFlags(): Int =
        ByteBuffer.wrap(inode).order(ByteOrder.LITTLE_ENDIAN).getInt(32)

    private fun ByteArray.regionMatches(
        thisOff: Int, other: ByteArray, otherOff: Int, len: Int,
    ): Boolean {
        for (i in 0 until len)
            if (this[thisOff + i] != other[otherOff + i]) return false
        return true
    }
}

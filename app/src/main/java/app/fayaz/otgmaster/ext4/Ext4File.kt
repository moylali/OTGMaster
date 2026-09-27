package app.fayaz.otgmaster.ext4

import me.jahnen.libaums.core.fs.UsbFile
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Read-only [UsbFile] backed by an ext2/3/4 inode.
 *
 * File data access:
 *  - Extent tree (EXT4_EXTENTS_FL): used by all ext4 volumes and any ext3 volume
 *    reformatted with tune2fs -E test_fs.
 *  - Block map (legacy): direct blocks (0..11), single, double, triple indirect.
 *    Covers ext2 and ext3 volumes up to the triple-indirect limit.
 *  - Inline data (EXT4_INLINE_DATA_FL): raw bytes stored in the inode's i_block
 *    area; capped at 60 bytes.
 *
 * Directory parsing:
 *  - Linear directories (the default for small directories).
 *  - Hash-tree (htree/dx) directories: the leaf blocks are ordinary linear
 *    directory blocks, so htree support reduces to following the two-level
 *    index to find the right leaf, then parsing it linearly.  For listing the
 *    full directory we skip the index and iterate all leaf blocks directly.
 */
class Ext4File private constructor(
    private val fs: Ext4FileSystem,
    private val inodeNum: Long,
    private val entryName: String,
    private val parentFile: UsbFile?,
    private val dir: Boolean,
    private val fileSize: Long,
    private val atimeMs: Long,
    private val mtimeMs: Long,
    private val ctimeMs: Long,
    private val inode: ByteArray,
) : UsbFile {

    companion object {
        private const val EXT4_EXTENTS_FL   = 0x00080000
        private const val EXT4_INLINE_DATA_FL = 0x10000000
        private const val EXTENT_MAGIC      = 0xF30A
        private const val S_IFDIR           = 0x4000
        private const val S_IFLNK           = 0xA000

        fun forInode(fs: Ext4FileSystem, inodeNum: Long, name: String, parent: UsbFile?): Ext4File {
            val inode = fs.readInode(inodeNum)
            val bb = ByteBuffer.wrap(inode).order(ByteOrder.LITTLE_ENDIAN)

            val mode   = bb.getShort(0).toInt() and 0xFFFF
            val isDir  = (mode and 0xF000) == S_IFDIR
            val sizeLo = bb.getInt(4).toLong() and 0xFFFFFFFFL
            // i_size_high (offset 108) is only valid for regular files; directories
            // use it for a different purpose in older kernels.
            val sizeHi = if (!isDir) (bb.getInt(108).toLong() and 0xFFFFFFFFL) else 0L
            val size   = (sizeHi shl 32) or sizeLo

            val atime  = (bb.getInt(8).toLong()  and 0xFFFFFFFFL) * 1000L
            val ctime  = (bb.getInt(12).toLong() and 0xFFFFFFFFL) * 1000L
            val mtime  = (bb.getInt(16).toLong() and 0xFFFFFFFFL) * 1000L

            return Ext4File(fs, inodeNum, name, parent, isDir, size, atime, mtime, ctime, inode)
        }
    }

    // -----------------------------------------------------------------------
    // UsbFile properties
    // -----------------------------------------------------------------------

    override val isDirectory: Boolean   get() = dir
    override var name: String           get() = entryName; set(_) = unsupported()
    override val absolutePath: String   get() =
        if (parentFile == null) "/" else "${parentFile.absolutePath.trimEnd('/')}/$entryName"
    override val parent: UsbFile?       get() = parentFile
    override var length: Long           get() = fileSize; set(_) = unsupported()
    override val isRoot: Boolean        get() = parentFile == null

    override fun createdAt(): Long      = ctimeMs
    override fun lastModified(): Long   = mtimeMs
    override fun lastAccessed(): Long   = atimeMs

    // -----------------------------------------------------------------------
    // Directory listing
    // -----------------------------------------------------------------------

    override fun list(): Array<String>   = listFiles().map { it.name }.toTypedArray()

    override fun listFiles(): Array<UsbFile> {
        if (!dir) throw IOException("Not a directory: $entryName")
        val result = mutableListOf<UsbFile>()
        val flags = inodesFlags()

        if (flags and EXT4_INLINE_DATA_FL != 0) {
            // Tiny directory whose entries fit in the i_block area (60 bytes).
            val inline = inode.copyOfRange(40, 100)
            parseDirBlock(inline, result)
            return result.toTypedArray()
        }

        val numBlocks = (fileSize + fs.blockSize - 1) / fs.blockSize
        for (blk in 0 until numBlocks) {
            val phys = resolveBlock(blk) ?: continue
            val data = fs.readBlock(phys)
            // Skip the htree root block (magic 0x2358 at offset 8); htree leaf
            // blocks look like ordinary linear directory blocks.
            parseDirBlock(data, result)
        }
        return result.toTypedArray()
    }

    private fun parseDirBlock(block: ByteArray, out: MutableList<UsbFile>) {
        var pos = 0
        while (pos + 8 <= block.size) {
            val inoLo  = (block[pos].toInt() and 0xFF) or
                         ((block[pos+1].toInt() and 0xFF) shl 8) or
                         ((block[pos+2].toInt() and 0xFF) shl 16) or
                         ((block[pos+3].toInt() and 0xFF) shl 24)
            val ino    = inoLo.toLong() and 0xFFFFFFFFL
            val recLen = (block[pos+4].toInt() and 0xFF) or
                         ((block[pos+5].toInt() and 0xFF) shl 8)
            if (recLen < 8) break
            val nameLen = block[pos + 6].toInt() and 0xFF
            if (ino != 0L && nameLen > 0 && pos + 8 + nameLen <= block.size) {
                val n = String(block, pos + 8, nameLen, Charsets.UTF_8)
                if (n != "." && n != "..") {
                    out.add(forInode(fs, ino, n, this))
                }
            }
            pos += recLen
        }
    }

    // -----------------------------------------------------------------------
    // File reading
    // -----------------------------------------------------------------------

    override fun read(offset: Long, destination: ByteBuffer) {
        if (dir) throw IOException("Cannot read a directory as a file")
        val len = destination.remaining()
        val buf = readBytes(offset, len)
        destination.put(buf, 0, buf.size)
    }

    private fun readBytes(fileOffset: Long, len: Int): ByteArray {
        val flags = inodesFlags()

        if (flags and EXT4_INLINE_DATA_FL != 0) {
            val inline = inode.copyOfRange(40, 100)
            val start  = fileOffset.toInt()
            val end    = minOf(start + len, inline.size)
            return if (start >= inline.size) ByteArray(0) else inline.copyOfRange(start, end)
        }

        val result  = ByteArray(len)
        var written = 0
        var pos     = fileOffset

        while (written < len) {
            val logicalBlock = pos / fs.blockSize
            val blockOff     = (pos % fs.blockSize).toInt()
            val phys         = resolveBlock(logicalBlock)
            val toCopy       = minOf(len - written, fs.blockSize - blockOff)

            if (phys == null) {
                // Sparse block — already zero in result.
            } else {
                val data = fs.readBlock(phys)
                System.arraycopy(data, blockOff, result, written, toCopy)
            }
            written += toCopy
            pos     += toCopy
        }
        return result
    }

    // -----------------------------------------------------------------------
    // Block resolution: extent tree or legacy block map
    // -----------------------------------------------------------------------

    private fun resolveBlock(logicalBlock: Long): Long? {
        return if (inodesFlags() and EXT4_EXTENTS_FL != 0)
            extentLookup(logicalBlock)
        else
            blockMapLookup(logicalBlock)
    }

    // --- Extent tree ---

    private fun extentLookup(target: Long): Long? =
        extentSearch(inode, 40, target)

    private fun extentSearch(data: ByteArray, offset: Int, target: Long): Long? {
        val bb      = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val magic   = bb.getShort(offset).toInt() and 0xFFFF
        if (magic != EXTENT_MAGIC) throw IOException(
            "Bad extent header magic 0x${magic.toString(16)} in inode $inodeNum"
        )
        val entries = bb.getShort(offset + 2).toInt() and 0xFFFF
        val depth   = bb.getShort(offset + 6).toInt() and 0xFFFF

        return if (depth == 0) {
            // Leaf: scan for the extent containing target.
            for (i in 0 until entries) {
                val e      = offset + 12 + i * 12
                val eBlock = bb.getInt(e).toLong() and 0xFFFFFFFFL
                val eLen   = bb.getShort(e + 4).toInt() and 0xFFFF
                // Lengths > 32768 mark uninitialized extents; subtract 32768 for actual count.
                val count  = if (eLen > 32768) eLen - 32768 else eLen
                if (target >= eBlock && target < eBlock + count) {
                    val startHi = (bb.getShort(e + 6).toInt() and 0xFFFF).toLong()
                    val startLo = bb.getInt(e + 8).toLong() and 0xFFFFFFFFL
                    return (startHi shl 32) or startLo + (target - eBlock)
                }
            }
            null  // sparse
        } else {
            // Index: find the last index whose ei_block <= target.
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
            extentSearch(childData, 0, target)
        }
    }

    // --- Legacy block map (ext2/3) ---

    private fun blockMapLookup(logical: Long): Long? {
        val bb            = ByteBuffer.wrap(inode).order(ByteOrder.LITTLE_ENDIAN)
        val pointersPerBlock = (fs.blockSize / 4).toLong()

        return when {
            // Direct blocks (0..11)
            logical < 12 -> {
                val ptr = bb.getInt(40 + logical.toInt() * 4).toLong() and 0xFFFFFFFFL
                if (ptr == 0L) null else ptr
            }
            // Single indirect
            logical < 12 + pointersPerBlock -> {
                val ind = bb.getInt(40 + 12 * 4).toLong() and 0xFFFFFFFFL
                if (ind == 0L) return null
                val indData = fs.readBlock(ind)
                val idx = (logical - 12).toInt()
                val ptr = ByteBuffer.wrap(indData).order(ByteOrder.LITTLE_ENDIAN)
                    .getInt(idx * 4).toLong() and 0xFFFFFFFFL
                if (ptr == 0L) null else ptr
            }
            // Double indirect
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
            // Triple indirect
            else -> {
                val tind = bb.getInt(40 + 14 * 4).toLong() and 0xFFFFFFFFL
                if (tind == 0L) return null
                val offset1 = logical - 12 - pointersPerBlock -
                        pointersPerBlock * pointersPerBlock
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
    // Unsupported write operations (read-only driver)
    // -----------------------------------------------------------------------

    override fun write(offset: Long, source: ByteBuffer): Unit = unsupported()
    override fun flush(): Unit                                  = unsupported()
    override fun createDirectory(name: String): UsbFile         = unsupported()
    override fun createFile(name: String): UsbFile              = unsupported()
    override fun moveTo(destination: UsbFile): Unit             = unsupported()
    override fun delete(): Unit                                 = unsupported()
    override fun close() {}  // no resources to release

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun inodesFlags(): Int =
        ByteBuffer.wrap(inode).order(ByteOrder.LITTLE_ENDIAN).getInt(32)

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("ext4 driver is read-only")
}

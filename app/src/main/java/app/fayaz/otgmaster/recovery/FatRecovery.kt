package app.fayaz.otgmaster.recovery

import app.fayaz.otgmaster.block.RawBlockDevice
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A file the filesystem has deleted, as found on the volume. */
data class DeletedFile(
    /** Path of the folder it was in, and its name, "/"-separated; a deleted folder's files are under its path. */
    val path: String,
    val size: Long,
    val firstCluster: Long,
    val state: State,
    /** When it was last modified, from the directory entry; null when unknown. */
    val modifiedMillis: Long?,
    /** Its clusters run consecutively from [firstCluster] (always assumed on FAT32; exFAT records it). */
    val contiguous: Boolean = true,
) {
    enum class State {
        /** Every cluster it would occupy is still free: the content is very likely intact. */
        RECOVERABLE,
        /** Some of its clusters now belong to a live file: what is read back is partly someone else's data. */
        PARTLY_OVERWRITTEN,
        /** Its first cluster belongs to a live file: nothing of it is left. */
        OVERWRITTEN,
    }
}

/**
 * Lists deleted files on a FAT32 volume and reads them back, without writing anything.
 *
 * FAT deletes a file by overwriting the first byte of its directory entry with 0xE5
 * and freeing its clusters in the FAT; the entry keeps the size and first cluster, and
 * the data stays until something else is written there. The chain is gone, so the file
 * is read as consecutive clusters from its first one, which is right for any file the
 * filesystem wrote without fragmentation (almost all of them on a USB drive).
 *
 * Only deleted files are listed. A deleted entry whose first cluster starts a live file
 * is the old name of a renamed or moved file, which the user can still browse, so it
 * is left out; one whose clusters a live file now uses is listed as overwritten.
 */
class FatRecovery(private val dev: RawBlockDevice) {
    private val bs = ByteBuffer.wrap(dev.readBlocks(0, 1)).order(ByteOrder.LITTLE_ENDIAN)
    private val bytesPerSector = bs.getShort(0x0B).toInt() and 0xFFFF
    private val sectorsPerCluster = bs.get(0x0D).toInt() and 0xFF
    private val reservedSectors = bs.getShort(0x0E).toInt() and 0xFFFF
    private val fatCount = bs.get(0x10).toInt() and 0xFF
    private val fatSectors = bs.getInt(0x24).toLong() and 0xFFFFFFFFL
    private val rootCluster = bs.getInt(0x2C).toLong() and 0x0FFFFFFF
    private val totalSectors = (bs.getInt(0x20).toLong() and 0xFFFFFFFFL).takeIf { it != 0L } ?: (bs.getShort(0x13).toLong() and 0xFFFF)
    private val clusterBytes = bytesPerSector * sectorsPerCluster
    private val dataStart = reservedSectors + fatCount * fatSectors
    private val clusterCount = (totalSectors - dataStart) / sectorsPerCluster
    private val devPerSector = bytesPerSector / dev.blockSize

    init {
        if (bs.getShort(0x16).toInt() != 0 || fatSectors == 0L || sectorsPerCluster == 0 || bytesPerSector % dev.blockSize != 0) {
            throw IOException("not a FAT32 volume")
        }
    }

    /** The FAT, read a sector at a time as needed. */
    private val fatCache = HashMap<Long, ByteBuffer>()
    private fun fat(cluster: Long): Long {
        val byte = cluster * 4
        val sector = byte / bytesPerSector
        val buf = fatCache.getOrPut(sector) {
            ByteBuffer.wrap(dev.readBlocks((reservedSectors + sector) * devPerSector, devPerSector)).order(ByteOrder.LITTLE_ENDIAN)
        }
        return (buf.getInt((byte % bytesPerSector).toInt()).toLong() and 0x0FFFFFFF)
    }

    private fun inUse(cluster: Long) = fat(cluster) != 0L
    private fun valid(cluster: Long) = cluster in 2 until clusterCount + 2

    private fun readCluster(c: Long): ByteArray =
        dev.readBlocks((dataStart + (c - 2) * sectorsPerCluster) * devPerSector, sectorsPerCluster * devPerSector)

    private fun chain(first: Long, limit: Int = 1 shl 20): List<Long> {
        val out = ArrayList<Long>()
        var c = first
        while (valid(c) && out.size < limit) {
            out += c
            val next = fat(c)
            if (next >= 0x0FFFFFF8 || next == 0L || next == c) break
            c = next
        }
        return out
    }

    private class Entry(val name: String, val attr: Int, val firstCluster: Long, val size: Long, val deleted: Boolean, val mtime: Long?)

    /** The entries of one directory's data, with long names assembled; "." and ".." and labels left out. */
    private fun entries(data: ByteArray): List<Entry> {
        val out = ArrayList<Entry>()
        val lfn = ArrayList<ByteArray>()
        var i = 0
        while (i + 32 <= data.size) {
            val e = data.copyOfRange(i, i + 32); i += 32
            val first = e[0].toInt() and 0xFF
            if (first == 0) break
            val attr = e[11].toInt() and 0xFF
            if (attr == 0x0F) { lfn += e; continue }
            val parts = lfn.toList(); lfn.clear()
            if (attr and 0x08 != 0) continue
            val deleted = first == 0xE5
            if (!deleted && (e[0] == '.'.code.toByte())) continue // "." and ".."
            // Deletion overwrote the short name's first byte, which the long name's
            // checksum covers: the byte that makes the checksum match is the original one.
            if (deleted && parts.isNotEmpty()) {
                val want = parts[0][13].toInt() and 0xFF
                (0x20..0xFF).firstOrNull { b -> e[0] = b.toByte(); checksum(e) == want }?.let { e[0] = it.toByte() }
                    ?: run { e[0] = 0xE5.toByte() }
            }
            val short = shortName(e, e[0].toInt() and 0xFF == 0xE5)
            if (short == "." || short == "..") continue
            val long = longName(parts, e)
            val b = ByteBuffer.wrap(e).order(ByteOrder.LITTLE_ENDIAN)
            val cluster = ((b.getShort(20).toLong() and 0xFFFF) shl 16) or (b.getShort(26).toLong() and 0xFFFF)
            out += Entry(long ?: short, attr, cluster, b.getInt(28).toLong() and 0xFFFFFFFFL, deleted,
                dosTime(b.getShort(24).toInt() and 0xFFFF, b.getShort(22).toInt() and 0xFFFF))
        }
        return out
    }

    private fun shortName(e: ByteArray, deleted: Boolean): String {
        val raw = e.copyOfRange(0, 11)
        if (deleted) raw[0] = '_'.code.toByte() // the first letter is what deletion overwrote
        // Byte 12 bits 3 and 4: Windows NT's and Linux's "base / extension in lower case".
        val lowerBase = e[12].toInt() and 0x08 != 0
        val lowerExt = e[12].toInt() and 0x10 != 0
        val base = String(raw, 0, 8, Charsets.ISO_8859_1).trimEnd().let { if (lowerBase) it.lowercase() else it }
        val ext = String(raw, 8, 3, Charsets.ISO_8859_1).trimEnd().let { if (lowerExt) it.lowercase() else it }
        return if (ext.isEmpty()) base else "$base.$ext"
    }

    /**
     * The long name from the LFN entries just before [sfn], if their checksum matches it.
     * For a deleted file their sequence numbers were overwritten too, so they are taken in
     * the order they lie on disk, which is last part first.
     */
    private fun longName(parts: List<ByteArray>, sfn: ByteArray): String? {
        if (parts.isEmpty()) return null
        val sum = checksum(sfn)
        if (parts.any { (it[13].toInt() and 0xFF) != sum }) return null
        val sb = StringBuilder()
        for (p in parts.asReversed()) {
            for (off in intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)) {
                val ch = (p[off].toInt() and 0xFF) or ((p[off + 1].toInt() and 0xFF) shl 8)
                if (ch == 0 || ch == 0xFFFF) return sb.toString()
                sb.append(ch.toChar())
            }
        }
        return sb.toString()
    }

    private fun checksum(sfn: ByteArray): Int {
        var sum = 0
        for (k in 0 until 11) sum = (((sum and 1) shl 7) + (sum ushr 1) + (sfn[k].toInt() and 0xFF)) and 0xFF
        return sum
    }

    private fun dosTime(date: Int, time: Int): Long? {
        if (date == 0) return null
        return runCatching {
            java.time.LocalDateTime.of(1980 + (date ushr 9), (date ushr 5) and 0xF, date and 0x1F,
                time ushr 11, (time ushr 5) and 0x3F, (time and 0x1F) * 2)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun clustersFor(size: Long) = maxOf(1L, (size + clusterBytes - 1) / clusterBytes)

    /** Every deleted file on the volume, in live folders and in deleted ones. */
    fun scan(isCancelled: () -> Boolean = { false }, onProgress: (folders: Int, found: Int) -> Unit = { _, _ -> }): List<DeletedFile> {
        val liveStarts = HashMap<Long, Long>() // first cluster -> size of the live file there
        val deleted = ArrayList<Pair<String, Entry>>()
        val deletedDirs = ArrayList<Pair<String, Entry>>()
        val seenDirs = HashSet<Long>()
        fun walk(path: String, first: Long) {
            if (isCancelled() || !seenDirs.add(first)) return
            onProgress(seenDirs.size, deleted.size)
            val data = java.io.ByteArrayOutputStream().also { o -> chain(first, 4096).forEach { o.write(readCluster(it)) } }.toByteArray()
            for (e in entries(data)) {
                val p = if (path.isEmpty()) e.name else "$path/${e.name}"
                val dir = e.attr and 0x10 != 0
                when {
                    e.deleted && dir -> deletedDirs += p to e
                    e.deleted -> deleted += p to e
                    dir -> { liveStarts[e.firstCluster] = -1L; if (valid(e.firstCluster)) walk(p, e.firstCluster) }
                    else -> if (e.firstCluster != 0L) liveStarts[e.firstCluster] = e.size
                }
            }
        }
        walk("", rootCluster)
        // A deleted folder's own entries, read from its first clusters while they are free;
        // its files are deleted with it, whatever their entries say.
        val queue = ArrayDeque(deletedDirs)
        while (queue.isNotEmpty() && !isCancelled()) {
            val (path, e) = queue.removeFirst()
            if (!valid(e.firstCluster) || inUse(e.firstCluster) || !seenDirs.add(e.firstCluster)) continue
            val data = java.io.ByteArrayOutputStream()
            var c = e.firstCluster
            while (valid(c) && !inUse(c) && data.size() < 64 * clusterBytes) {
                val bytes = readCluster(c)
                data.write(bytes)
                if (bytes.indices.step(32).any { bytes[it].toInt() == 0 }) break
                c++
            }
            for (child in entries(data.toByteArray())) {
                val p = "$path/${child.name}"
                if (child.attr and 0x10 != 0) queue += p to child else deleted += p to child
            }
        }
        if (isCancelled()) throw java.util.concurrent.CancellationException("scan stopped")
        return deleted.mapNotNull { (path, e) ->
            if (e.firstCluster == 0L) return@mapNotNull DeletedFile(path, e.size, 0, DeletedFile.State.RECOVERABLE.takeIf { e.size == 0L }
                ?: DeletedFile.State.OVERWRITTEN, e.mtime)
            if (!valid(e.firstCluster)) return@mapNotNull null
            // A live file starting where this one did, with its size, is this file renamed or
            // moved: still there, so not listed. A different size means the space was reused.
            if (liveStarts[e.firstCluster] == e.size) return@mapNotNull null
            val n = clustersFor(e.size)
            val state = when {
                inUse(e.firstCluster) -> DeletedFile.State.OVERWRITTEN
                (0 until n).any { k -> !valid(e.firstCluster + k) || inUse(e.firstCluster + k) } -> DeletedFile.State.PARTLY_OVERWRITTEN
                else -> DeletedFile.State.RECOVERABLE
            }
            DeletedFile(path, e.size, e.firstCluster, state, e.mtime)
        }.sortedBy { it.path.lowercase() }
    }

    /** Writes [file]'s bytes, read as consecutive clusters from its first, to [out]. */
    fun read(file: DeletedFile, out: OutputStream, limit: Long = Long.MAX_VALUE) {
        var left = minOf(file.size, limit)
        var c = file.firstCluster
        while (left > 0) {
            if (!valid(c)) throw IOException("${file.path} runs past the end of the volume")
            val bytes = readCluster(c)
            val n = minOf(left, bytes.size.toLong()).toInt()
            out.write(bytes, 0, n)
            left -= n
            c++
        }
    }
}

package app.fayaz.otgmaster.recovery

import app.fayaz.otgmaster.block.RawBlockDevice
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lists deleted files on an exFAT volume and reads them back, without writing anything.
 *
 * exFAT deletes a file by clearing the "in use" bit of its directory entry set (0x85,
 * 0xC0, 0xC1 become 0x05, 0x40, 0x41) and its clusters in the allocation bitmap. The
 * entries keep the name, size and first cluster. A file written contiguously says so
 * (NoFatChain) and reads back exactly; otherwise its FAT chain is followed, which exFAT
 * does not clear on delete, as long as the chain stays on free clusters.
 *
 * As on FAT32, only deleted files are listed: an entry whose first cluster starts a
 * live file is a renamed or moved file's old entry, and is left out.
 */
class ExFatRecovery(private val dev: RawBlockDevice) {
    private val bs = ByteBuffer.wrap(dev.readBlocks(0, 1)).order(ByteOrder.LITTLE_ENDIAN)
    private val fatOffset = bs.getInt(0x50).toLong() and 0xFFFFFFFFL
    private val heapOffset = bs.getInt(0x58).toLong() and 0xFFFFFFFFL
    private val clusterCount = bs.getInt(0x5C).toLong() and 0xFFFFFFFFL
    private val rootCluster = bs.getInt(0x60).toLong() and 0xFFFFFFFFL
    private val sectorShift = bs.get(0x6C).toInt()
    private val clusterShift = bs.get(0x6D).toInt()
    private val sectorBytes = 1 shl sectorShift
    private val clusterBytes = sectorBytes shl clusterShift
    private val devPerSector = sectorBytes / dev.blockSize

    init {
        if (String(dev.readBlocks(0, 1), 3, 8, Charsets.US_ASCII) != "EXFAT   " || devPerSector == 0) throw IOException("not an exFAT volume")
    }

    private fun valid(c: Long) = c in 2 until clusterCount + 2

    private fun readCluster(c: Long): ByteArray =
        dev.readBlocks((heapOffset + ((c - 2) shl clusterShift)) * devPerSector, (1 shl clusterShift) * devPerSector)

    private val fatCache = HashMap<Long, ByteBuffer>()
    private fun fat(c: Long): Long {
        val byte = c * 4
        val sector = byte / sectorBytes
        val buf = fatCache.getOrPut(sector) {
            ByteBuffer.wrap(dev.readBlocks((fatOffset + sector) * devPerSector, devPerSector)).order(ByteOrder.LITTLE_ENDIAN)
        }
        return buf.getInt((byte % sectorBytes).toInt()).toLong() and 0xFFFFFFFFL
    }

    private class Stream(val first: Long, val length: Long, val contiguous: Boolean)

    private fun clusters(s: Stream, limit: Long = Long.MAX_VALUE): List<Long> {
        val n = minOf(limit, maxOf(1L, (s.length + clusterBytes - 1) / clusterBytes))
        if (s.contiguous) return (0 until n).map { s.first + it }
        val out = ArrayList<Long>()
        var c = s.first
        while (valid(c) && out.size < n) { out += c; c = fat(c) }
        return out
    }

    private fun read(s: Stream, limit: Long = Long.MAX_VALUE): ByteArray {
        val o = ByteArrayOutputStream()
        for (c in clusters(s, limit)) o.write(readCluster(c))
        return o.toByteArray()
    }

    /** The allocation bitmap, from the root directory's 0x81 entry. */
    private val bitmap: ByteArray by lazy {
        val root = read(Stream(rootCluster, 0, false).let { Stream(it.first, clusterBytes.toLong() * 64, false) }, 64)
        var i = 0
        while (i + 32 <= root.size) {
            if (root[i].toInt() and 0xFF == 0x81) {
                val b = ByteBuffer.wrap(root, i, 32).order(ByteOrder.LITTLE_ENDIAN)
                val first = b.getInt(i + 20).toLong() and 0xFFFFFFFFL
                val len = b.getLong(i + 24)
                return@lazy read(Stream(first, len, true)).copyOf(len.toInt())
            }
            i += 32
        }
        throw IOException("exFAT allocation bitmap not found")
    }

    private fun inUse(c: Long): Boolean {
        val k = c - 2
        return (bitmap[(k / 8).toInt()].toInt() ushr (k % 8).toInt()) and 1 == 1
    }

    private class Entry(val name: String, val dir: Boolean, val stream: Stream, val deleted: Boolean, val mtime: Long?)

    private fun entries(data: ByteArray): List<Entry> {
        val out = ArrayList<Entry>()
        var i = 0
        while (i + 32 <= data.size) {
            val type = data[i].toInt() and 0xFF
            if (type == 0) break
            if (type != 0x85 && type != 0x05) { i += 32; continue }
            val deleted = type == 0x05
            val secondary = data[i + 1].toInt() and 0xFF
            if (secondary < 2 || i + 32 * (secondary + 1) > data.size) { i += 32; continue }
            val file = ByteBuffer.wrap(data, i, 32).order(ByteOrder.LITTLE_ENDIAN)
            val attr = file.getShort(i + 4).toInt() and 0xFFFF
            val mtime = exfatTime(file.getInt(i + 12))
            val st = i + 32
            val stType = data[st].toInt() and 0xFF
            if (stType != (if (deleted) 0x40 else 0xC0)) { i += 32; continue }
            val sb = ByteBuffer.wrap(data, st, 32).order(ByteOrder.LITTLE_ENDIAN)
            val flags = data[st + 1].toInt() and 0xFF
            val nameLen = data[st + 3].toInt() and 0xFF
            val first = sb.getInt(st + 20).toLong() and 0xFFFFFFFFL
            val length = sb.getLong(st + 24)
            val name = StringBuilder()
            for (k in 2..secondary) {
                val ne = i + 32 * k
                if ((data[ne].toInt() and 0x7F) != 0x41) break
                for (c in 0 until 15) {
                    if (name.length >= nameLen) break
                    val ch = (data[ne + 2 + c * 2].toInt() and 0xFF) or ((data[ne + 3 + c * 2].toInt() and 0xFF) shl 8)
                    name.append(ch.toChar())
                }
            }
            out += Entry(name.toString(), attr and 0x10 != 0, Stream(first, length, flags and 0x02 != 0), deleted, mtime)
            i += 32 * (secondary + 1)
        }
        return out
    }

    private fun exfatTime(t: Int): Long? {
        if (t == 0) return null
        return runCatching {
            java.time.LocalDateTime.of(1980 + (t ushr 25), (t ushr 21) and 0xF, (t ushr 16) and 0x1F,
                (t ushr 11) and 0x1F, (t ushr 5) and 0x3F, (t and 0x1F) * 2)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    /** Every deleted file, in live folders and in deleted ones. */
    fun scan(isCancelled: () -> Boolean = { false }): List<DeletedFile> {
        val liveStarts = HashMap<Long, Long>() // first cluster -> size of the live file there
        val deleted = ArrayList<Pair<String, Entry>>()
        val seen = HashSet<Long>()
        fun walk(path: String, data: ByteArray, deletedDir: Boolean) {
            for (e in entries(data)) {
                if (isCancelled()) return
                val p = if (path.isEmpty()) e.name else "$path/${e.name}"
                val gone = e.deleted || deletedDir
                if (e.dir) {
                    if (!gone) liveStarts[e.stream.first] = -1L
                    if (!valid(e.stream.first) || !seen.add(e.stream.first)) continue
                    // A deleted folder is read only while its clusters are still free.
                    if (gone && inUse(e.stream.first)) continue
                    walk(p, read(e.stream, 256), gone)
                } else if (gone) deleted += p to e
                else if (e.stream.first != 0L) liveStarts[e.stream.first] = e.stream.length
            }
        }
        seen += rootCluster
        walk("", read(Stream(rootCluster, clusterBytes.toLong() * 4096, false), 4096), false)
        return deleted.mapNotNull { (path, e) ->
            val s = e.stream
            if (s.length == 0L) return@mapNotNull DeletedFile(path, 0, 0, DeletedFile.State.RECOVERABLE, e.mtime)
            if (!valid(s.first)) return@mapNotNull null
            // A live file starting where this one did, with its size, is this file renamed or
            // moved: still there, so not listed. A different size means the space was reused.
            if (liveStarts[s.first] == s.length) return@mapNotNull null
            val cs = clusters(s)
            val state = when {
                inUse(s.first) -> DeletedFile.State.OVERWRITTEN
                cs.size.toLong() < (s.length + clusterBytes - 1) / clusterBytes || cs.any { !valid(it) || inUse(it) } -> DeletedFile.State.PARTLY_OVERWRITTEN
                else -> DeletedFile.State.RECOVERABLE
            }
            DeletedFile(path, s.length, s.first, state, e.mtime, s.contiguous)
        }.sortedBy { it.path.lowercase() }
    }

    /** Writes [file]'s bytes to [out]: its contiguous run, or its FAT chain. */
    fun read(file: DeletedFile, out: OutputStream, limit: Long = Long.MAX_VALUE) {
        val cs = clusters(Stream(file.firstCluster, file.size, file.contiguous))
        var left = minOf(file.size, limit)
        for (c in cs) {
            if (left <= 0) break
            if (!valid(c)) throw IOException("${file.path} runs past the end of the volume")
            val b = readCluster(c)
            val n = minOf(left, b.size.toLong()).toInt()
            out.write(b, 0, n)
            left -= n
        }
    }
}

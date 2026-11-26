package app.fayaz.otgmaster.dmg

import app.fayaz.otgmaster.block.RawBlockDevice
import app.fayaz.otgmaster.block.requireInRange
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A macOS disk image (.dmg, UDIF) as a read-only [RawBlockDevice] of 512-byte sectors: the disk
 * the image holds, partition map and all, decoded chunk by chunk as it is read.
 *
 * Layout (Apple's UDIF, as documented by the dmg2img and libmodi projects): a 512-byte "koly"
 * trailer at the end of the file points to an XML property list whose resource fork holds one
 * "blkx" entry per partition. Each is a "mish" table of chunks: a run of sectors, its type, and
 * where its bytes are in the file. Types read here: zero fill and ignored (both zeros), raw,
 * zlib (UDZO), bzip2 (UDBZ) and LZMA (ULMO). ADC and LZFSE (ULFO) chunks are named in the error
 * when a read reaches one. An image encrypted by Disk Utility ("encrcdsa") has no koly at the
 * end in the clear, and is refused as such.
 *
 * A decoded chunk is kept (the last [CACHE_CHUNKS]), since a filesystem reads neighbouring
 * sectors one after another and a chunk is typically 1 MiB of them.
 */
class UdifImage private constructor(
    private val channel: FileChannel,
    private val chunks: List<Chunk>,
    override val blockCount: Long,
    /** Each partition the image names, with its first sector and length on the image's disk. */
    val partitions: List<Partition>,
) : RawBlockDevice {

    override val blockSize: Int get() = SECTOR

    class Partition(val name: String, val firstSector: Long, val sectorCount: Long)

    internal class Chunk(val type: Int, val sector: Long, val sectors: Long, val offset: Long, val length: Long)

    class UdifFormatException(message: String) : IOException(message)

    private val cache = object : LinkedHashMap<Chunk, ByteArray>(CACHE_CHUNKS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Chunk, ByteArray>) = size > CACHE_CHUNKS
    }

    override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
        requireInRange(startBlock, blockCount.toLong(), "read")
        val out = ByteArray(blockCount * SECTOR)
        var sector = startBlock
        val end = startBlock + blockCount
        while (sector < end) {
            val c = chunkAt(sector)
            if (c == null) { sector++; continue }   // a gap no chunk covers reads as zeros
            val from = sector - c.sector
            val n = minOf(end, c.sector + c.sectors) - sector
            if (c.type != ZERO && c.type != IGNORE) {
                val data = decoded(c)
                System.arraycopy(data, (from * SECTOR).toInt(), out, ((sector - startBlock) * SECTOR).toInt(), (n * SECTOR).toInt())
            }
            sector += n
        }
        return out
    }

    private fun chunkAt(sector: Long): Chunk? {
        var lo = 0; var hi = chunks.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = chunks[mid]
            when {
                sector < c.sector -> hi = mid - 1
                sector >= c.sector + c.sectors -> lo = mid + 1
                else -> return c
            }
        }
        return null
    }

    private fun decoded(c: Chunk): ByteArray = synchronized(cache) {
        cache[c] ?: decode(c).also { cache[c] = it }
    }

    private fun decode(c: Chunk): ByteArray {
        val size = Math.toIntExact(c.sectors * SECTOR)
        if (c.length > MAX_CHUNK_BYTES || size > MAX_CHUNK_BYTES) throw UdifFormatException("a chunk of ${c.length} bytes for $size; refused")
        val raw = ByteArray(c.length.toInt())
        val b = ByteBuffer.wrap(raw)
        var p = c.offset
        while (b.hasRemaining()) { val n = channel.read(b, p); if (n < 0) throw IOException("The image ends inside a chunk at byte ${c.offset}"); p += n }
        val out = when (c.type) {
            RAW -> raw
            ZLIB -> inflate(raw, size)
            BZIP2 -> stream(org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(ByteArrayInputStream(raw)), size)
            LZMA -> stream(org.tukaani.xz.LZMAInputStream(ByteArrayInputStream(raw)), size)
            ADC -> throw UdifFormatException("This image uses ADC compression, which Container Master cannot read yet")
            LZFSE -> throw UdifFormatException("This image uses LZFSE compression (ULFO), which Container Master cannot read yet")
            else -> throw UdifFormatException("This image uses chunk type 0x${Integer.toHexString(c.type)}, which Container Master cannot read")
        }
        if (out.size != size) throw UdifFormatException("a chunk decodes to ${out.size} bytes, not $size; the image is damaged")
        return out
    }

    private fun inflate(src: ByteArray, size: Int): ByteArray {
        val inf = Inflater()
        try {
            inf.setInput(src)
            val out = ByteArray(size)
            var n = 0
            while (n < size && !inf.finished()) {
                val got = try { inf.inflate(out, n, size - n) } catch (e: DataFormatException) { throw UdifFormatException("a zlib chunk is damaged: ${e.message}") }
                if (got == 0 && (inf.needsInput() || inf.needsDictionary())) break
                n += got
            }
            return if (n == size) out else out.copyOf(n)
        } finally { inf.end() }
    }

    private fun stream(input: java.io.InputStream, size: Int): ByteArray = input.use { s ->
        val out = ByteArray(size)
        var n = 0
        while (n < size) { val got = s.read(out, n, size - n); if (got < 0) break; n += got }
        if (n == size && s.read() >= 0) throw UdifFormatException("a chunk decodes past its declared size; refused")
        if (n == size) out else out.copyOf(n)
    }

    override fun writeBlocks(startBlock: Long, data: ByteArray) { throw IOException("Disk images open read-only") }
    override fun close() {}

    companion object {
        const val SECTOR = 512
        private const val CACHE_CHUNKS = 8
        private const val MAX_CHUNK_BYTES = 64L * 1024 * 1024
        internal const val ZERO = 0x00000000
        internal const val RAW = 0x00000001
        internal const val IGNORE = 0x00000002
        internal const val ADC = 0x80000004.toInt()
        internal const val ZLIB = 0x80000005.toInt()
        internal const val BZIP2 = 0x80000006.toInt()
        internal const val LZFSE = 0x80000007.toInt()
        internal const val LZMA = 0x80000008.toInt()
        private const val COMMENT = 0x7FFFFFFE
        private const val TERMINATOR = 0xFFFFFFFF.toInt()

        /** Whether [tail], the file's last 512 bytes, is a UDIF trailer. */
        fun looksLikeUdif(tail: ByteArray): Boolean = tail.size >= SECTOR && String(tail, tail.size - SECTOR, 4, Charsets.US_ASCII) == "koly"

        /** Whether [head], a file's first bytes, is an image Disk Utility encrypted. */
        fun looksEncrypted(head: ByteArray): Boolean = head.size >= 8 && String(head, 0, 8, Charsets.US_ASCII) == "encrcdsa"

        fun open(channel: FileChannel, length: Long = channel.size()): UdifImage {
            if (length < SECTOR) throw UdifFormatException("too short for a disk image")
            val koly = read(channel, length - SECTOR, SECTOR)
            if (koly.getInt(0) != 0x6B6F6C79) {   // "koly"
                if (length >= 8 && String(read(channel, 0, 8).array(), Charsets.US_ASCII) == "encrcdsa")
                    throw UdifFormatException("This disk image is encrypted, which Container Master cannot open yet")
                throw UdifFormatException("not a UDIF disk image: no koly trailer")
            }
            val dataForkOffset = koly.getLong(24)
            val xmlOffset = koly.getLong(216)
            val xmlLength = koly.getLong(224)
            val sectorCount = koly.getLong(492)
            if (xmlLength <= 0 || xmlLength > 64L * 1024 * 1024 || xmlOffset < 0 || xmlOffset + xmlLength > length)
                throw UdifFormatException("the image's property list is missing or out of range")
            val doc = DocumentBuilderFactory.newInstance().apply {
                // The plist names Apple's DTD, which is never fetched, and no entity is expanded:
                // the XML comes from the file the user picked.
                isNamespaceAware = false; isValidating = false
                runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { isExpandEntityReferences = false }
            }.newDocumentBuilder().parse(ByteArrayInputStream(read(channel, xmlOffset, xmlLength.toInt()).array()))
            val chunks = ArrayList<Chunk>()
            val parts = ArrayList<Partition>()
            for ((name, data) in blkxEntries(doc)) {
                val m = ByteBuffer.wrap(data)
                if (data.size < 204 || m.getInt(0) != 0x6D697368) throw UdifFormatException("a damaged partition table entry ($name)")   // "mish"
                val first = m.getLong(8)
                val count = m.getLong(16)
                val base = m.getLong(24)
                val n = m.getInt(200)
                if (n < 0 || 204 + n.toLong() * 40 > data.size) throw UdifFormatException("a damaged chunk table ($name)")
                for (i in 0 until n) {
                    val o = 204 + i * 40
                    val type = m.getInt(o)
                    if (type == COMMENT || type == TERMINATOR) continue
                    val s = first + m.getLong(o + 8)
                    val cnt = m.getLong(o + 16)
                    val off = dataForkOffset + base + m.getLong(o + 24)
                    val len = m.getLong(o + 32)
                    if (cnt <= 0) continue
                    if (s < 0 || cnt > Int.MAX_VALUE || off < 0 || len < 0 || off + len > length)
                        throw UdifFormatException("a chunk points outside the image ($name)")
                    chunks += Chunk(type, s, cnt, off, len)
                }
                parts += Partition(name, first, count)
            }
            chunks.sortBy { it.sector }
            for (i in 1 until chunks.size) if (chunks[i].sector < chunks[i - 1].sector + chunks[i - 1].sectors)
                throw UdifFormatException("two chunks claim the same sectors; the image is damaged")
            val total = maxOf(sectorCount, chunks.lastOrNull()?.let { it.sector + it.sectors } ?: 0)
            return UdifImage(channel, chunks, total, parts)
        }

        private fun read(channel: FileChannel, pos: Long, len: Int): ByteBuffer {
            val b = ByteBuffer.allocate(len)
            var p = pos
            while (b.hasRemaining()) { val n = channel.read(b, p); if (n < 0) throw IOException("the image ended at byte $p"); p += n }
            return b.flip() as ByteBuffer
        }

        /** The resource fork's "blkx" array: each entry's Name and its base64 Data. */
        private fun blkxEntries(doc: org.w3c.dom.Document): List<Pair<String, ByteArray>> {
            val keys = doc.getElementsByTagName("key")
            for (i in 0 until keys.length) {
                val k = keys.item(i)
                if (k.textContent != "blkx") continue
                var arr = k.nextSibling
                while (arr != null && arr.nodeType != org.w3c.dom.Node.ELEMENT_NODE) arr = arr.nextSibling
                if (arr?.nodeName != "array") continue
                val out = ArrayList<Pair<String, ByteArray>>()
                val dicts = arr.childNodes
                for (j in 0 until dicts.length) {
                    val d = dicts.item(j)
                    if (d.nodeName != "dict") continue
                    var name = ""; var data: ByteArray? = null
                    val kids = d.childNodes
                    var lastKey: String? = null
                    for (x in 0 until kids.length) {
                        val c = kids.item(x)
                        when (c.nodeName) {
                            "key" -> lastKey = c.textContent
                            "string" -> if (lastKey == "Name" || lastKey == "CFName") name = c.textContent
                            "data" -> if (lastKey == "Data") data = Base64.getMimeDecoder().decode(c.textContent.trim())
                        }
                    }
                    data?.let { out += name to it }
                }
                return out
            }
            throw UdifFormatException("the image's property list has no partition table (blkx)")
        }
    }
}

package app.fayaz.otgmaster.luks

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Reads LUKS2 metadata the way cryptsetup does: both header copies, each checked against
 * its own checksum, and the valid copy with the higher sequence id chosen.
 *
 * Review of 2026-11-11, L02: the app read a fixed 256 KiB, never checked a checksum, and
 * read only the primary copy, so a damaged header was parsed as if good and a damaged
 * primary made an intact secondary unreachable. Nothing here writes: a damaged copy is
 * reported, not repaired.
 */
object Luks2Metadata {

    /** The header sizes LUKS2 allows (binary header plus JSON area). */
    val HEADER_SIZES: List<Long> = listOf(16L, 32, 64, 128, 256, 512, 1024, 2048, 4096).map { it * 1024 }

    const val BINARY_HEADER_BYTES = 4096

    private const val OFF_VERSION = 6
    private const val OFF_HDR_SIZE = 8
    private const val OFF_SEQID = 16
    private const val OFF_CHECKSUM_ALG = 72
    private const val OFF_HDR_OFFSET = 256
    private const val OFF_CSUM = 448
    private const val CSUM_BYTES = 64

    /** One valid header copy. */
    data class Copy(val offset: Long, val hdrSize: Long, val seqId: Long, val json: String)

    /**
     * Reads the metadata of the LUKS2 volume whose partition is [partitionBytes] long;
     * [readAt] reads `length` bytes at a byte offset from the start of the partition.
     *
     * @throws LuksUnlocker.UnsupportedFormatException if neither copy is valid.
     */
    fun read(partitionBytes: Long, readAt: (offset: Long, length: Int) -> ByteArray): Copy {
        val problems = mutableListOf<String>()
        fun copyAt(offset: Long, label: String): Copy? {
            if (offset + BINARY_HEADER_BYTES > partitionBytes) return null
            val head = readAt(offset, BINARY_HEADER_BYTES)
            if (!hasMagic(head, offset) || LuksParser.getVersion(head) != 2) {
                problems += "$label: no LUKS2 header"; return null
            }
            val hdrSize = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN).getLong(OFF_HDR_SIZE)
            if (hdrSize !in HEADER_SIZES || offset + hdrSize > partitionBytes) {
                problems += "$label: header size $hdrSize is not valid here"; return null
            }
            return parseCopy(readAt(offset, hdrSize.toInt()), offset) { problems += "$label: $it" }
        }

        val primary = copyAt(0, "primary header")
        // The secondary sits right after the primary. With the primary unreadable its size is
        // unknown, so every offset cryptsetup allows is tried, as cryptsetup does.
        val secondaryOffsets = primary?.let { listOf(it.hdrSize) } ?: HEADER_SIZES
        val secondary = secondaryOffsets.firstNotNullOfOrNull { off ->
            copyAt(off, "secondary header at $off")?.takeIf { it.offset == off }
        }
        return listOfNotNull(primary, secondary).maxByOrNull { it.seqId }
            ?: throw LuksUnlocker.UnsupportedFormatException(
                "The LUKS2 metadata is damaged: neither header copy is valid (" +
                    problems.joinToString("; ") + ")")
    }

    /**
     * [bytes] as a header copy found at [expectedOffset], or null (with [problem] told why)
     * if it is not a valid one: wrong magic or version, a size or offset field that does not
     * match, an unsupported checksum algorithm, a checksum mismatch, or JSON that is not JSON.
     */
    fun parseCopy(bytes: ByteArray, expectedOffset: Long, problem: (String) -> Unit = {}): Copy? {
        if (bytes.size < BINARY_HEADER_BYTES || !hasMagic(bytes, expectedOffset)) { problem("no LUKS magic"); return null }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (b.getShort(OFF_VERSION).toInt() != 2) { problem("not version 2"); return null }
        val hdrSize = b.getLong(OFF_HDR_SIZE)
        if (hdrSize !in HEADER_SIZES || hdrSize != bytes.size.toLong()) {
            problem("header size $hdrSize does not match"); return null
        }
        if (b.getLong(OFF_HDR_OFFSET) != expectedOffset) {
            problem("records offset ${b.getLong(OFF_HDR_OFFSET)}, found at $expectedOffset"); return null
        }
        val alg = cString(bytes, OFF_CHECKSUM_ALG, 32)
        val md = try {
            MessageDigest.getInstance(LuksAlgorithms.digest(alg, "LUKS2 checksum_alg"))
        } catch (e: LuksUnlocker.UnsupportedFormatException) { problem(e.message ?: alg); return null }
        // The checksum covers the whole header copy with its own checksum field zeroed.
        val copy = bytes.copyOf()
        copy.fill(0, OFF_CSUM, OFF_CSUM + CSUM_BYTES)
        val want = md.digest(copy)
        val have = bytes.copyOfRange(OFF_CSUM, OFF_CSUM + want.size)
        if (!MessageDigest.isEqual(want, have)) { problem("checksum mismatch"); return null }
        val json = String(bytes, BINARY_HEADER_BYTES, bytes.size - BINARY_HEADER_BYTES, Charsets.UTF_8).trimEnd('\u0000')
        if (runCatching { org.json.JSONObject(json) }.isFailure) { problem("JSON area does not parse"); return null }
        return Copy(expectedOffset, hdrSize, b.getLong(OFF_SEQID), json)
    }

    /** Recomputes and stores the checksum of a header copy; for tests that build or edit headers. */
    fun withChecksum(bytes: ByteArray): ByteArray {
        val alg = cString(bytes, OFF_CHECKSUM_ALG, 32).ifEmpty { "sha256" }
        val out = bytes.copyOf()
        out.fill(0, OFF_CSUM, OFF_CSUM + CSUM_BYTES)
        MessageDigest.getInstance(LuksAlgorithms.digest(alg, "LUKS2 checksum_alg")).digest(out).copyInto(out, OFF_CSUM)
        return out
    }

    /** The secondary copy's magic is "SKUL\xba\xbe", the primary's reversed. */
    private val SECONDARY_MAGIC = byteArrayOf(0x53, 0x4B, 0x55, 0x4C, 0xBA.toByte(), 0xBE.toByte())

    private fun hasMagic(b: ByteArray, offset: Long): Boolean =
        if (offset == 0L) LuksParser.hasLuksMagic(b)
        else b.size >= 6 && SECONDARY_MAGIC.indices.all { b[it] == SECONDARY_MAGIC[it] }

    private fun cString(b: ByteArray, offset: Int, max: Int): String {
        var end = offset
        while (end < offset + max && b[end] != 0.toByte()) end++
        return String(b, offset, end - offset, Charsets.US_ASCII)
    }
}

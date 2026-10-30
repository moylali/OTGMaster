package app.fayaz.otgmaster.backup.restic

import java.io.InputStream

/**
 * restic's content-defined chunker (github.com/restic/chunker): a Rabin fingerprint over
 * a 64-byte window, with the irreducible polynomial stored in the repository's config.
 *
 * Any chunking would produce a repository restic can read; this one produces the *same*
 * chunks restic does, so a phone backup into a repository a desktop also backs up to
 * deduplicates against the desktop's data, and the reverse. Minimum 512 KiB, maximum
 * 8 MiB, a cut where the low 20 bits of the fingerprint are zero (1 MiB on average).
 */
class ResticChunker(private val pol: Long, private val input: InputStream) {

    private val polShift = deg(pol) - 8
    private val outTable = LongArray(256)
    private val modTable = LongArray(256)

    init {
        require(deg(pol) in 9..63) { "chunker polynomial of degree ${deg(pol)}" }
        for (b in 0 until 256) {
            var h = appendByte(0, b, pol)
            repeat(WINDOW - 1) { h = appendByte(h, 0, pol) }
            outTable[b] = h
        }
        val k = deg(pol)
        for (b in 0 until 256) modTable[b] = mod(b.toLong() shl k, pol) or (b.toLong() shl k)
    }

    private val window = ByteArray(WINDOW)
    private var wpos = 0
    private var digest = 0L
    private val buf = ByteArray(BUF)
    private var bpos = 0
    private var bmax = 0
    private var eof = false

    private fun reset() {
        window.fill(0); wpos = 0; digest = 0
        slide(1)
    }

    private fun slide(b: Int) {
        val out = window[wpos].toInt() and 0xff
        window[wpos] = b.toByte()
        digest = digest xor outTable[out]
        wpos = (wpos + 1) % WINDOW
        val index = (digest ushr polShift).toInt() and 0xff
        digest = (digest shl 8) or b.toLong()
        digest = digest xor modTable[index]
    }

    private fun fill(): Boolean {
        if (bpos < bmax) return true
        if (eof) return false
        var n = 0
        while (n < BUF) {
            val r = input.read(buf, n, BUF - n)
            if (r < 0) { eof = true; break }
            n += r
        }
        bpos = 0; bmax = n
        return n > 0
    }

    /** The next chunk's bytes, or null at the end of the input. */
    fun next(): ByteArray? {
        reset()
        val out = java.io.ByteArrayOutputStream(MIN)
        var count = 0
        // The first MIN - WINDOW bytes cannot end a chunk: they are taken without hashing.
        var pre = MIN - WINDOW
        while (pre > 0) {
            if (!fill()) return if (count == 0) null else out.toByteArray()
            val n = minOf(pre, bmax - bpos)
            out.write(buf, bpos, n); bpos += n; count += n; pre -= n
        }
        while (true) {
            if (!fill()) return if (count == 0) null else out.toByteArray()
            val start = bpos
            while (bpos < bmax) {
                slide(buf[bpos].toInt() and 0xff)
                bpos++; count++
                if (count < MIN) continue
                if ((digest and SPLIT_MASK) == 0L || count >= MAX) {
                    out.write(buf, start, bpos - start)
                    return out.toByteArray()
                }
            }
            out.write(buf, start, bpos - start)
        }
    }

    companion object {
        const val WINDOW = 64
        const val MIN = 512 * 1024
        const val MAX = 8 * 1024 * 1024
        private const val BUF = 512 * 1024
        private const val SPLIT_MASK = (1L shl 20) - 1

        fun deg(p: Long): Int = 63 - java.lang.Long.numberOfLeadingZeros(p)

        fun mod(x0: Long, d: Long): Long {
            var x = x0
            val dd = deg(d)
            while (x != 0L && deg(x) >= dd) x = x xor (d shl (deg(x) - dd))
            return x
        }

        private fun appendByte(hash: Long, b: Int, pol: Long): Long = mod((hash shl 8) or b.toLong(), pol)

        /** The polynomial as the config stores it: lowercase hex. */
        fun parsePolynomial(hex: String): Long = java.lang.Long.parseUnsignedLong(hex, 16)
    }
}

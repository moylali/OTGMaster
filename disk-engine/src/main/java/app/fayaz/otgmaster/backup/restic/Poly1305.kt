package app.fayaz.otgmaster.backup.restic

/**
 * Poly1305 one-time authenticator (RFC 8439 §2.5), in 26-bit limbs ("poly1305-donna").
 *
 * restic authenticates every encrypted object with Poly1305-AES; Android's providers
 * offer Poly1305 only inside ChaCha20-Poly1305, so it is here on its own. Checked
 * against the RFC's test vectors and against restic's own repositories.
 */
class Poly1305(key: ByteArray) {
    init { require(key.size == 32) { "Poly1305 takes a 32-byte key" } }

    private val r0 = le32(key, 0) and 0x3ffffff
    private val r1 = (le32(key, 3) ushr 2) and 0x3ffff03
    private val r2 = (le32(key, 6) ushr 4) and 0x3ffc0ff
    private val r3 = (le32(key, 9) ushr 6) and 0x3f03fff
    private val r4 = (le32(key, 12) ushr 8) and 0x00fffff
    private val s1 = r1 * 5; private val s2 = r2 * 5; private val s3 = r3 * 5; private val s4 = r4 * 5
    private val pad = LongArray(4) { le32(key, 16 + 4 * it) }

    private var h0 = 0L; private var h1 = 0L; private var h2 = 0L; private var h3 = 0L; private var h4 = 0L
    private val buf = ByteArray(16)
    private var bufLen = 0

    fun update(m: ByteArray, off: Int = 0, len: Int = m.size): Poly1305 {
        var o = off
        var n = len
        if (bufLen > 0) {
            val take = minOf(16 - bufLen, n)
            System.arraycopy(m, o, buf, bufLen, take)
            bufLen += take; o += take; n -= take
            if (bufLen < 16) return this
            block(buf, 0, 1L shl 24)
            bufLen = 0
        }
        while (n >= 16) { block(m, o, 1L shl 24); o += 16; n -= 16 }
        if (n > 0) { System.arraycopy(m, o, buf, 0, n); bufLen = n }
        return this
    }

    fun finish(): ByteArray {
        if (bufLen > 0) {
            buf[bufLen] = 1
            for (i in bufLen + 1 until 16) buf[i] = 0
            block(buf, 0, 0L)
        }
        var c: Long
        c = h1 ushr 26; h1 = h1 and M; h2 += c
        c = h2 ushr 26; h2 = h2 and M; h3 += c
        c = h3 ushr 26; h3 = h3 and M; h4 += c
        c = h4 ushr 26; h4 = h4 and M; h0 += c * 5
        c = h0 ushr 26; h0 = h0 and M; h1 += c

        var g0 = h0 + 5; c = g0 ushr 26; g0 = g0 and M
        var g1 = h1 + c; c = g1 ushr 26; g1 = g1 and M
        var g2 = h2 + c; c = g2 ushr 26; g2 = g2 and M
        var g3 = h3 + c; c = g3 ushr 26; g3 = g3 and M
        val g4 = h4 + c - (1L shl 26)
        // h + 5 - 2^130 >= 0 means h >= p: take g.
        if (g4 >= 0) { h0 = g0; h1 = g1; h2 = g2; h3 = g3; h4 = g4 }

        val w0 = (h0 or (h1 shl 26)) and 0xffffffffL
        val w1 = ((h1 ushr 6) or (h2 shl 20)) and 0xffffffffL
        val w2 = ((h2 ushr 12) or (h3 shl 14)) and 0xffffffffL
        val w3 = ((h3 ushr 18) or (h4 shl 8)) and 0xffffffffL
        var f = w0 + pad[0]; val o0 = f and 0xffffffffL
        f = w1 + pad[1] + (f ushr 32); val o1 = f and 0xffffffffL
        f = w2 + pad[2] + (f ushr 32); val o2 = f and 0xffffffffL
        f = w3 + pad[3] + (f ushr 32); val o3 = f and 0xffffffffL
        val out = ByteArray(16)
        putLe32(out, 0, o0); putLe32(out, 4, o1); putLe32(out, 8, o2); putLe32(out, 12, o3)
        return out
    }

    private fun block(m: ByteArray, o: Int, hibit: Long) {
        h0 += le32(m, o) and 0x3ffffff
        h1 += (le32(m, o + 3) ushr 2) and 0x3ffffff
        h2 += (le32(m, o + 6) ushr 4) and 0x3ffffff
        h3 += (le32(m, o + 9) ushr 6) and 0x3ffffff
        h4 += (le32(m, o + 12) ushr 8) or hibit

        val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
        var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
        var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
        var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
        var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0

        var c = d0 ushr 26; h0 = d0 and M
        d1 += c; c = d1 ushr 26; h1 = d1 and M
        d2 += c; c = d2 ushr 26; h2 = d2 and M
        d3 += c; c = d3 ushr 26; h3 = d3 and M
        d4 += c; c = d4 ushr 26; h4 = d4 and M
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and M
        h1 += c
    }

    companion object {
        private const val M = 0x3ffffffL

        fun mac(key: ByteArray, msg: ByteArray, off: Int = 0, len: Int = msg.size): ByteArray =
            Poly1305(key).update(msg, off, len).finish()

        private fun le32(b: ByteArray, i: Int): Long =
            ((b[i].toLong() and 0xff) or ((b[i + 1].toLong() and 0xff) shl 8) or
                ((b[i + 2].toLong() and 0xff) shl 16) or ((b[i + 3].toLong() and 0xff) shl 24))

        private fun putLe32(b: ByteArray, i: Int, v: Long) {
            b[i] = v.toByte(); b[i + 1] = (v ushr 8).toByte(); b[i + 2] = (v ushr 16).toByte(); b[i + 3] = (v ushr 24).toByte()
        }
    }
}

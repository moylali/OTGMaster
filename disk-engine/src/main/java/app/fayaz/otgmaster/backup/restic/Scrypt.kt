package app.fayaz.otgmaster.backup.restic

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * scrypt (RFC 7914), which restic uses to turn a repository password into the key that
 * opens a key file. Pure Kotlin so the same code runs in the app and in host tests.
 */
object Scrypt {

    fun derive(password: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int, length: Int): ByteArray {
        require(n > 1 && n and (n - 1) == 0) { "N must be a power of 2 greater than 1" }
        require(r > 0 && p > 0) { "r and p must be positive" }
        val blockLen = 128 * r
        val b = pbkdf2Sha256(password, salt, 1, p * blockLen)
        val x = IntArray(32 * r)
        val v = IntArray(32 * r * n)
        val y = IntArray(32 * r)
        for (i in 0 until p) roMix(b, i * blockLen, r, n, x, v, y)
        return pbkdf2Sha256(password, b, 1, length)
    }

    private fun roMix(b: ByteArray, off: Int, r: Int, n: Int, x: IntArray, v: IntArray, y: IntArray) {
        val words = 32 * r
        for (i in 0 until words) x[i] = le32(b, off + 4 * i)
        for (i in 0 until n) {
            System.arraycopy(x, 0, v, i * words, words)
            blockMix(x, y, r)
        }
        for (i in 0 until n) {
            val j = x[(2 * r - 1) * 16] and (n - 1)
            for (k in 0 until words) x[k] = x[k] xor v[j * words + k]
            blockMix(x, y, r)
        }
        for (i in 0 until words) putLe32(b, off + 4 * i, x[i])
    }

    /** BlockMix_salsa20/8 in place on [b] (32·r words), with [y] as scratch. */
    private fun blockMix(b: IntArray, y: IntArray, r: Int) {
        val t = IntArray(16)
        System.arraycopy(b, (2 * r - 1) * 16, t, 0, 16)
        for (i in 0 until 2 * r) {
            for (k in 0 until 16) t[k] = t[k] xor b[i * 16 + k]
            salsa8(t)
            // Even blocks to the first half, odd to the second.
            val dst = (if (i % 2 == 0) i / 2 else r + i / 2) * 16
            System.arraycopy(t, 0, y, dst, 16)
        }
        System.arraycopy(y, 0, b, 0, 32 * r)
    }

    private fun salsa8(b: IntArray) {
        val x = b.copyOf()
        repeat(4) {
            x[4] = x[4] xor Integer.rotateLeft(x[0] + x[12], 7); x[8] = x[8] xor Integer.rotateLeft(x[4] + x[0], 9)
            x[12] = x[12] xor Integer.rotateLeft(x[8] + x[4], 13); x[0] = x[0] xor Integer.rotateLeft(x[12] + x[8], 18)
            x[9] = x[9] xor Integer.rotateLeft(x[5] + x[1], 7); x[13] = x[13] xor Integer.rotateLeft(x[9] + x[5], 9)
            x[1] = x[1] xor Integer.rotateLeft(x[13] + x[9], 13); x[5] = x[5] xor Integer.rotateLeft(x[1] + x[13], 18)
            x[14] = x[14] xor Integer.rotateLeft(x[10] + x[6], 7); x[2] = x[2] xor Integer.rotateLeft(x[14] + x[10], 9)
            x[6] = x[6] xor Integer.rotateLeft(x[2] + x[14], 13); x[10] = x[10] xor Integer.rotateLeft(x[6] + x[2], 18)
            x[3] = x[3] xor Integer.rotateLeft(x[15] + x[11], 7); x[7] = x[7] xor Integer.rotateLeft(x[3] + x[15], 9)
            x[11] = x[11] xor Integer.rotateLeft(x[7] + x[3], 13); x[15] = x[15] xor Integer.rotateLeft(x[11] + x[7], 18)
            x[1] = x[1] xor Integer.rotateLeft(x[0] + x[3], 7); x[2] = x[2] xor Integer.rotateLeft(x[1] + x[0], 9)
            x[3] = x[3] xor Integer.rotateLeft(x[2] + x[1], 13); x[0] = x[0] xor Integer.rotateLeft(x[3] + x[2], 18)
            x[6] = x[6] xor Integer.rotateLeft(x[5] + x[4], 7); x[7] = x[7] xor Integer.rotateLeft(x[6] + x[5], 9)
            x[4] = x[4] xor Integer.rotateLeft(x[7] + x[6], 13); x[5] = x[5] xor Integer.rotateLeft(x[4] + x[7], 18)
            x[11] = x[11] xor Integer.rotateLeft(x[10] + x[9], 7); x[8] = x[8] xor Integer.rotateLeft(x[11] + x[10], 9)
            x[9] = x[9] xor Integer.rotateLeft(x[8] + x[11], 13); x[10] = x[10] xor Integer.rotateLeft(x[9] + x[8], 18)
            x[12] = x[12] xor Integer.rotateLeft(x[15] + x[14], 7); x[13] = x[13] xor Integer.rotateLeft(x[12] + x[15], 9)
            x[14] = x[14] xor Integer.rotateLeft(x[13] + x[12], 13); x[15] = x[15] xor Integer.rotateLeft(x[14] + x[13], 18)
        }
        for (i in 0 until 16) b[i] += x[i]
    }

    fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // An empty key is legal in HMAC but refused by SecretKeySpec; restic allows an empty password.
        mac.init(if (password.isEmpty()) EmptyKey else SecretKeySpec(password, "HmacSHA256"))
        val out = ByteArray(length)
        var block = 1
        var pos = 0
        while (pos < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val take = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, take)
            pos += take
            block++
        }
        return out
    }

    /** A zero-length HMAC key, which SecretKeySpec will not construct. */
    private object EmptyKey : javax.crypto.SecretKey {
        private fun readResolve(): Any = EmptyKey
        override fun getAlgorithm() = "HmacSHA256"
        override fun getFormat() = "RAW"
        override fun getEncoded() = ByteArray(0)
    }

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
            ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)

    private fun putLe32(b: ByteArray, i: Int, v: Int) {
        b[i] = v.toByte(); b[i + 1] = (v ushr 8).toByte(); b[i + 2] = (v ushr 16).toByte(); b[i + 3] = (v ushr 24).toByte()
    }
}

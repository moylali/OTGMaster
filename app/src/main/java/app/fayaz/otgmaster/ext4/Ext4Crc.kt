package app.fayaz.otgmaster.ext4

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * CRC32c (Castagnoli) for ext4 metadata checksums.
 *
 * ext4 stores partial running states, not final CRC values — callers must NOT
 * invert the result of [update] before storing it.  [compute] is a convenience
 * that starts from the standard seed and returns the final (inverted) CRC for
 * uses that need it.
 */
internal object Ext4Crc {

    private val TABLE = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x82F63B78.toInt() else c ushr 1 }
        c
    }

    /**
     * Update running CRC state with [data].
     *
     * [state] is the running state — NOT the final inverted CRC.  Pass [SEED]
     * to start a fresh computation; pass the previous [update] return value to
     * chain.  The return value is the new running state.
     */
    fun update(state: Int, data: ByteArray, offset: Int = 0, len: Int = data.size - offset): Int {
        var crc = state
        for (i in offset until offset + len)
            crc = (crc ushr 8) xor TABLE[(crc xor data[i].toInt()) and 0xFF]
        return crc
    }

    /** Compute a final CRC (inverted) over [chunks] starting from [SEED]. */
    fun compute(vararg chunks: ByteArray): Int {
        var s = SEED
        for (c in chunks) s = update(s, c)
        return s.inv()
    }

    /** Encode [v] as a little-endian 4-byte array. */
    fun leInt(v: Int): ByteArray =
        ByteArray(4).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(v) }

    /** Standard initial running state (0xFFFFFFFF). */
    const val SEED = -1
}

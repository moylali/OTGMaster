package app.fayaz.otgmaster.luks

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LuksHeaderTest {

    /** A LUKS2 primary header with the given hdr_size, valid magic and version. */
    private fun luks2Header(hdrSize: Long, size: Int = 8192): ByteArray {
        val data = ByteArray(size)
        byteArrayOf(0x4C, 0x55, 0x4B, 0x53, 0xBA.toByte(), 0xBE.toByte()).copyInto(data)
        data[6] = 0; data[7] = 2  // version 2
        ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).putLong(8, hdrSize)
        return data
    }

    /**
     * hdr_size is read off the medium, so a drive can claim a header smaller than
     * the 4096-byte binary header it must follow.  That made copyOfRange receive a
     * fromIndex above its toIndex and throw IllegalArgumentException, which crashes
     * the app rather than refusing the volume — reachable from any USB drive handed
     * to the user.
     */
    @Test
    fun aHeaderSmallerThanTheBinaryHeaderIsRejectedNotCrashed() {
        for (hdrSize in longArrayOf(0L, 1L, 512L, 4095L, 4096L)) {
            val thrown = runCatching { LuksParser.parseLuks2(luks2Header(hdrSize)) }
                .exceptionOrNull()
            assertTrue(
                "hdr_size=$hdrSize should be refused, got ${thrown?.let { it::class.java.name }}",
                thrown is LuksUnlocker.UnsupportedFormatException
            )
        }
    }

    /** A negative hdr_size must not slip past the check either. */
    @Test
    fun aNegativeHeaderSizeIsRejected() {
        val thrown = runCatching { LuksParser.parseLuks2(luks2Header(-1L)) }.exceptionOrNull()
        assertTrue(
            "negative hdr_size should be refused, got ${thrown?.let { it::class.java.name }}",
            thrown is LuksUnlocker.UnsupportedFormatException
        )
    }
}

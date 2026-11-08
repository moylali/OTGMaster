package app.fayaz.otgmaster.veracrypt

import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * A decrypted VeraCrypt volume header: the 448 bytes from header offset 64, after the
 * 64-byte salt, once [VeraCryptNative.decryptHeader] has run.
 *
 * Field offsets are VeraCrypt's header format version 5 (Common/Volumes.c), stated relative
 * to the start of these 448 bytes, which is 64 less than the offsets VeraCrypt's own
 * documentation uses. [VeraCryptVolumeCreator.plainHeader] writes the same layout.
 */
data class VeraCryptHeader(
    val formatVersion: Int,
    val minProgramVersion: Int,
    /** Non-zero only in a hidden volume's own header; see [isHidden]. */
    val hiddenVolumeSize: Long,
    val volumeSize: Long,
    val dataAreaOffset: Long,
    val encryptedAreaSize: Long,
    val flags: Int,
    val sectorSize: Int,
    /** The 256-byte key area. The caller takes the leading [VeraCryptCipher.keySizeBytes]. */
    val keyArea: ByteArray,
) {
    /**
     * Whether this header describes a hidden volume, read from the header's own field.
     *
     * It says only which volume was just opened. A standard volume's header holds 0 here
     * whether or not a hidden volume lies inside it, as it must for deniability, so this can
     * never reveal that a volume has a hidden companion.
     */
    val isHidden: Boolean get() = hiddenVolumeSize != 0L

    /**
     * Whether [dataAreaOffset] and the size fields can be trusted. VeraCrypt sets them from
     * format version 4; a caller falls back to [STANDARD_DATA_OFFSET] for older headers.
     */
    val hasDataAreaFields: Boolean get() = formatVersion >= 4

    override fun equals(other: Any?): Boolean =
        other is VeraCryptHeader && formatVersion == other.formatVersion &&
            minProgramVersion == other.minProgramVersion &&
            hiddenVolumeSize == other.hiddenVolumeSize && volumeSize == other.volumeSize &&
            dataAreaOffset == other.dataAreaOffset && encryptedAreaSize == other.encryptedAreaSize &&
            flags == other.flags && sectorSize == other.sectorSize &&
            keyArea.contentEquals(other.keyArea)

    override fun hashCode(): Int = listOf(
        formatVersion, minProgramVersion, hiddenVolumeSize, volumeSize,
        dataAreaOffset, encryptedAreaSize, flags, sectorSize,
    ).hashCode() * 31 + keyArea.contentHashCode()

    companion object {
        /** Bytes of decrypted header the cipher produces, and this parser expects. */
        const val PLAIN_BYTES = 448

        /** Where a standard volume's header sits, from the start of the host volume. */
        const val STANDARD_HEADER_OFFSET = 0L

        /** Where a standard volume's data begins: after the 128 KiB header group. */
        const val STANDARD_DATA_OFFSET = 131072L

        /**
         * Where a hidden volume's header sits: the second 64 KiB of the header group. Its
         * data lies in the outer volume's free space, so that offset comes from the header.
         */
        const val HIDDEN_HEADER_OFFSET = 65536L

        private const val OFF_FORMAT_VERSION = 4
        private const val OFF_MIN_PROGRAM_VERSION = 6
        private const val OFF_KEY_AREA_CRC = 8
        private const val OFF_HIDDEN_VOLUME_SIZE = 28
        private const val OFF_VOLUME_SIZE = 36
        private const val OFF_DATA_AREA_OFFSET = 44
        private const val OFF_ENCRYPTED_AREA_SIZE = 52
        private const val OFF_FLAGS = 60
        private const val OFF_SECTOR_SIZE = 64
        private const val OFF_FIELDS_CRC = 188
        private const val OFF_KEY_AREA = 192
        private const val KEY_AREA_BYTES = 256

        private val MAGIC = "VERA".toByteArray(Charsets.US_ASCII)

        /**
         * Parses [plain] if it really is a decrypted header, or returns null if it is not.
         *
         * Null is the ordinary answer: a wrong password, or the right password at the other
         * header location, produces 448 bytes of noise. Both CRC-32s are checked as VeraCrypt
         * does, because the four magic bytes alone accept noise once in four billion tries,
         * and accepting a bad header hands a random master key to the write path.
         */
        fun parse(plain: ByteArray): VeraCryptHeader? {
            if (plain.size < PLAIN_BYTES) return null
            for (i in MAGIC.indices) if (plain[i] != MAGIC[i]) return null

            val b = ByteBuffer.wrap(plain)                      // big-endian, as VeraCrypt stores it
            if (b.getInt(OFF_FIELDS_CRC) != crc32(plain, 0, OFF_FIELDS_CRC)) return null
            if (b.getInt(OFF_KEY_AREA_CRC) != crc32(plain, OFF_KEY_AREA, KEY_AREA_BYTES)) return null

            return VeraCryptHeader(
                formatVersion = b.getShort(OFF_FORMAT_VERSION).toInt() and 0xFFFF,
                minProgramVersion = b.getShort(OFF_MIN_PROGRAM_VERSION).toInt() and 0xFFFF,
                hiddenVolumeSize = b.getLong(OFF_HIDDEN_VOLUME_SIZE),
                volumeSize = b.getLong(OFF_VOLUME_SIZE),
                dataAreaOffset = b.getLong(OFF_DATA_AREA_OFFSET),
                encryptedAreaSize = b.getLong(OFF_ENCRYPTED_AREA_SIZE),
                flags = b.getInt(OFF_FLAGS),
                sectorSize = b.getInt(OFF_SECTOR_SIZE),
                keyArea = plain.copyOfRange(OFF_KEY_AREA, OFF_KEY_AREA + KEY_AREA_BYTES),
            )
        }

        private fun crc32(b: ByteArray, offset: Int, length: Int): Int =
            CRC32().apply { update(b, offset, length) }.value.toInt()
    }
}

package app.fayaz.otgmaster.veracrypt

import app.fayaz.otgmaster.block.RawBlockDevice
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.CRC32

/**
 * Creates a VeraCrypt volume over a whole partition, as VeraCrypt lays out a non-system,
 * device-hosted volume:
 *
 * - bytes 0-131071: the header (salt + 448 encrypted bytes) in the first 512, then random
 *   data, including where a hidden volume's header would be (64 KiB in);
 * - the data area from 131072, [dataBytes] long;
 * - the last 131072 bytes: the backup header, under its own salt, then random data.
 *
 * Both headers carry the same master keys, freshly drawn from SecureRandom. Header fields
 * (VeraCrypt's Common/Volumes.c, header format version 5): "VERA"; format version 5;
 * minimum program version 1.11; CRC-32 of the 256-byte key area; volume and encrypted-area
 * size; data offset 131072; sector size 512; CRC-32 of the fields; the key area. All
 * big-endian.
 *
 * Returns the decrypted data area, sized exactly to it, for the caller to format.
 */
object VeraCryptVolumeCreator {

    const val HEADER_GROUP_BYTES = 131072L
    private const val TOTAL_HEADER_BYTES = 2 * HEADER_GROUP_BYTES
    private const val SECTOR = 512
    private const val KEY_AREA_BYTES = 256

    /** VeraCrypt refuses a PIM below this with a password shorter than 20 characters. */
    const val MIN_PIM_FOR_SHORT_PASSWORD = 485
    const val SHORT_PASSWORD_CHARS = 20
    /** VeraCrypt's maximum password length, in UTF-8 bytes. */
    const val MAX_PASSWORD_BYTES = 128

    /** The data area a partition of [hostSectors] 512-byte sectors gives, in bytes. */
    fun dataBytes(hostSectors: Long): Long =
        ((hostSectors * SECTOR - TOTAL_HEADER_BYTES) / 4096 * 4096).coerceAtLeast(0)

    /**
     * Why VeraCrypt would refuse this password and PIM, or null if it would accept them.
     * Checked before anything is written.
     */
    fun passwordProblem(password: CharArray, pim: Int?): String? {
        val bytes = String(password).toByteArray(Charsets.UTF_8)
        try {
            return when {
                password.isEmpty() -> "Enter a password."
                bytes.size > MAX_PASSWORD_BYTES -> "VeraCrypt passwords are at most $MAX_PASSWORD_BYTES bytes."
                pim != null && pim < 0 -> "The PIM cannot be negative."
                pim != null && pim in 1 until MIN_PIM_FOR_SHORT_PASSWORD && password.size < SHORT_PASSWORD_CHARS ->
                    "With a password under $SHORT_PASSWORD_CHARS characters, VeraCrypt requires a PIM of at " +
                        "least $MIN_PIM_FOR_SHORT_PASSWORD, or none."
                else -> null
            }
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * Writes the headers over [partition] (the whole partition, 512-byte sectors) and
     * returns the decrypted data area. [password] is wiped. Nothing about the old contents
     * survives in the header areas; the data area is left as it was, for the caller to
     * format (and, for a full format, overwrite).
     */
    fun create(
        partition: RawBlockDevice,
        password: CharArray,
        pim: Int?,
        cipher: VeraCryptCipher,
        hash: VeraCryptHash,
        random: SecureRandom = SecureRandom(),
        progress: (String) -> Unit = {},
    ): RawBlockDevice {
        require(cipher.isSupported) { "${cipher.displayName} is not supported" }
        require(hash.isSupported) { "${hash.displayName} is not supported" }
        require(partition.blockSize == SECTOR) { "VeraCrypt volumes are created on 512-byte sectors only" }
        passwordProblem(password, pim)?.let { password.fill('\u0000'); throw IllegalArgumentException(it) }
        val hostBytes = partition.blockCount * SECTOR
        val dataBytes = dataBytes(partition.blockCount)
        require(dataBytes >= 1L shl 20) { "The partition is too small for a VeraCrypt volume." }

        val passwordBytes = String(password).toByteArray(Charsets.UTF_8)
        password.fill('\u0000')
        val keyArea = ByteArray(KEY_AREA_BYTES).also { random.nextBytes(it) }
        val plain = plainHeader(dataBytes, keyArea)
        val kdf = KdfParams.forVolume(hash, pim)
        try {
            fun sealed(): ByteArray {
                val salt = ByteArray(64).also { random.nextBytes(it) }
                val enc = try {
                    VeraCryptNative.encryptHeader(cipher.nativeIds, hash.kdfNativeId!!, passwordBytes, salt,
                        kdf.iterations, kdf.memoryKiB, plain)
                } catch (e: IllegalStateException) {
                    throw IllegalStateException(
                        "Argon2 could not derive the key (${e.message}). It needs " +
                            "${kdf.memoryKiB / 1024} MiB of memory at this PIM; close other apps and try again.", e)
                } ?: throw IllegalStateException("Header encryption failed")
                return salt + enc
            }

            progress("Deriving the header key")
            val primary = sealed()
            progress("Deriving the backup header key")
            val backup = sealed()

            progress("Writing the headers")
            val group = ByteArray(HEADER_GROUP_BYTES.toInt())
            random.nextBytes(group); primary.copyInto(group)
            partition.writeBlocks(0, group)
            random.nextBytes(group); backup.copyInto(group)
            partition.writeBlocks((hostBytes - HEADER_GROUP_BYTES) / SECTOR, group)
            group.fill(0)
        } finally {
            passwordBytes.fill(0)
            plain.fill(0)
        }

        val masterKey = keyArea.copyOf(cipher.keySizeBytes)
        keyArea.fill(0)
        val dataOffsetSectors = HEADER_GROUP_BYTES / SECTOR
        return NativeDecryptedBlockDevice(
            partition, masterKey,
            volumeDataOffset = dataOffsetSectors,
            decryptedBlockCount = dataBytes / SECTOR,
            cipherNativeIds = cipher.nativeIds,
            tweakDataOffset = dataOffsetSectors,
        )
    }

    /** The 448 bytes from header offset 64, before encryption. */
    internal fun plainHeader(dataBytes: Long, keyArea: ByteArray): ByteArray {
        require(keyArea.size == KEY_AREA_BYTES)
        val h = ByteBuffer.allocate(448)                       // big-endian, as VeraCrypt stores it
        h.put("VERA".toByteArray(Charsets.US_ASCII))
        h.putShort(5)                                          // header format version
        h.putShort(0x010B)                                     // VeraCrypt 1.11 or later to open
        h.putInt(crc32(keyArea))
        h.position(28)
        h.putLong(0)                                           // hidden volume size: none
        h.putLong(dataBytes)                                   // volume size
        h.putLong(HEADER_GROUP_BYTES)                          // master key scope: data offset
        h.putLong(dataBytes)                                   // encrypted area size
        h.putInt(0)                                            // flags
        h.putInt(SECTOR)                                       // sector size
        val out = h.array()
        ByteBuffer.wrap(out).putInt(188, crc32(out.copyOfRange(0, 188)))
        keyArea.copyInto(out, 192)
        return out
    }

    private fun crc32(b: ByteArray): Int = CRC32().apply { update(b) }.value.toInt()
}

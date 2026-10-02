package app.fayaz.otgmaster.bitlocker

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * A BitLocker (version 2, Windows 7 and later) volume's metadata.
 *
 * Layout, after cryptsetup's lib/bitlk/bitlk.c: sector 0 carries "-FVE-FS-" (or,
 * for BitLocker To Go, a FAT32 discovery volume "MSWIN4.1") and the byte offsets of
 * three copies of the FVE metadata block. Each copy is a 64 KiB area holding a
 * header, a list of entries — the VMKs, each sealed under one protector, the FVEK
 * sealed under the VMK, and where the volume's real first sectors were moved to —
 * and a validation block: a CRC-32 of the block and a SHA-256 of it sealed under
 * the VMK, which is what authenticates the metadata once a protector opens the VMK.
 */
class BitLockerHeader private constructor(
    val sectorSize: Int,
    val togo: Boolean,
    /** Byte offsets of the three 64 KiB FVE metadata areas, from the volume start. */
    val metadataOffsets: LongArray,
    val volumeHeaderOffset: Long,
    val volumeHeaderSize: Long,
    val volumeSize: Long,
    /** fve.encryption: 0x8000–0x8005. */
    val encryption: Int,
    val vmks: List<Vmk>,
    val fvek: SealedKey,
    val validation: SealedKey,
    /** SHA-256 of the FVE block, which the sealed validation datum must equal. */
    val metadataSha256: ByteArray,
    /** Why this volume cannot be opened as-is, or null. */
    val unsupportedReason: String?,
) {
    enum class Protection { CLEAR_KEY, TPM, STARTUP_KEY, TPM_PIN, RECOVERY_PASSWORD, SMART_CARD, PASSWORD, UNKNOWN }

    /** An AES-CCM sealed key: 12-byte nonce, 16-byte tag, ciphertext. */
    class SealedKey(val nonce: ByteArray, val tag: ByteArray, val data: ByteArray)

    class Vmk(
        val protection: Protection,
        val salt: ByteArray?,
        val sealed: SealedKey?,
        /** For a clear-key VMK (protection suspended), the key that unseals it. */
        val clearKey: ByteArray?,
    )

    val mode: Int
        get() = when (encryption) {
            0x8000, 0x8001 -> BitLockerNative.MODE_CBC_ELEPHANT
            0x8002, 0x8003 -> BitLockerNative.MODE_CBC
            0x8004, 0x8005 -> BitLockerNative.MODE_XTS
            else -> throw IOException("unknown BitLocker encryption 0x${encryption.toString(16)}")
        }

    val cipherName: String
        get() = when (encryption) {
            0x8000 -> "AES-CBC 128 + Elephant"
            0x8001 -> "AES-CBC 256 + Elephant"
            0x8002 -> "AES-CBC 128"
            0x8003 -> "AES-CBC 256"
            0x8004 -> "XTS-AES 128"
            0x8005 -> "XTS-AES 256"
            else -> "unknown (0x${encryption.toString(16)})"
        }

    companion object {
        private const val FVE_SIGNATURE = "-FVE-FS-"
        private const val TOGO_SIGNATURE = "MSWIN4.1"
        const val METADATA_AREA_SIZE = 64 * 1024
        private const val STATE_NORMAL = 4

        private val GUID_NORMAL = byteArrayOf(0x3b, 0xd6.toByte(), 0x67, 0x49, 0x29, 0x2e, 0xd8.toByte(), 0x4a,
            0x83.toByte(), 0x99.toByte(), 0xf6.toByte(), 0xa3.toByte(), 0x39, 0xe3.toByte(), 0xd0.toByte(), 0x01)

        /** True if [sector0] is the first sector of a BitLocker volume. */
        fun hasSignature(sector0: ByteArray): Boolean {
            if (sector0.size < 11 || sector0[0] != 0xEB.toByte()) return false
            val sig = String(sector0, 3, 8, Charsets.US_ASCII)
            if (sig == FVE_SIGNATURE) return true
            // To Go: a FAT32 discovery volume whose superblock GUID marks it.
            if (sig != TOGO_SIGNATURE || sector0.size < 424 + 16) return false
            return sector0.copyOfRange(424, 440).contentEquals(GUID_NORMAL)
        }

        /**
         * Parses the metadata. [read] returns [length] bytes at byte [offset] from
         * the volume start.
         */
        fun parse(read: (offset: Long, length: Int) -> ByteArray): BitLockerHeader {
            val s0 = read(0, 512)
            val sig = String(s0, 3, 8, Charsets.US_ASCII)
            val togo = when (sig) {
                FVE_SIGNATURE -> false
                TOGO_SIGNATURE -> true
                else -> throw IOException("not a BitLocker volume")
            }
            if (s0[0] == 0xEB.toByte() && s0[1] == 0x52.toByte()) {
                throw IOException("BitLocker version 1 (Windows Vista) volumes are not supported")
            }
            val b0 = le(s0)
            val sectorSize = (b0.getShort(11).toInt() and 0xFFFF).let { if (it == 0) 512 else it }
            if (sectorSize != 512 && sectorSize != 4096) throw IOException("unsupported BitLocker sector size $sectorSize")
            val sbOff = if (togo) 424 else 160
            val guidNormal = s0.copyOfRange(sbOff, sbOff + 16).contentEquals(GUID_NORMAL)
            val offsets = LongArray(3) { b0.getLong(sbOff + 16 + 8 * it) }

            // Use the first copy whose signature, version and CRC are valid.
            var lastProblem = "no FVE metadata copy is readable"
            for (i in 0 until 3) {
                val off = offsets[i]
                if (off <= 0) continue
                val area = try { read(off, METADATA_AREA_SIZE) } catch (e: Exception) {
                    lastProblem = "FVE metadata copy $i unreadable: ${e.message}"; continue
                }
                val b = le(area)
                if (String(area, 0, 8, Charsets.US_ASCII) != FVE_SIGNATURE || b.getShort(10).toInt() != 2) {
                    lastProblem = "FVE metadata copy $i has no valid signature"; continue
                }
                val fveSize = (b.getShort(8).toInt() and 0xFFFF) shl 4
                if (fveSize < 112 || fveSize + 16 + 72 > METADATA_AREA_SIZE) {
                    lastProblem = "FVE metadata copy $i has an implausible size"; continue
                }
                val crc = CRC32().apply { update(area, 0, fveSize) }.value.toInt()
                if (b.getInt(fveSize + 4) != crc) {
                    lastProblem = "FVE metadata copy $i fails its CRC-32"; continue
                }
                if (b.getLong(32 + 8 * i) != off) {
                    lastProblem = "FVE metadata copy $i is not where it says it is"; continue
                }
                return parseBlock(area, fveSize, sectorSize, togo, guidNormal)
            }
            throw IOException(lastProblem)
        }

        private fun parseBlock(area: ByteArray, fveSize: Int, sectorSize: Int, togo: Boolean,
                               guidNormal: Boolean): BitLockerHeader {
            val b = le(area)
            val currState = b.getShort(12).toInt() and 0xFFFF
            val nextState = b.getShort(14).toInt() and 0xFFFF
            val volumeSize = b.getLong(16)
            val offsets = LongArray(3) { b.getLong(32 + 8 * it) }
            val metadataSize = b.getInt(64)
            val encryption = b.getShort(100).toInt() and 0xFFFF
            if (metadataSize < 48 + 4 || 64 + metadataSize > fveSize) throw IOException("bad FVE metadata size")

            val vmks = mutableListOf<Vmk>()
            var fvek: SealedKey? = null
            var headerOffset = -1L
            var headerSize = -1L
            var pos = 112
            val end = 64 + metadataSize
            while (pos + 8 <= end) {
                val size = b.getShort(pos).toInt() and 0xFFFF
                if (size == 0) break
                if (size < 8 || pos + size > end) throw IOException("FVE metadata entry overruns the block")
                val type = b.getShort(pos + 2).toInt() and 0xFFFF
                val value = b.getShort(pos + 4).toInt() and 0xFFFF
                when {
                    type == 0x0002 && size >= 8 + 28 -> vmks += parseVmk(area, pos, size)
                    type == 0x0003 && fvek == null && value == 0x0005 -> fvek = sealed(area, pos + 8, pos + size)
                    type == 0x000F && size >= 8 + 16 -> {
                        headerOffset = b.getLong(pos + 8)
                        headerSize = b.getLong(pos + 16)
                    }
                }
                pos += size
            }

            // Validation block: size, version, CRC, then a nested datum sealed under the VMK.
            val v = fveSize
            val nestedSize = b.getShort(v + 8).toInt() and 0xFFFF
            val nestedType = b.getShort(v + 12).toInt() and 0xFFFF
            if (nestedSize != 8 + 72 || nestedType != 5) throw IOException("unrecognised FVE validation block")
            val validation = SealedKey(
                area.copyOfRange(v + 16, v + 28), area.copyOfRange(v + 28, v + 44), area.copyOfRange(v + 44, v + 88))

            val reason = when {
                !guidNormal -> "it is mid-way through Windows' encrypt-on-write conversion"
                currState != STATE_NORMAL || nextState != STATE_NORMAL ->
                    "encryption or decryption is in progress (state $currState → $nextState). Let Windows finish it first."
                fvek == null -> "it has no FVEK entry"
                headerOffset < 0 || headerSize <= 0 -> "it has no volume header entry"
                headerSize % sectorSize != 0L || headerOffset % sectorSize != 0L ||
                    offsets.any { it % sectorSize != 0L } -> "its metadata is not sector-aligned"
                encryption !in 0x8000..0x8005 -> "it uses an unknown cipher (0x${encryption.toString(16)})"
                else -> null
            }
            return BitLockerHeader(
                sectorSize = sectorSize,
                togo = togo,
                metadataOffsets = offsets,
                volumeHeaderOffset = headerOffset,
                volumeHeaderSize = headerSize,
                volumeSize = volumeSize,
                encryption = encryption,
                vmks = vmks,
                fvek = fvek ?: SealedKey(ByteArray(12), ByteArray(16), ByteArray(0)),
                validation = validation,
                metadataSha256 = MessageDigest.getInstance("SHA-256").digest(area.copyOfRange(0, fveSize)),
                unsupportedReason = reason,
            )
        }

        private fun parseVmk(area: ByteArray, pos: Int, size: Int): Vmk {
            val b = le(area)
            val protection = when (b.getShort(pos + 8 + 26).toInt() and 0xFFFF) {
                0x0000 -> Protection.CLEAR_KEY
                0x0100 -> Protection.TPM
                0x0200 -> Protection.STARTUP_KEY
                0x0500 -> Protection.TPM_PIN
                0x0800 -> Protection.RECOVERY_PASSWORD
                0x1000 -> Protection.SMART_CARD
                0x2000 -> Protection.PASSWORD
                else -> Protection.UNKNOWN
            }
            var salt: ByteArray? = null
            var sealed: SealedKey? = null
            var clearKey: ByteArray? = null
            var p = pos + 8 + 28
            val end = pos + size
            while (p + 8 <= end) {
                val n = b.getShort(p).toInt() and 0xFFFF
                if (n == 0) break
                if (n < 8 || p + n > end) throw IOException("VMK entry overruns its parent")
                when (b.getShort(p + 4).toInt() and 0xFFFF) {
                    0x0003 -> if (n >= 8 + 4 + 16) salt = area.copyOfRange(p + 12, p + 28)
                    0x0005 -> if (n >= 8 + 28) sealed = sealed(area, p + 8, p + n)
                    0x0001 -> if (n > 12) clearKey = area.copyOfRange(p + 12, p + n)
                }
                p += n
            }
            return Vmk(protection, salt, sealed, clearKey)
        }

        private fun sealed(area: ByteArray, from: Int, to: Int) =
            SealedKey(area.copyOfRange(from, from + 12), area.copyOfRange(from + 12, from + 28),
                area.copyOfRange(from + 28, to))

        private fun le(a: ByteArray) = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
    }
}

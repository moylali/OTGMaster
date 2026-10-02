package app.fayaz.otgmaster.bitlocker

import app.fayaz.otgmaster.block.RawBlockDevice
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Opens a BitLocker volume with a password or a 48-digit recovery password, or
 * with no secret at all when protection is suspended (a clear-key VMK).
 *
 * Every protector unseals the same VMK. Before the VMK is trusted to unseal the
 * FVEK it must also unseal the metadata's validation datum to the SHA-256 of the
 * FVE block that was read — the check that the metadata in use is the metadata
 * the key was sealed with, not a copy someone edited.
 */
class BitLockerUnlocker {

    class WrongPasswordException : Exception("Wrong password or recovery key")
    class UnsupportedVolumeException(message: String) : Exception(message)

    /**
     * @param password the password, or a recovery password in its usual
     *   000000-000000-… form, or empty to try a clear key.
     */
    fun unlock(device: RawBlockDevice, startBlock: Long, blockCount: Long?, password: CharArray): BitLockerBlockDevice {
        val bs = device.blockSize
        val partBlocks = blockCount ?: (device.blockCount - startBlock)
        val read = { offset: Long, length: Int ->
            val first = offset / bs
            val last = (offset + length - 1) / bs
            val raw = device.readBlocks(startBlock + first, (last - first + 1).toInt())
            raw.copyOfRange((offset % bs).toInt(), (offset % bs).toInt() + length)
        }
        val header = BitLockerHeader.parse(read)
        header.unsupportedReason?.let { throw UnsupportedVolumeException("This BitLocker volume cannot be opened: $it") }
        android.util.Log.i(TAG, "BitLocker ${header.cipherName}, sector ${header.sectorSize}, " +
            "${if (header.togo) "To Go, " else ""}protectors: ${header.vmks.map { it.protection }}")

        val fvek = openFvek(header, password) ?: run {
            password.fill('\u0000')
            val usable = header.vmks.map { it.protection }.filter {
                it == BitLockerHeader.Protection.PASSWORD || it == BitLockerHeader.Protection.RECOVERY_PASSWORD
            }
            if (usable.isEmpty()) throw UnsupportedVolumeException(
                "This BitLocker volume has no password or recovery-key protector " +
                    "(it has: ${header.vmks.joinToString { it.protection.name }})")
            throw WrongPasswordException()
        }
        password.fill('\u0000')
        return BitLockerBlockDevice(device, startBlock, partBlocks, header, fvek)
    }

    /** The FVEK key bytes ready for [BitLockerNative.newContext], or null if nothing opened. */
    internal fun openFvek(header: BitLockerHeader, password: CharArray): ByteArray? {
        val recovery = parseRecoveryPassword(password)
        val passwordHash = if (password.isNotEmpty() && recovery == null) {
            val utf16 = String(password).toByteArray(Charsets.UTF_16LE)
            sha256(sha256(utf16)).also { utf16.fill(0) }
        } else null
        val recoveryHash = recovery?.let { sha256(it).also { _ -> it.fill(0) } }

        for (vmk in header.vmks) {
            val vmkKey: ByteArray = when (vmk.protection) {
                BitLockerHeader.Protection.PASSWORD -> {
                    val salt = vmk.salt ?: continue
                    val sealed = vmk.sealed ?: continue
                    val stretched = passwordHash?.let { BitLockerNative.stretchKey(it, salt) } ?: continue
                    unseal(stretched, sealed).also { stretched.fill(0) } ?: continue
                }
                BitLockerHeader.Protection.RECOVERY_PASSWORD -> {
                    val salt = vmk.salt ?: continue
                    val sealed = vmk.sealed ?: continue
                    val stretched = recoveryHash?.let { BitLockerNative.stretchKey(it, salt) } ?: continue
                    unseal(stretched, sealed).also { stretched.fill(0) } ?: continue
                }
                BitLockerHeader.Protection.CLEAR_KEY -> {
                    val key = vmk.clearKey ?: continue
                    unseal(key, vmk.sealed ?: continue) ?: continue
                }
                else -> continue
            }
            try {
                if (!metadataAuthentic(header, vmkKey)) {
                    throw IOException("BitLocker metadata failed authentication — it does not match its key")
                }
                val raw = unseal(vmkKey, header.fvek) ?: continue
                return fvekKeyBytes(header, raw).also { raw.fill(0) }
            } finally {
                vmkKey.fill(0)
            }
        }
        passwordHash?.fill(0)
        recoveryHash?.fill(0)
        return null
    }

    /** Unseals a key entry: AES-CCM, then a 12-byte header whose first u16 is the total size. */
    private fun unseal(key: ByteArray, sealed: BitLockerHeader.SealedKey): ByteArray? {
        val plain = BitLockerNative.ccmDecrypt(key, sealed.nonce, sealed.tag, sealed.data) ?: return null
        val size = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN).getShort(0).toInt() and 0xFFFF
        if (size != plain.size || size < 12) { plain.fill(0); return null }
        return plain.copyOfRange(12, plain.size).also { plain.fill(0) }
    }

    private fun metadataAuthentic(header: BitLockerHeader, vmk: ByteArray): Boolean {
        val v = header.validation
        val datum = BitLockerNative.ccmDecrypt(vmk, v.nonce, v.tag, v.data) ?: return false
        val b = ByteBuffer.wrap(datum).order(ByteOrder.LITTLE_ENDIAN)
        // size, role 0, type 1, flags, hash type 0x2005 (SHA-256), unknown, hash[32]
        return datum.size >= 44 && b.getShort(2).toInt() == 0 && b.getShort(4).toInt() == 1 &&
            (b.getShort(8).toInt() and 0xFFFF) == 0x2005 &&
            datum.copyOfRange(12, 44).contentEquals(header.metadataSha256)
    }

    private fun fvekKeyBytes(header: BitLockerHeader, key: ByteArray): ByteArray {
        val expected = when (header.encryption) {
            0x8002 -> 16
            0x8003, 0x8004 -> 32
            0x8000, 0x8001, 0x8005 -> 64
            else -> throw UnsupportedVolumeException("unknown BitLocker cipher")
        }
        if (key.size < expected) throw IOException("BitLocker FVEK is ${key.size} bytes, expected $expected")
        // AES-128 + Elephant stores 16 B CBC key, 16 B unused, 16 B Elephant key, 16 B unused.
        if (header.encryption == 0x8000) return key.copyOfRange(0, 16) + key.copyOfRange(32, 48)
        return key.copyOfRange(0, expected)
    }

    companion object {
        private const val TAG = "BitLocker"

        private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

        /**
         * The 16-byte key a recovery password stands for: eight groups of six
         * digits, each a multiple of 11, each group/11 a little-endian u16. Null if
         * [input] is not in that form, in which case it is treated as a password.
         */
        fun parseRecoveryPassword(input: CharArray): ByteArray? {
            val s = String(input).trim()
            val groups = s.split('-')
            if (groups.size != 8 || groups.any { it.length != 6 || !it.all(Char::isDigit) }) return null
            val out = ByteArray(16)
            for ((i, g) in groups.withIndex()) {
                val n = g.toInt()
                if (n % 11 != 0 || n / 11 > 0xFFFF) return null
                out[2 * i] = (n / 11).toByte()
                out[2 * i + 1] = ((n / 11) shr 8).toByte()
            }
            return out
        }
    }
}

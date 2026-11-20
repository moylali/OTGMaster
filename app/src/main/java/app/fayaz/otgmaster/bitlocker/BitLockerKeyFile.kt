package app.fayaz.otgmaster.bitlocker

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A BitLocker startup key or recovery key saved as a file (`<GUID>.BEK`), as Windows
 * writes it with `manage-bde -protectors -add -StartupKey` or `-RecoveryKey`, or when it
 * offers to "Save to a file" a key for a removable drive.
 *
 * The file is a small FVE metadata set: a 48-byte header (size, version 1, header size,
 * size again, the protector's GUID, a nonce counter, a method and a time), then one
 * external-key entry (type 6, value 9) holding the GUID again, a time, and nested entries:
 * the 32-byte key (value 1, after a 4-byte method) and usually the file's name (value 2).
 * The key unseals the VMK whose protector has the same GUID. Parsed as cryptsetup's
 * `get_startup_key` does, so a file cryptsetup refuses is refused here too.
 */
class BitLockerKeyFile private constructor(val guid: ByteArray, val key: ByteArray) {

    class NotAKeyFileException(message: String) : Exception(message)

    companion object {
        private const val HEADER = 48
        private const val MAX_SIZE = 64 * 1024

        /** Parses [bytes]; throws [NotAKeyFileException] saying why it is not a .BEK file. */
        fun parse(bytes: ByteArray): BitLockerKeyFile {
            fun no(why: String): Nothing = throw NotAKeyFileException("Not a BitLocker .BEK key file: $why")
            if (bytes.size < HEADER + 8 + 24 || bytes.size > MAX_SIZE) no("it is ${bytes.size} bytes")
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (b.getInt(4) != 1) no("version ${b.getInt(4)}")
            if (b.getInt(0).toLong() != bytes.size.toLong()) no("its size field says ${b.getInt(0)}, the file is ${bytes.size}")
            val guid = bytes.copyOfRange(16, 32)
            val size = b.getShort(HEADER).toInt() and 0xFFFF
            val type = b.getShort(HEADER + 2).toInt() and 0xFFFF
            val value = b.getShort(HEADER + 4).toInt() and 0xFFFF
            if (type != 0x0006 || value != 0x0009) no("its entry is not an external key")
            if (size < 8 + 24 || HEADER + size > bytes.size) no("its external key entry is cut short")
            var p = HEADER + 8 + 24
            val end = HEADER + size
            while (p + 8 <= end) {
                val n = b.getShort(p).toInt() and 0xFFFF
                if (n < 8 || p + n > end) no("an entry overruns the file")
                if ((b.getShort(p + 4).toInt() and 0xFFFF) == 0x0001) {
                    if (n != 8 + 4 + 32) no("its key is ${n - 12} bytes, not 32")
                    return BitLockerKeyFile(guid, bytes.copyOfRange(p + 12, p + n))
                }
                p += n
            }
            no("it holds no key")
        }
    }
}

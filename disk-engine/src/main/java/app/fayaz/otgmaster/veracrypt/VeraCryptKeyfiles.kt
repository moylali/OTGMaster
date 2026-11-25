package app.fayaz.otgmaster.veracrypt

import java.io.IOException
import java.io.InputStream

/**
 * VeraCrypt's keyfile mixing (Common/Keyfiles.c, KeyFilesApply), shared by unlocking and
 * creating, so a volume this app creates with keyfiles opens with the same keyfiles here
 * and in VeraCrypt.
 *
 * Each keyfile's first 1 MiB runs through a CRC-32 whose running value, after every byte,
 * is added big-endian into a pool; the CRC and the write position restart per keyfile and
 * the pool does not, so the order of the keyfiles does not matter. The pool is then added
 * byte by byte onto the password, which is extended to the pool's length.
 *
 * The pool is 64 bytes for a password of up to 64 bytes and 128 above that. The unlocker's
 * own copy of this used 64 always, which cut a longer password down to its first 64 bytes:
 * such a volume, made by VeraCrypt, could not be opened with its keyfiles.
 */
object VeraCryptKeyfiles {

    private const val LEGACY_POOL = 64
    private const val POOL = 128
    private const val MAX_LEGACY_PASSWORD = 64
    private const val MAX_BYTES_READ = 1 shl 20

    /**
     * [password] with [keyfiles] mixed in, as a new array; [password] is wiped. With no
     * keyfiles it is returned as it is. A keyfile that cannot be opened or read, or is
     * empty, throws [IOException]: the unlock fails rather than going on without it.
     */
    fun apply(password: ByteArray, keyfiles: List<() -> InputStream?>): ByteArray {
        if (keyfiles.isEmpty()) return password
        val poolSize = if (password.size <= MAX_LEGACY_PASSWORD) LEGACY_POOL else POOL
        val pool = ByteArray(poolSize)
        val buffer = ByteArray(65536)
        for ((n, open) in keyfiles.withIndex()) {
            try {
                val stream = open() ?: throw IOException("keyfile ${n + 1} could not be opened")
                val read = stream.use { mixOne(it, pool, buffer) }
                if (read == 0) throw IOException("keyfile ${n + 1} is empty")
            } catch (e: Exception) {
                // SecurityException too: a keyfile whose read grant was revoked.
                app.fayaz.otgmaster.util.Secrets.wipe(password, pool, buffer)
                throw e as? IOException ?: IOException("keyfile ${n + 1} could not be read: ${e.message}", e)
            }
        }
        val mixed = ByteArray(maxOf(poolSize, password.size))
        password.copyInto(mixed)
        for (i in 0 until poolSize) mixed[i] = (mixed[i] + pool[i]).toByte()
        app.fayaz.otgmaster.util.Secrets.wipe(password, pool, buffer)
        return mixed
    }

    /** What VeraCrypt's keyfile generator writes by default: 64 random bytes. */
    const val NEW_KEYFILE_BYTES = 64

    /** Writes a new keyfile to [out]: [NEW_KEYFILE_BYTES] bytes from [random]. */
    fun generate(out: java.io.OutputStream, random: java.security.SecureRandom = java.security.SecureRandom()) {
        val bytes = ByteArray(NEW_KEYFILE_BYTES).also { random.nextBytes(it) }
        try {
            out.write(bytes)
            out.flush()
        } finally {
            bytes.fill(0)
        }
    }

    /** Mixes up to 1 MiB of [stream] into [pool]; returns how many bytes it read. */
    private fun mixOne(stream: InputStream, pool: ByteArray, buffer: ByteArray): Int {
        var crc = 0xFFFFFFFFL
        var writePos = 0
        var total = 0
        while (total < MAX_BYTES_READ) {
            val read = stream.read(buffer, 0, minOf(buffer.size, MAX_BYTES_READ - total))
            if (read <= 0) break
            for (i in 0 until read) {
                crc = crc xor (buffer[i].toLong() and 0xFF)
                repeat(8) { crc = if (crc and 1L != 0L) (crc ushr 1) xor 0xEDB88320L else crc ushr 1 }
                for (shift in intArrayOf(24, 16, 8, 0)) {
                    pool[writePos] = (pool[writePos] + ((crc ushr shift) and 0xFF).toInt()).toByte()
                    if (++writePos >= pool.size) writePos = 0
                }
            }
            total += read
        }
        return total
    }
}

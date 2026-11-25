package app.fayaz.otgmaster.util

/**
 * Wiping secret byte arrays (passwords, derived keys, decrypted headers, key material).
 *
 * This shortens how long a secret sits in the heap; it cannot promise erasure, because
 * the JVM may have copied an array before it is wiped, and Strings cannot be wiped at all.
 */
object Secrets {

    /** Zeroes every array given; nulls are skipped. */
    fun wipe(vararg secrets: ByteArray?) {
        for (s in secrets) s?.fill(0)
    }

    /** Zeroes every char array given; nulls are skipped. */
    fun wipe(vararg secrets: CharArray?) {
        for (s in secrets) s?.fill('\u0000')
    }

    /** Runs [block] on [secret] and zeroes [secret] afterwards, however [block] ends. */
    inline fun <T> use(secret: ByteArray, block: (ByteArray) -> T): T =
        try { block(secret) } finally { secret.fill(0) }
}

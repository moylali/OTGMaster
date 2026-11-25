package app.fayaz.otgmaster.luks

/**
 * The algorithm names this app supports in LUKS headers, matched exactly.
 *
 * An unknown name is refused with an error naming the field, never replaced by a
 * default: the old code mapped every unknown hash to SHA-256, which turned an unsupported
 * header into a "wrong password" (review of 2026-11-11, L09).
 */
object LuksAlgorithms {

    /** The only payload and keyslot-area cipher spec supported. */
    const val AES_XTS_PLAIN64 = "aes-xts-plain64"

    private val digests = mapOf("sha1" to "SHA-1", "sha256" to "SHA-256", "sha512" to "SHA-512")
    private val hmacs = mapOf("sha1" to "HmacSHA1", "sha256" to "HmacSHA256", "sha512" to "HmacSHA512")

    /** The JCA MessageDigest name for a LUKS hash name, or UnsupportedFormatException. */
    fun digest(name: String, field: String): String =
        digests[name.lowercase()] ?: unsupported(field, name)

    /** The JCA Mac name for PBKDF2 with a LUKS hash name, or UnsupportedFormatException. */
    fun hmac(name: String, field: String): String =
        hmacs[name.lowercase()] ?: unsupported(field, name)

    /** Requires [spec] to be exactly [AES_XTS_PLAIN64]. */
    fun requireAesXtsPlain64(spec: String, field: String) {
        if (!spec.equals(AES_XTS_PLAIN64, ignoreCase = true)) {
            throw LuksUnlocker.UnsupportedFormatException(
                "$field is \"$spec\"; OTG Master supports only $AES_XTS_PLAIN64. The volume may open " +
                "with cryptsetup on a computer.")
        }
    }

    private fun unsupported(field: String, name: String): Nothing =
        throw LuksUnlocker.UnsupportedFormatException("$field \"$name\" is not supported by OTG Master")
}

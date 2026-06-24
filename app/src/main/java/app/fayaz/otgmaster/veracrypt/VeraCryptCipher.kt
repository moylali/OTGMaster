package app.fayaz.otgmaster.veracrypt

/**
 * The 8 cipher options VeraCrypt offers (3 standalone + 5 cascades). [components] lists the
 * single ciphers applied in encryption order; decryption must reverse this order. [keySizeBytes]
 * is the total XTS key material VeraCrypt stores in the volume header: 64 bytes per cipher in
 * the cascade (two 256-bit XTS keys each).
 *
 * Only AES and Serpent are wired up to native code today ([isSupported]); the rest are listed so
 * the picker can show VeraCrypt's full option set and reject unsupported choices with a clear
 * message instead of silently mis-decrypting.
 */
enum class VeraCryptCipher(
    val displayName: String,
    val components: List<SingleCipher>,
    val isSupported: Boolean
) {
    AES("AES", listOf(SingleCipher.AES), isSupported = true),
    SERPENT("Serpent", listOf(SingleCipher.SERPENT), isSupported = true),
    TWOFISH("Twofish", listOf(SingleCipher.TWOFISH), isSupported = false),
    AES_TWOFISH("AES-Twofish", listOf(SingleCipher.AES, SingleCipher.TWOFISH), isSupported = false),
    AES_TWOFISH_SERPENT(
        "AES-Twofish-Serpent",
        listOf(SingleCipher.AES, SingleCipher.TWOFISH, SingleCipher.SERPENT),
        isSupported = false
    ),
    SERPENT_AES("Serpent-AES", listOf(SingleCipher.SERPENT, SingleCipher.AES), isSupported = false),
    SERPENT_TWOFISH_AES(
        "Serpent-Twofish-AES",
        listOf(SingleCipher.SERPENT, SingleCipher.TWOFISH, SingleCipher.AES),
        isSupported = false
    ),
    TWOFISH_SERPENT("Twofish-Serpent", listOf(SingleCipher.TWOFISH, SingleCipher.SERPENT), isSupported = false);

    /** Total bytes of XTS key material stored in the VeraCrypt header for this cipher choice. */
    val keySizeBytes: Int get() = components.size * 64

    companion object {
        val DEFAULT = AES
    }
}

/** A single cipher within a (possibly cascaded) [VeraCryptCipher] selection. */
enum class SingleCipher(val nativeId: Int) {
    AES(nativeId = 0),
    SERPENT(nativeId = 1),
    TWOFISH(nativeId = -1) // not yet implemented natively
}

enum class VeraCryptHash(val displayName: String, val isSupported: Boolean) {
    SHA512("SHA-512", isSupported = true),
    SHA256("SHA-256", isSupported = false),
    WHIRLPOOL("Whirlpool", isSupported = false),
    STREEBOG("Streebog", isSupported = false);

    companion object {
        val DEFAULT = SHA512
    }
}

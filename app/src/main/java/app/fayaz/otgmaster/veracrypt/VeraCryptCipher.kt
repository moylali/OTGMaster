package app.fayaz.otgmaster.veracrypt

/**
 * The AES/Serpent/Twofish options VeraCrypt offers: three single ciphers and five cascades.
 * (VeraCrypt also has Camellia and Kuznyechik and their cascades; those are not here.)
 *
 * [components] is the cascade in **VeraCrypt's own table order** (EncryptionAlgorithms[] in
 * src/Common/Crypto.c), which is the reverse of the name it shows: "AES-Twofish-Serpent" is
 * Serpent, Twofish, AES. That order is the encryption order — Serpent is applied first — and
 * the order of the keys in the header: each cipher's 32-byte primary key in turn, then each
 * one's 32-byte XTS tweak key in the same order, so [keySizeBytes] is 64 per cipher. Decryption
 * runs the list backwards. An earlier version of this list followed the displayed name; it was
 * never used for a cascade, which is the only reason it never mis-decrypted.
 *
 * The enum names are persisted with saved credentials; do not rename them.
 */
enum class VeraCryptCipher(
    val displayName: String,
    val components: List<SingleCipher>,
    val isSupported: Boolean = true,
) {
    AES("AES", listOf(SingleCipher.AES)),
    SERPENT("Serpent", listOf(SingleCipher.SERPENT)),
    TWOFISH("Twofish", listOf(SingleCipher.TWOFISH)),
    AES_TWOFISH("AES-Twofish", listOf(SingleCipher.TWOFISH, SingleCipher.AES)),
    AES_TWOFISH_SERPENT("AES-Twofish-Serpent", listOf(SingleCipher.SERPENT, SingleCipher.TWOFISH, SingleCipher.AES)),
    SERPENT_AES("Serpent-AES", listOf(SingleCipher.AES, SingleCipher.SERPENT)),
    SERPENT_TWOFISH_AES("Serpent-Twofish-AES", listOf(SingleCipher.AES, SingleCipher.TWOFISH, SingleCipher.SERPENT)),
    TWOFISH_SERPENT("Twofish-Serpent", listOf(SingleCipher.SERPENT, SingleCipher.TWOFISH));

    /** Total bytes of XTS key material stored in the VeraCrypt header for this cipher choice. */
    val keySizeBytes: Int get() = components.size * 64

    /** What the native layer takes: [components] as ids, in the same order. */
    val nativeIds: IntArray get() = components.map { it.nativeId }.toIntArray()

    companion object {
        val DEFAULT = AES
    }
}

/** A single cipher within a (possibly cascaded) [VeraCryptCipher]; ids match vc_cascade.h. */
enum class SingleCipher(val nativeId: Int) {
    AES(nativeId = 0),
    SERPENT(nativeId = 1),
    TWOFISH(nativeId = 2),
}

/**
 * The header key derivation VeraCrypt calls the "hash": a PRF for PBKDF2, or Argon2id,
 * which is a KDF in its own right (VeraCrypt 1.26.24 and later).
 *
 * [kdfNativeId] matches the KDF_* ids in VeraCryptNative.cpp; null means not implemented.
 * The enum names are persisted with saved credentials; do not rename them.
 */
enum class VeraCryptHash(val displayName: String, val kdfNativeId: Int?) {
    SHA512("SHA-512", kdfNativeId = 0),
    ARGON2("Argon2", kdfNativeId = 1),
    SHA256("SHA-256", kdfNativeId = null),
    WHIRLPOOL("Whirlpool", kdfNativeId = null),
    STREEBOG("Streebog", kdfNativeId = null);

    val isSupported: Boolean get() = kdfNativeId != null

    companion object {
        val DEFAULT = SHA512
    }
}

/**
 * The cost parameters VeraCrypt derives a header key with, from the PIM (0 or null for none).
 * [iterations] is PBKDF2's iteration count, or Argon2id's time cost; [memoryKiB] is Argon2id's
 * memory cost and 0 for PBKDF2.
 */
data class KdfParams(val iterations: Int, val memoryKiB: Int) {
    companion object {
        /** Non-system volumes only (OTG Master opens no system volumes). */
        fun forVolume(hash: VeraCryptHash, pim: Int?): KdfParams {
            val p = pim?.coerceAtLeast(0) ?: 0
            return when (hash) {
                VeraCryptHash.ARGON2 -> argon2(p)
                // VeraCrypt's get_pkcs5_iteration_count: 500000 by default for SHA-512,
                // SHA-256, Whirlpool and Streebog alike, else 15000 + PIM x 1000.
                else -> KdfParams(if (p == 0) 500_000 else 15_000 + p * 1000, 0)
            }
        }

        /**
         * get_argon2_params in VeraCrypt's src/Common/Pkcs5.c. No PIM means PIM 12: 416 MiB
         * and 6 passes. Memory grows 32 MiB per step from 64 MiB and stops at 1 GiB (PIM 31);
         * the time cost grows one pass per three steps up to 13, then one per step.
         */
        private fun argon2(pim: Int): KdfParams {
            val p = if (pim == 0) 12 else pim
            val memoryMiB = minOf(64 + (p - 1) * 32, 1024)
            val passes = if (p <= 31) 3 + (p - 1) / 3 else 13 + (p - 31)
            return KdfParams(passes, memoryMiB * 1024)
        }
    }
}

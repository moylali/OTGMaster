package app.fayaz.otgmaster.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * The RSA keypair behind [LockedStore], in the Android keystore. Its private key is
 * usable only within [WINDOW_SECONDS] of a fingerprint (class 3) or the screen-lock
 * PIN, pattern or password, and the keystore, not this app, enforces that.
 *
 * Enrolling another fingerprint does not destroy it (setInvalidatedByBiometricEnrollment
 * false): that would delete every saved password whenever someone added a finger.
 * Removing the screen lock does, as Android always does for such keys; the saved
 * passwords then cannot be read and are cleared ([CredentialStore]).
 */
class AndroidLockKeys(private val alias: String = ALIAS) : LockedStore.Keys {

    private val ks: KeyStore get() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun publicKey(): PublicKey? {
        val cert = ks.getCertificate(alias) ?: run {
            if (!create()) return null
            ks.getCertificate(alias) ?: return null
        }
        // A software copy for encrypting: a public-key operation needs no keystore, and
        // some keystore providers refuse OAEP parameters on their own public key objects.
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(cert.publicKey.encoded))
    }

    override fun privateKey(): PrivateKey? = ks.getKey(alias, null) as? PrivateKey

    override fun delete() {
        runCatching { ks.deleteEntry(alias) }
    }

    /** False when the phone has no secure screen lock, so no such key can exist. */
    private fun create(): Boolean = try {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT)
            .setKeySize(2048)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(false)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(WINDOW_SECONDS,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(WINDOW_SECONDS)
                }
            }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, PROVIDER).apply { initialize(spec) }.generateKeyPair()
        true
    } catch (e: Exception) {
        // InvalidAlgorithmParameterException / IllegalStateException: "Secure lock screen
        // must be enabled to create keys requiring user authentication".
        android.util.Log.w("OTGMaster", "AndroidLockKeys: cannot create the key: ${e.message}")
        false
    }

    companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "otg_cred_lock_v1"
        /** How long after a fingerprint or PIN saved passwords can be read. The owner's choice, 2026-11-18. */
        const val WINDOW_SECONDS = 30
    }
}

/** [LockedStore.Kv] over a SharedPreferences file. */
class PrefsKv(private val prefs: SharedPreferences) : LockedStore.Kv {
    override fun all(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    override fun put(name: String, value: String) { prefs.edit().putString(name, value).commit() }
    override fun remove(name: String) { prefs.edit().remove(name).commit() }
    override fun clear() { prefs.edit().clear().commit() }

    companion object {
        fun open(context: Context, file: String) = PrefsKv(context.getSharedPreferences(file, Context.MODE_PRIVATE))
    }
}

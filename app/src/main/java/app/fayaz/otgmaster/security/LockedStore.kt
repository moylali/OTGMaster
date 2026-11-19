package app.fayaz.otgmaster.security

import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Values that only the phone's lock can open (#165).
 *
 * Saved drive passwords used to sit in EncryptedSharedPreferences under a keystore key
 * that any code running as this app could use at any time: the fingerprint prompt in
 * front of auto mount was the app's own UI step, and nothing below it checked that a
 * fingerprint had been given. Here each value is encrypted with a fresh AES-256-GCM key,
 * and that key is wrapped with the public half of an RSA keypair whose private half the
 * keystore releases only within [AndroidLockKeys.WINDOW_SECONDS] of a fingerprint or the
 * screen-lock PIN. So:
 *
 * - saving needs no prompt: a password is saved right after the user typed it, and only
 *   the public key is used;
 * - reading needs the phone's lock, enforced by the keystore hardware, not by this app;
 * - whether a value exists is visible without the lock, which auto mount needs to decide
 *   whether to prompt at all. Names are hashed, so the file does not list drive serials.
 *
 * The keystore and the storage are behind [Keys] and [Kv], so the envelope and the
 * migration run in host tests.
 */
class LockedStore(private val kv: Kv, private val keys: Keys) {

    /** Where the envelopes are kept: a SharedPreferences file on the phone. */
    interface Kv {
        fun all(): Map<String, String>
        fun put(name: String, value: String)
        fun remove(name: String)
        fun clear()
    }

    /** The keypair. [publicKey] creates it on first use; null where it cannot exist (no screen lock). */
    interface Keys {
        fun publicKey(): PublicKey?
        /** Throws the keystore's UserNotAuthenticatedException outside the window. */
        fun privateKey(): PrivateKey?
        fun delete()
    }

    fun has(name: String): Boolean = name in kv.all()

    fun names(prefix: String): List<String> = kv.all().keys.filter { it.startsWith(prefix) }

    /** False if no key can exist on this phone (no screen lock): nothing is saved then. */
    fun put(name: String, plaintext: ByteArray): Boolean {
        val pub = keys.publicKey() ?: return false
        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val body = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, aes, GCMParameterSpec(128, iv))
            updateAAD(name.toByteArray())   // an envelope moved to another name does not open
            doFinal(plaintext)
        }
        val wrapped = rsa().run { init(Cipher.ENCRYPT_MODE, pub, OAEP); doFinal(aes.encoded) }
        aes.encoded.fill(0)
        val out = java.io.ByteArrayOutputStream()
        out.write(VERSION)
        out.write(wrapped.size shr 8); out.write(wrapped.size and 0xFF)
        out.write(wrapped); out.write(iv); out.write(body)
        kv.put(name, java.util.Base64.getEncoder().encodeToString(out.toByteArray()))
        return true
    }

    /**
     * The value, or null if there is none. Outside the window after the phone's lock the
     * keystore throws UserNotAuthenticatedException, which reaches the caller.
     */
    fun get(name: String): ByteArray? {
        val raw = kv.all()[name] ?: return null
        val b = java.util.Base64.getDecoder().decode(raw)
        require(b.isNotEmpty() && b[0].toInt() == VERSION) { "unknown envelope version" }
        val wl = ((b[1].toInt() and 0xFF) shl 8) or (b[2].toInt() and 0xFF)
        val wrapped = b.copyOfRange(3, 3 + wl)
        val iv = b.copyOfRange(3 + wl, 3 + wl + 12)
        val body = b.copyOfRange(3 + wl + 12, b.size)
        val priv = keys.privateKey() ?: return null
        val aesBytes = rsa().run { init(Cipher.DECRYPT_MODE, priv, OAEP); doFinal(wrapped) }
        try {
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(aesBytes, "AES"), GCMParameterSpec(128, iv))
                updateAAD(name.toByteArray())
                doFinal(body)
            }
        } finally {
            aesBytes.fill(0)
        }
    }

    fun remove(name: String) = kv.remove(name)

    /** Every value, and the keypair: what removing the screen lock leaves unreadable anyway. */
    fun clear() {
        kv.clear()
        keys.delete()
    }

    companion object {
        private const val VERSION = 1
        private val random = SecureRandom()

        /**
         * OAEP with SHA-256, and MGF1 with SHA-1: the combination Android's keystore accepts
         * for decryption on every release since 23, and it is stated explicitly on both
         * sides so the software provider that encrypts agrees with it.
         */
        private val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

        private fun rsa(): Cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")

        /** "d_<sha256 of the sanitized device key>_sb<start block>": no serial in the clear. */
        fun nameFor(sanitizedDeviceKey: String, startBlock: Long): String = "${devicePrefix(sanitizedDeviceKey)}$startBlock"

        fun devicePrefix(sanitizedDeviceKey: String): String {
            val h = MessageDigest.getInstance("SHA-256").digest(sanitizedDeviceKey.toByteArray())
            return "d_" + h.joinToString("") { "%02x".format(it) } + "_sb"
        }
    }
}

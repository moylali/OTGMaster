package app.fayaz.otgmaster.backup.restic

import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * restic's encryption (doc/design.rst, "Keys, Encryption and MAC"), so that a repository
 * the app writes opens in restic and one restic writes opens here.
 *
 * Every encrypted object is `IV (16) | AES-256-CTR ciphertext | MAC (16)`. The MAC is
 * Poly1305-AES over the ciphertext: Poly1305 keyed with `r` and with `AES-128(k, IV)`
 * as its pad. A repository's data is encrypted under one random master key; each key
 * file in `keys/` holds that master key encrypted under a key scrypt derives from a
 * password.
 */
class ResticKey(val encrypt: ByteArray, val macK: ByteArray, val macR: ByteArray) {
    init {
        require(encrypt.size == 32 && macK.size == 16 && macR.size == 16) { "restic keys are 32 + 16 + 16 bytes" }
    }

    class AuthenticationException(message: String) : GeneralSecurityException(message)

    fun decrypt(data: ByteArray): ByteArray {
        if (data.size < OVERHEAD) throw AuthenticationException("ciphertext of ${data.size} bytes is shorter than IV and MAC")
        val iv = data.copyOfRange(0, 16)
        val ctLen = data.size - OVERHEAD
        val expected = mac(iv, data, 16, ctLen)
        val got = data.copyOfRange(data.size - 16, data.size)
        if (!MessageDigest.isEqual(expected, got)) throw AuthenticationException("MAC mismatch: wrong key, or the data is damaged")
        return ctr(iv).doFinal(data, 16, ctLen)
    }

    fun encrypt(plain: ByteArray, random: SecureRandom = rng): ByteArray {
        val iv = ByteArray(16).also { random.nextBytes(it) }
        val out = ByteArray(plain.size + OVERHEAD)
        System.arraycopy(iv, 0, out, 0, 16)
        ctr(iv).doFinal(plain, 0, plain.size, out, 16)
        val tag = mac(iv, out, 16, plain.size)
        System.arraycopy(tag, 0, out, 16 + plain.size, 16)
        return out
    }

    private fun ctr(iv: ByteArray): Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(encrypt, "AES"), IvParameterSpec(iv))
    }

    /** Poly1305-AES: r from the key, s = AES-128(k, IV). */
    private fun mac(iv: ByteArray, msg: ByteArray, off: Int, len: Int): ByteArray {
        val s = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(macK, "AES")) }.doFinal(iv)
        return Poly1305.mac(macR + s, msg, off, len)
    }

    /** The master key as restic stores it inside a key file. */
    fun toJson(): JSONObject = JSONObject()
        .put("mac", JSONObject().put("k", b64(macK)).put("r", b64(macR)))
        .put("encrypt", b64(encrypt))

    companion object {
        const val OVERHEAD = 32
        private val rng = SecureRandom()

        fun random(random: SecureRandom = rng): ResticKey {
            val b = ByteArray(64).also { random.nextBytes(it) }
            return ResticKey(b.copyOfRange(0, 32), b.copyOfRange(32, 48), b.copyOfRange(48, 64))
        }

        fun fromJson(o: JSONObject): ResticKey {
            val mac = o.getJSONObject("mac")
            return ResticKey(unb64(o.getString("encrypt")), unb64(mac.getString("k")), unb64(mac.getString("r")))
        }

        /** The key scrypt derives from [password]: 32 bytes to encrypt, then 16 + 16 for the MAC. */
        fun derive(password: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int): ResticKey {
            val b = Scrypt.derive(password, salt, n, r, p, 64)
            return ResticKey(b.copyOfRange(0, 32), b.copyOfRange(32, 48), b.copyOfRange(48, 64))
        }

        fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
        fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
    }
}

/**
 * A file in `keys/`: scrypt parameters, a salt, and the master key encrypted under what
 * they derive from the password.
 */
object ResticKeyFile {

    /** The master key in [json], if [password] opens it; null if it does not. */
    fun open(json: JSONObject, password: ByteArray): ResticKey? {
        require(json.getString("kdf") == "scrypt") { "unsupported key derivation ${json.getString("kdf")}" }
        val user = ResticKey.derive(password, ResticKey.unb64(json.getString("salt")),
            json.getInt("N"), json.getInt("r"), json.getInt("p"))
        val plain = try {
            user.decrypt(ResticKey.unb64(json.getString("data")))
        } catch (e: ResticKey.AuthenticationException) {
            return null
        }
        return ResticKey.fromJson(JSONObject(String(plain, Charsets.UTF_8)))
    }

    /**
     * A key file for [master] under [password]. scrypt's cost is restic's own default
     * floor (N = 2^15, r = 8, p = 1): about a quarter of a second on a phone, once per open.
     */
    fun create(
        master: ResticKey, password: ByteArray, username: String, hostname: String, created: String,
        n: Int = 1 shl 15, r: Int = 8, p: Int = 1, random: SecureRandom = SecureRandom(),
    ): JSONObject {
        val salt = ByteArray(64).also { random.nextBytes(it) }
        val user = ResticKey.derive(password, salt, n, r, p)
        val data = user.encrypt(master.toJson().toString().toByteArray(Charsets.UTF_8), random)
        return JSONObject()
            .put("created", created).put("username", username).put("hostname", hostname)
            .put("kdf", "scrypt").put("N", n).put("r", r).put("p", p)
            .put("salt", ResticKey.b64(salt)).put("data", ResticKey.b64(data))
    }
}

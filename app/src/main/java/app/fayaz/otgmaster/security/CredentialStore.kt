package app.fayaz.otgmaster.security

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

class CredentialStore(context: Context) {

    private val exclusionPrefs = context.getSharedPreferences("otg_exclusions", Context.MODE_PRIVATE)

    fun loadExcludedKeys(): Set<String> {
        val json = exclusionPrefs.getString("excluded_devices", null) ?: return emptySet()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (e: Exception) { emptySet() }
    }

    fun isExcluded(deviceKey: String): Boolean = deviceKey in loadExcludedKeys()

    fun setExcluded(deviceKey: String, excluded: Boolean) {
        val current = loadExcludedKeys().toMutableSet()
        if (excluded) current.add(deviceKey) else current.remove(deviceKey)
        exclusionPrefs.edit().putString("excluded_devices", JSONArray(current.toList()).toString()).apply()
    }

    data class Credentials(
        val password: String,
        val pim: String,
        val keyfileUris: List<Uri>,
        val cipherName: String,
        val hashName: String
    )

    private val prefs = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "otg_cred_store",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        null
    }

    fun has(deviceKey: String): Boolean = prefs?.contains(sanitize(deviceKey)) == true

    fun save(
        deviceKey: String,
        password: String,
        pim: String,
        keyfileUris: List<Uri>,
        cipherName: String,
        hashName: String
    ) {
        val json = JSONObject().apply {
            put("password", password)
            put("pim", pim)
            put("keyfiles", JSONArray(keyfileUris.map { it.toString() }))
            put("cipher", cipherName)
            put("hash", hashName)
        }.toString()
        prefs?.edit()?.putString(sanitize(deviceKey), json)?.apply()
    }

    fun load(deviceKey: String): Credentials? {
        val json = prefs?.getString(sanitize(deviceKey), null) ?: return null
        return try {
            val obj = JSONObject(json)
            val arr = obj.getJSONArray("keyfiles")
            val uris = (0 until arr.length()).mapNotNull { i ->
                try { Uri.parse(arr.getString(i)) } catch (e: Exception) { null }
            }
            Credentials(
                password = obj.getString("password"),
                pim = obj.optString("pim", ""),
                keyfileUris = uris,
                cipherName = obj.optString("cipher", "AES"),
                hashName = obj.optString("hash", "SHA_512")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun delete(deviceKey: String) {
        prefs?.edit()?.remove(sanitize(deviceKey))?.apply()
    }

    // SharedPreferences keys can't contain certain characters
    private fun sanitize(key: String): String =
        "cred_${key.replace(Regex("[^a-zA-Z0-9_]"), "_")}"
}

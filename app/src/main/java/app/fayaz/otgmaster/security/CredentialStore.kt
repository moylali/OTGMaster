package app.fayaz.otgmaster.security

import android.content.Context
import android.content.SharedPreferences
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
        val hashName: String,
        val candidateStartBlock: Long = 0L
    )

    private val prefs = openEncryptedPrefs(context)

    private fun openEncryptedPrefs(context: Context): SharedPreferences? {
        fun build(): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                "otg_cred_store",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
        return try {
            build()
        } catch (e: Exception) {
            // Prefs are undecryptable — stale file from a previous install or a key rotation.
            // Delete and start fresh; losing cached credentials is recoverable (re-enter once).
            android.util.Log.w("OTGMaster", "CredentialStore: encrypted prefs unreadable, wiping (${e.message})")
            try { context.deleteSharedPreferences("otg_cred_store") } catch (_: Exception) {}
            try { build() } catch (e2: Exception) {
                android.util.Log.e("OTGMaster", "CredentialStore: prefs recreate failed (${e2.message})")
                null
            }
        }
    }

    // Per-partition key: "cred_<sanitized-device>_sb<startBlock>"
    private fun partitionKey(deviceKey: String, startBlock: Long) =
        "cred_${sanitize(deviceKey)}_sb${startBlock}"

    // Legacy key (one-record-per-device, pre-partition era).
    private fun legacyKey(deviceKey: String) = "cred_${sanitize(deviceKey)}"

    // Prefix used to find all partition entries for a device when scanning with getAll().
    private fun devicePrefix(deviceKey: String) = "cred_${sanitize(deviceKey)}_sb"

    fun hasAny(deviceKey: String): Boolean {
        val p = prefs ?: return false
        val prefix = devicePrefix(deviceKey)
        if (p.all.keys.any { it.startsWith(prefix) }) return true
        return p.contains(legacyKey(deviceKey))
    }

    fun save(
        deviceKey: String,
        startBlock: Long,
        password: String,
        pim: String,
        keyfileUris: List<Uri>,
        cipherName: String,
        hashName: String,
    ) {
        val json = JSONObject().apply {
            put("password", password)
            put("pim", pim)
            put("keyfiles", JSONArray(keyfileUris.map { it.toString() }))
            put("cipher", cipherName)
            put("hash", hashName)
            put("startBlock", startBlock)
        }.toString()
        prefs?.edit()?.putString(partitionKey(deviceKey, startBlock), json)?.apply()
    }

    /** Returns all stored credentials for this device, one per partition. */
    fun loadAll(deviceKey: String): List<Credentials> {
        val p = prefs ?: return emptyList()
        val prefix = devicePrefix(deviceKey)
        val results = p.all.entries
            .filter { it.key.startsWith(prefix) }
            .mapNotNull { (_, v) -> parseCredentials(v as? String ?: return@mapNotNull null) }
        if (results.isNotEmpty()) return results
        // Migrate legacy single-entry.
        val legacy = parseCredentials(p.getString(legacyKey(deviceKey), null) ?: return emptyList())
            ?: return emptyList()
        // Write to new format and delete old key so next call uses the new path.
        save(deviceKey, legacy.candidateStartBlock, legacy.password, legacy.pim,
            legacy.keyfileUris, legacy.cipherName, legacy.hashName)
        p.edit().remove(legacyKey(deviceKey)).apply()
        return listOf(legacy)
    }

    fun load(deviceKey: String, startBlock: Long): Credentials? {
        return parseCredentials(prefs?.getString(partitionKey(deviceKey, startBlock), null) ?: return null)
    }

    fun deletePartition(deviceKey: String, startBlock: Long) {
        prefs?.edit()?.remove(partitionKey(deviceKey, startBlock))?.apply()
    }

    fun deleteAll(deviceKey: String) {
        val p = prefs?.edit() ?: return
        val prefix = devicePrefix(deviceKey)
        prefs!!.all.keys.filter { it.startsWith(prefix) }.forEach { p.remove(it) }
        p.remove(legacyKey(deviceKey))
        p.apply()
    }

    fun clearAll() {
        prefs?.edit()?.clear()?.apply()
    }

    private fun parseCredentials(json: String?): Credentials? {
        if (json == null) return null
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
                hashName = obj.optString("hash", "SHA_512"),
                candidateStartBlock = obj.optLong("startBlock", 0L)
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun sanitize(key: String): String =
        key.replace(Regex("[^a-zA-Z0-9_]"), "_")
}

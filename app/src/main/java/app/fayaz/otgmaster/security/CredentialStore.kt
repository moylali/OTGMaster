package app.fayaz.otgmaster.security

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.fayaz.otgmaster.veracrypt.ContainerType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved drive passwords (auto mount and quick unlock), readable only within 30 s of the
 * phone's lock: a fingerprint or the screen-lock PIN (#165; [LockedStore], [AndroidLockKeys]).
 * Saving needs no prompt; reading does, and the keystore enforces it.
 *
 * Until 0.5.4 they were in EncryptedSharedPreferences ("otg_cred_store") under a key any
 * code running as the app could use. They are moved on the first start after the update,
 * with no prompt, since writing needs only the public key; the old file is deleted once
 * every entry is across. A phone with no screen lock cannot hold such a key, so there
 * nothing is saved and the old file stays until a lock is set.
 */
class CredentialStore(
    private val context: Context,
    keys: LockedStore.Keys = AndroidLockKeys(),
    kv: LockedStore.Kv = PrefsKv.open(context, VAULT_FILE),
) {

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
        val candidateStartBlock: Long = 0L,
        val containerType: ContainerType = ContainerType.VERACRYPT
    )

    private val store = LockedStore(kv, keys)

    init {
        migrateLegacy()
    }

    private fun name(deviceKey: String, startBlock: Long) = LockedStore.nameFor(sanitize(deviceKey), startBlock)

    /** Whether any partition of this drive has a saved password. Needs no lock. */
    fun hasAny(deviceKey: String): Boolean = store.names(LockedStore.devicePrefix(sanitize(deviceKey))).isNotEmpty()

    /** Whether this partition has a saved password. Needs no lock. */
    fun has(deviceKey: String, startBlock: Long): Boolean = store.has(name(deviceKey, startBlock))

    /** False when nothing could be saved: the phone has no screen lock. */
    fun save(
        deviceKey: String,
        startBlock: Long,
        password: String,
        pim: String,
        keyfileUris: List<Uri>,
        cipherName: String,
        hashName: String,
        containerType: ContainerType = ContainerType.VERACRYPT,
    ): Boolean {
        val json = JSONObject().apply {
            put("password", password)
            put("pim", pim)
            put("keyfiles", JSONArray(keyfileUris.map { it.toString() }))
            put("cipher", cipherName)
            put("hash", hashName)
            put("startBlock", startBlock)
            put("containerType", containerType.name)
        }.toString()
        return store.put(name(deviceKey, startBlock), json.toByteArray(Charsets.UTF_8))
    }

    /** All saved credentials for this drive, one per partition. Call within 30 s of the phone's lock. */
    fun loadAll(deviceKey: String): List<Credentials> =
        store.names(LockedStore.devicePrefix(sanitize(deviceKey))).mapNotNull { read(it) }

    /** Call within 30 s of the phone's lock. */
    fun load(deviceKey: String, startBlock: Long): Credentials? = read(name(deviceKey, startBlock))

    private fun read(name: String): Credentials? = try {
        store.get(name)?.let { parseCredentials(String(it, Charsets.UTF_8)) }
    } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
        // The screen lock was removed: Android destroyed the key, and nothing saved can be read.
        app.fayaz.otgmaster.OtgMasterState.log(context.getString(app.fayaz.otgmaster.R.string.log_credentials_lock_removed))
        store.clear()
        null
    } catch (e: android.security.keystore.UserNotAuthenticatedException) {
        // Outside the window: the prompt was passed more than 30 s ago. The form is still there.
        app.fayaz.otgmaster.OtgMasterState.log(context.getString(app.fayaz.otgmaster.R.string.log_credentials_need_lock))
        null
    } catch (e: Exception) {
        app.fayaz.otgmaster.OtgMasterState.logFailure("credentials", "A saved password could not be read", e)
        null
    }

    fun deletePartition(deviceKey: String, startBlock: Long) = store.remove(name(deviceKey, startBlock))

    fun deleteAll(deviceKey: String) {
        store.names(LockedStore.devicePrefix(sanitize(deviceKey))).forEach { store.remove(it) }
    }

    fun clearAll() {
        store.names("").forEach { store.remove(it) }
        // Not saved yet when the phone had no screen lock; cleared all the same.
        runCatching { context.deleteSharedPreferences(LEGACY_FILE) }
    }

    /** Moves passwords saved before 0.5.4 into [store]. See the class comment. */
    private fun migrateLegacy() {
        val file = java.io.File(context.applicationInfo.dataDir, "shared_prefs/$LEGACY_FILE.xml")
        if (!file.exists()) return
        val legacy = runCatching {
            EncryptedSharedPreferences.create(
                context, LEGACY_FILE,
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            // As before 0.5.4: an old store that cannot be decrypted is lost, and re-entered once.
            app.fayaz.otgmaster.OtgMasterState.logFailure("credentials",
                "Saved passwords could not be decrypted (Android keystore changed?) and were cleared", it)
            context.deleteSharedPreferences(LEGACY_FILE)
            return
        }
        val entries = legacy.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
        if (migrate(entries, store)) {
            context.deleteSharedPreferences(LEGACY_FILE)
            if (entries.isNotEmpty()) app.fayaz.otgmaster.OtgMasterState.log(
                context.getString(app.fayaz.otgmaster.R.string.log_credentials_moved, entries.size))
        }
    }

    private fun parseCredentials(json: String?): Credentials? {
        if (json == null) return null
        return try {
            val obj = JSONObject(json)
            val arr = obj.getJSONArray("keyfiles")
            val uris = (0 until arr.length()).mapNotNull { i ->
                try { Uri.parse(arr.getString(i)) } catch (e: Exception) { null }
            }
            val typeStr = obj.optString("containerType", ContainerType.VERACRYPT.name)
            val containerType = try {
                ContainerType.valueOf(typeStr)
            } catch (_: IllegalArgumentException) {
                ContainerType.VERACRYPT
            }
            Credentials(
                password = obj.getString("password"),
                pim = obj.optString("pim", ""),
                keyfileUris = uris,
                cipherName = obj.optString("cipher", "AES"),
                hashName = obj.optString("hash", "SHA_512"),
                candidateStartBlock = obj.optLong("startBlock", 0L),
                containerType = containerType
            )
        } catch (e: Exception) {
            null
        }
    }


    companion object {
        const val VAULT_FILE = "otg_cred_lock"
        const val LEGACY_FILE = "otg_cred_store"

        /**
         * Copies the pre-0.5.4 entries into [store], under their new names. False if the
         * store cannot save (no screen lock): the caller then keeps the old file.
         * "cred_<sanitized device>_sb<start>", and the older one-per-device "cred_<sanitized
         * device>" whose start block is inside the value.
         */
        fun migrate(entries: Map<String, String>, store: LockedStore): Boolean {
            for ((key, json) in entries) {
                val name = legacyName(key, json) ?: continue
                if (!store.put(name, json.toByteArray(Charsets.UTF_8))) return false
            }
            return true
        }

        internal fun legacyName(key: String, json: String): String? {
            if (!key.startsWith("cred_")) return null
            val rest = key.removePrefix("cred_")
            val sb = rest.lastIndexOf("_sb")
            if (sb > 0 && rest.substring(sb + 3).let { it.isNotEmpty() && it.all(Char::isDigit) }) {
                return LockedStore.nameFor(rest.substring(0, sb), rest.substring(sb + 3).toLong())
            }
            val start = runCatching { JSONObject(json).optLong("startBlock", 0L) }.getOrDefault(0L)
            return LockedStore.nameFor(rest, start)
        }

        internal fun sanitize(key: String): String =
            key.replace(Regex("[^a-zA-Z0-9_]"), "_")
    }
}

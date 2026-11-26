package app.fayaz.containermaster

import android.content.Context
import app.fayaz.otgmaster.security.AndroidLockKeys
import app.fayaz.otgmaster.security.LockedStore
import app.fayaz.otgmaster.security.PrefsKv
import org.json.JSONObject

/**
 * Passwords saved for containers, under the phone's lock (OTG Master's [LockedStore]): saving
 * needs no prompt, reading needs a fingerprint or the screen lock within the last
 * [AndroidLockKeys.WINDOW_SECONDS], and the keystore enforces that, not this app.
 *
 * Keyed by the container's ID, which comes from the file's own header
 * (ContainerSessions.passwordIdFor), so picking the same file again finds its password
 * whatever URI the picker returns. Lives in the screen's process: a password crosses to the
 * engine only inside the open call, as a typed one does.
 */
class SavedPasswords(context: Context) {
    private val store = LockedStore(PrefsKv.open(context.applicationContext, FILE), AndroidLockKeys(ALIAS))

    data class Saved(val password: CharArray, val pim: Int?)

    fun has(id: String): Boolean = store.has(name(id))

    /** False when the phone has no screen lock, so nothing can be saved. */
    fun save(id: String, password: CharArray, pim: Int?): Boolean {
        val json = JSONObject().put("p", String(password)).apply { pim?.let { put("pim", it) } }.toString()
        val bytes = json.toByteArray(Charsets.UTF_8)
        try {
            return store.put(name(id), bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /** Only within the window after the phone's lock; throws the keystore's exception outside it. */
    fun load(id: String): Saved? {
        val bytes = store.get(name(id)) ?: return null
        try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            return Saved(o.getString("p").toCharArray(), if (o.has("pim")) o.getInt("pim") else null)
        } finally {
            bytes.fill(0)
        }
    }

    fun forget(id: String) = store.remove(name(id))

    fun forgetAll() = store.clear()

    private fun name(id: String) = "c_$id"

    companion object {
        private const val FILE = "saved_passwords"
        private const val ALIAS = "cm_cred_lock_v1"
    }
}

package app.fayaz.otgmaster.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Each incremental job's restic repository password, encrypted with a key in the
 * Android keystore.
 *
 * The password is what opens the backups, on this phone and with `restic` on a
 * desktop, so the job screen shows it and offers to save it to a password manager.
 * Unlike CredentialStore, a store that cannot be decrypted is never wiped: that would
 * delete the only copy of a password the user may not have saved. A job whose password
 * cannot be read asks for it instead.
 */
class BackupSecrets(context: Context) {
    private val prefs: SharedPreferences? = runCatching {
        EncryptedSharedPreferences.create(
            context,
            FILE,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.onFailure {
        app.fayaz.otgmaster.OtgMasterState.log("Saved backup passwords could not be decrypted (left in place): ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    fun password(jobId: String): String? = prefs?.getString(jobId, null)

    fun setPassword(jobId: String, password: String) {
        val p = prefs ?: throw IllegalStateException("the keystore-backed password store is unavailable")
        check(p.edit().putString(jobId, password).commit()) { "could not save the backup password" }
    }

    fun forget(jobId: String) { prefs?.edit()?.remove(jobId)?.apply() }

    companion object {
        private const val FILE = "otg_backup_secrets"

        /** A new repository's password: 24 characters from a 56-letter alphabet without look-alikes (~139 bits). */
        fun generate(random: SecureRandom = SecureRandom()): String {
            val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            return buildString { repeat(24) { if (it > 0 && it % 6 == 0) append('-'); append(alphabet[random.nextInt(alphabet.length)]) } }
        }
    }
}

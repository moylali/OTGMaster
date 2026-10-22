package app.fayaz.otgmaster.security

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException

/**
 * The user's password manager (Google Password Manager, Bitwarden, 1Password, ...) through
 * Android's Credential Manager. Separate from [CredentialStore], which is the app's own
 * encrypted store for auto mount: this one is the user's, outside the app, and only reached
 * when the user asks.
 *
 * Built without credentials-play-services-auth, so the APK carries no Google Play Services
 * (F-Droid). That leaves Android 14+, where the framework itself provides Credential
 * Manager; below it [isAvailable] is false and the buttons are not shown. Autofill in the
 * password fields works on every version regardless.
 */
object PasswordManager {
    private const val TAG = "OTGMaster"

    val isAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /**
     * Shows the password manager's chooser and returns the password picked, or null if the
     * user backed out or there is none saved. [context] must be an Activity: the chooser is UI.
     */
    suspend fun pick(context: Context): String? = try {
        val response = CredentialManager.create(context)
            .getCredential(context, GetCredentialRequest(listOf(GetPasswordOption())))
        (response.credential as? PasswordCredential)?.password
    } catch (_: GetCredentialCancellationException) {
        null
    } catch (_: NoCredentialException) {
        null
    } catch (e: Exception) {
        Log.w(TAG, "password manager: get failed", e)
        null
    }

    /**
     * Offers [password] to the password manager under [id], which the user sees in it and in
     * the chooser, so it names the drive. Returns true once the manager has taken it, false if
     * the user declined or it failed; nothing here is fatal to the volume it belongs to.
     */
    suspend fun save(context: Context, id: String, password: String): Boolean = try {
        CredentialManager.create(context).createCredential(context, CreatePasswordRequest(id, password))
        true
    } catch (_: CreateCredentialCancellationException) {
        false
    } catch (e: Exception) {
        Log.w(TAG, "password manager: save failed", e)
        false
    }
}

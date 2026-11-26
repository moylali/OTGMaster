package app.fayaz.containermaster

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The UI's view of the engine ([EngineProvider]), from the ":ui" process. Every call is a
 * synchronous IPC, so call it off the main thread. Nothing here holds an engine object: the UI
 * gets copies (ids, names, formats, log lines) and sends commands.
 */
class EngineClient(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val uri = Uri.parse("content://${EngineProvider.AUTHORITY}")

    data class OpenContainer(val id: String, val name: String, val format: String, val fileSystem: String, val readOnly: Boolean = true)
    data class State(val open: List<OpenContainer>, val logs: List<String>, val idleMinutes: Int)
    sealed class OpenResult {
        object Ok : OpenResult(); object WrongPassword : OpenResult(); object AlreadyOpen : OpenResult()
        data class Failed(val message: String) : OpenResult()
    }

    private fun call(method: String, arg: String? = null, extras: Bundle? = null): Bundle =
        resolver.call(uri, method, arg, extras) ?: Bundle()

    fun state(): State {
        val b = call(EngineProvider.M_STATE)
        @Suppress("DEPRECATION")
        val open = b.getParcelableArrayList<Bundle>(EngineProvider.K_OPEN).orEmpty().map {
            OpenContainer(it.getString("id")!!, it.getString("name")!!, it.getString("format")!!, it.getString("fs")!!, it.getBoolean("ro", true))
        }
        return State(open, b.getStringArrayList(EngineProvider.K_LOGS).orEmpty(), b.getInt(EngineProvider.K_IDLE, 15))
    }

    /** Emits once at once, then whenever the engine's state changes. */
    fun states(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        resolver.registerContentObserver(EngineProvider.STATE_URI, false, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    /** A picked file's format and, for one with a password, the ID its saved password is under. */
    data class Detected(val format: ContainerFormat, val passwordId: String?)

    fun detect(uri: Uri, name: String): Detected {
        val b = call(EngineProvider.M_DETECT, uri.toString(), Bundle().apply { putString(EngineProvider.K_NAME, name) })
        return Detected(ContainerFormat.valueOf(b.getString(EngineProvider.K_FORMAT) ?: "VERACRYPT"), b.getString(EngineProvider.K_ID))
    }

    /** Opens [uri] as [format], for writing if [writable] (VeraCrypt and LUKS); [password] is cleared here once sent, and by the engine after use. */
    fun open(format: ContainerFormat, uri: Uri, name: String, password: CharArray = CharArray(0), pim: Int? = null,
             writable: Boolean = false): OpenResult {
        val extras = Bundle().apply {
            putString(EngineProvider.K_FORMAT, format.name); putString(EngineProvider.K_NAME, name)
            putBoolean(EngineProvider.K_WRITABLE, writable)
            putCharArray(EngineProvider.K_PASSWORD, password); pim?.let { putInt(EngineProvider.K_PIM, it) }
        }
        try {
            val b = call(EngineProvider.M_OPEN, uri.toString(), extras)
            return when (b.getString(EngineProvider.K_RESULT)) {
                EngineProvider.R_OK -> OpenResult.Ok
                EngineProvider.R_WRONG -> OpenResult.WrongPassword
                EngineProvider.R_ALREADY -> OpenResult.AlreadyOpen
                else -> OpenResult.Failed(b.getString(EngineProvider.K_MESSAGE) ?: "unknown error")
            }
        } finally {
            password.fill('\u0000'); extras.getCharArray(EngineProvider.K_PASSWORD)?.fill('\u0000')
        }
    }

    fun lock(id: String) { call(EngineProvider.M_LOCK, id) }
    fun lockAll() { call(EngineProvider.M_LOCK_ALL) }
    fun setIdle(minutes: Int) { call(EngineProvider.M_SET_IDLE, extras = Bundle().apply { putInt(EngineProvider.K_IDLE, minutes) }) }
    fun clearLogs() { call(EngineProvider.M_CLEAR_LOGS) }
}

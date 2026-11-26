package app.fayaz.containermaster

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The engine's door for the UI, which runs in its own process (":ui", architecture proposal,
 * Milestone 2b): a crash or ANR in the screen must not end the containers other apps are
 * reading. Not exported; only this app's processes call it.
 *
 * ContentProvider.call is synchronous and needs no binding, so it also serves Report issue,
 * which Android can restore without the main screen. Commands run on the caller's binder thread
 * in this process (the UI calls them off its main thread); state is a snapshot, and every change
 * to the open containers or the log is announced on [STATE_URI] for the UI to read again.
 */
class EngineProvider : ContentProvider() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        scope.launch { ContainerSessions.open.drop(1).collect { ctx.contentResolver.notifyChange(STATE_URI, null) } }
        scope.launch { CmLog.lines.drop(1).collect { ctx.contentResolver.notifyChange(STATE_URI, null) } }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context!!.applicationContext
        val out = Bundle()
        when (method) {
            M_STATE -> {
                out.putParcelableArrayList(K_OPEN, ArrayList(ContainerSessions.open.value.map { s ->
                    Bundle().apply {
                        putString("id", s.id); putString("name", s.name)
                        putString("format", s.format); putString("fs", s.fileSystemName)
                    }
                }))
                out.putStringArrayList(K_LOGS, ArrayList(CmLog.lines.value))
                out.putInt(K_IDLE, ContainerSessions.idleMinutes)
            }
            M_DETECT -> {
                val uri = Uri.parse(arg)
                val d = runCatching { ContainerSessions.detect(ctx, uri, extras?.getString(K_NAME) ?: "container") }
                    .getOrElse { CmLog.log("Could not read the chosen file: ${it.message}"); ContainerSessions.Detected(ContainerFormat.VERACRYPT, null) }
                out.putString(K_FORMAT, d.format.name)
                d.passwordId?.let { out.putString(K_ID, it) }
            }
            M_OPEN -> {
                val b = extras ?: Bundle()
                val uri = Uri.parse(arg); val name = b.getString(K_NAME) ?: "container"
                val password = b.getCharArray(K_PASSWORD) ?: CharArray(0)
                val pim = if (b.containsKey(K_PIM)) b.getInt(K_PIM) else null
                SessionService.opening = true
                try {
                    when (ContainerFormat.valueOf(b.getString(K_FORMAT) ?: "VERACRYPT")) {
                        ContainerFormat.VERACRYPT -> ContainerSessions.openVeraCrypt(ctx, uri, name, password, pim)
                        ContainerFormat.LUKS -> ContainerSessions.openLuks(ctx, uri, name, password)
                        ContainerFormat.AESCRYPT -> ContainerSessions.openAesCrypt(ctx, uri, name, password)
                        ContainerFormat.ZIP -> ContainerSessions.openZip(ctx, uri, name)
                        ContainerFormat.ISO -> ContainerSessions.openIso(ctx, uri, name)
                    }
                    out.putString(K_RESULT, R_OK)
                } catch (e: IllegalArgumentException) {
                    out.putString(K_RESULT, R_WRONG)
                } catch (e: ContainerSessions.AlreadyOpenException) {
                    out.putString(K_RESULT, R_ALREADY)
                } catch (e: Exception) {
                    out.putString(K_RESULT, R_FAILED); out.putString(K_MESSAGE, e.message ?: e.javaClass.simpleName)
                } finally {
                    password.fill('\u0000')
                    SessionService.opening = false
                    // Lets the service stop if this open failed and nothing else is open.
                    runCatching { SessionService.start(ctx) }
                }
            }
            M_LOCK -> ContainerSessions.lock(ctx, arg ?: "")
            M_LOCK_ALL -> ContainerSessions.lockAll(ctx)
            M_SET_IDLE -> {
                val m = extras?.getInt(K_IDLE) ?: 15
                ContainerSessions.idleMinutes = m
                ContainerMasterApp.prefs(ctx).edit().putInt(ContainerMasterApp.IDLE_MINUTES, m).apply()
                CmLog.log(if (m == 0) "Idle lock: never" else "Idle lock: after $m min")
            }
            M_CLEAR_LOGS -> CmLog.clear()
            else -> throw IllegalArgumentException("unknown method $method")
        }
        return out
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "app.fayaz.containermaster.engine"
        val STATE_URI: Uri = Uri.parse("content://$AUTHORITY/state")
        const val M_STATE = "state"; const val M_DETECT = "detect"; const val M_OPEN = "open"
        const val M_LOCK = "lock"; const val M_LOCK_ALL = "lockAll"; const val M_SET_IDLE = "setIdle"; const val M_CLEAR_LOGS = "clearLogs"
        const val K_OPEN = "open"; const val K_LOGS = "logs"; const val K_IDLE = "idle"; const val K_FORMAT = "format"
        const val K_ID = "id"; const val K_NAME = "name"; const val K_PASSWORD = "password"; const val K_PIM = "pim"
        const val K_RESULT = "result"; const val K_MESSAGE = "message"
        const val R_OK = "ok"; const val R_WRONG = "wrong"; const val R_ALREADY = "already"; const val R_FAILED = "failed"
    }
}

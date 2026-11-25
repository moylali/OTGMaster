package app.fayaz.containermaster

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Container Master's log: what the screen shows under Logs and what Report issue sends. Engine
 * lines arrive through EngineLog (ContainerMasterApp installs the sink). A line naming a file
 * wraps the name with PathMask.mark, so the report masks it. The newest [MAX] lines are kept.
 */
object CmLog {
    const val MAX = 200
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    @Synchronized
    fun log(line: String) {
        val stamped = "${fmt.format(Date())} $line"
        android.util.Log.i("ContainerMaster", line)
        _lines.value = (_lines.value + stamped).takeLast(MAX)
    }

    @Synchronized
    fun clear() { _lines.value = emptyList() }
}

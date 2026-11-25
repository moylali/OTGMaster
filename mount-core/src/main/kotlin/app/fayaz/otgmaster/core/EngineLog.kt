package app.fayaz.otgmaster.core

/**
 * Where engine code (filesystem drivers, unlockers, the filesystem detector) sends the lines
 * a "Report issue" needs. Until Milestone 2 those called `OtgMasterState.log` directly, which
 * tied every driver to OTG Master's app object; each app now installs its own [Sink], and
 * OTG Master's keeps the behaviour it had: its history, its UI log and its per-kind limit on
 * repeated failures.
 *
 * Lines logged before a sink is installed are kept, up to [MAX_PENDING], and handed to the
 * sink when it arrives, so a failure in the first moments of a process still reaches the log.
 */
object EngineLog {

    interface Sink {
        fun log(line: String)

        /** A failure of [kind]; the sink decides how to word [e] and how many to keep. */
        fun logFailure(kind: String, what: String, e: Throwable)
    }

    const val MAX_PENDING = 200

    private val lock = Any()
    @Volatile private var sink: Sink? = null
    private val pending = ArrayDeque<(Sink) -> Unit>()

    /** Installs [s], first handing it whatever was logged before. */
    fun install(s: Sink) {
        synchronized(lock) {
            while (pending.isNotEmpty()) pending.removeFirst()(s)
            sink = s
        }
    }

    fun log(line: String) = deliver { it.log(line) }

    fun logFailure(kind: String, what: String, e: Throwable) = deliver { it.logFailure(kind, what, e) }

    private fun deliver(call: (Sink) -> Unit) {
        sink?.let { return call(it) }
        synchronized(lock) {
            sink?.let { return call(it) }
            if (pending.size == MAX_PENDING) pending.removeFirst()
            pending.addLast(call)
        }
    }

    /** Tests only: forget the sink and anything pending. */
    internal fun resetForTest() = synchronized(lock) {
        sink = null
        pending.clear()
    }
}

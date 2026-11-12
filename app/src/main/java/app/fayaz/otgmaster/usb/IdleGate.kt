package app.fayaz.otgmaster.usb

/**
 * Says a drive is idle only once it has stayed idle for [quietMs] without a break.
 *
 * A waiting Unmount or Eject polls whether the drive is in use, and the provider calls a drive
 * in use while a file is open or for 3 s after one closed. A file manager copying many files
 * can pause longer than that between two of them (a slow source, a large directory listing),
 * so the drive looked idle for a moment mid-batch and was unmounted under the copy (review of
 * #141, follow-up). Requiring a continuous quiet spell closes the short gaps; a copy that
 * stops for longer, such as one waiting on a conflict dialog, can still look finished.
 */
class IdleGate(private val quietMs: Long = DEFAULT_QUIET_MS) {
    private var idleSince: Long? = null

    /** True once [busy] has been false at every call for at least [quietMs] up to [now]. */
    fun ready(busy: Boolean, now: Long): Boolean {
        if (busy) { idleSince = null; return false }
        val since = idleSince ?: now.also { idleSince = it }
        return now - since >= quietMs
    }

    companion object { const val DEFAULT_QUIET_MS = 5_000L }
}

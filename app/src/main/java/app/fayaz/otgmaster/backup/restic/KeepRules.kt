package app.fayaz.otgmaster.backup.restic

/**
 * A job's keep rules applied after a backup, without deleting the last good copy.
 *
 * BackupRunner used to forget and prune whatever [KeepPolicy] dropped, straight after
 * every run. A snapshot that left out files it could not read still counted as the
 * newest, so with "keep last 1" one unreadable file made the run delete the previous
 * snapshot, the only one that still held that file. Restoring into the source had the
 * same shape: its safety snapshot became the newest and the keep rules then deleted
 * the very snapshot being restored. Review findings 4 and 5, 2026-11-09.
 *
 * Two snapshots are now never forgotten whatever the policy says: the newest complete
 * one (no [INCOMPLETE_TAG]; snapshots from before the tag existed count as complete),
 * and any named in `pinned`. Everything else follows the policy as before, so a source
 * with a file that is never readable still has its history pruned, keeping one extra
 * snapshot at most.
 */
object KeepRules {
    /** Tag ResticWriter adds to a snapshot that left out something it could not read. */
    const val INCOMPLETE_TAG = "otg-incomplete"

    class Result(val forgotten: List<ResticRepository.Snapshot>, val bytesFreed: Long)

    fun isComplete(s: ResticRepository.Snapshot): Boolean {
        val tags = s.json.optJSONArray("tags") ?: return true
        return (0 until tags.length()).none { tags.optString(it) == INCOMPLETE_TAG }
    }

    /** Which of [snapshots] to forget: what [policy] drops, less the newest complete one and [pinned]. */
    fun toForget(maintenance: ResticMaintenance, snapshots: List<ResticRepository.Snapshot>, policy: KeepPolicy,
                 pinned: Set<String> = emptySet()): List<ResticRepository.Snapshot> {
        val newestComplete = snapshots.filter(::isComplete)
            .maxByOrNull { java.time.OffsetDateTime.parse(it.time).toInstant() }?.id
        return maintenance.toForget(snapshots, policy).filter { it.id != newestComplete && it.id !in pinned }
    }

    /** Forgets what [toForget] names and prunes the data only they used. [onPrune] is told first. */
    fun apply(maintenance: ResticMaintenance, snapshots: List<ResticRepository.Snapshot>, policy: KeepPolicy,
              pinned: Set<String> = emptySet(), onPrune: () -> Unit = {}): Result {
        val drop = toForget(maintenance, snapshots, policy, pinned)
        if (drop.isEmpty()) return Result(emptyList(), 0)
        onPrune()
        maintenance.forget(drop.map { it.id })
        return Result(drop, maintenance.prune().bytesFreed)
    }
}

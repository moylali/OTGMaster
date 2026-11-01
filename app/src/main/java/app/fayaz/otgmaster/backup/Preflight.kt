package app.fayaz.otgmaster.backup

import app.fayaz.otgmaster.backup.restic.CancelledException
import app.fayaz.otgmaster.backup.restic.ResticRepository
import app.fayaz.otgmaster.backup.restic.ResticWriter

/**
 * What a run is about to need, measured before anything is written.
 *
 * The source is walked once for its size. For a one-time copy, or the first
 * snapshot of a job, the whole of it must fit. For a later incremental run only
 * files that differ from the parent snapshot (by size or modification time)
 * will be read, so their sizes are what must fit — an upper bound, since
 * deduplication and compression can only make the real addition smaller.
 */
object Preflight {

    data class Scan(
        val files: Long,
        val dirs: Long,
        val totalBytes: Long,
        /** Bytes the run may add at most. */
        val neededBytes: Long,
        val changedFiles: Long,
    ) {
        /** Nothing was listed at all. Not a folder of empty folders: those still get backed up. */
        val isEmpty: Boolean get() = files == 0L && dirs == 0L
    }

    /**
     * Walks [source] once. With a restic [repo] and the same folder's tree id in the
     * parent snapshot ([parentTree]), files whose size and modification time match the
     * parent are not counted as needed: the run will not read them.
     */
    fun scan(
        source: FileTree,
        repo: ResticRepository? = null,
        parentTree: String? = null,
        excludes: Set<String> = emptySet(),
        isCancelled: () -> Boolean = { false },
        progress: (files: Long, bytes: Long) -> Unit = { _, _ -> },
    ): Scan {
        var files = 0L; var dirs = 0L; var total = 0L; var needed = 0L; var changed = 0L
        fun walk(path: String, prevTree: String?) {
            if (isCancelled()) throw CancelledException()
            val prev = prevTree?.let { id -> runCatching { repo?.tree(id) }.getOrNull() }?.associateBy { it.name }.orEmpty()
            for (e in source.list(path)) {
                val p = Paths.join(path, e.name)
                if (p in excludes) continue
                val old = prev[e.name]
                if (e.isDirectory) {
                    dirs++
                    walk(p, old?.takeIf { it.type == "dir" }?.subtree)
                } else {
                    files++
                    total += e.size
                    val same = old != null && old.type == "file" && old.size == e.size &&
                        ResticWriter.sameMillis(old.mtime, e.lastModified)
                    if (!same) { needed += e.size; changed++ }
                    if (files % 200 == 0L) progress(files, total)
                }
            }
        }
        if (source.stat("")?.isDirectory != true) throw java.io.IOException("source ${source.description} is not available")
        walk("", parentTree)
        return Scan(files, dirs, total, needed, changed)
    }

    enum class Verdict { OK, LOW_SPACE_AFTER, INSUFFICIENT, UNKNOWN }

    data class SpaceCheck(
        val verdict: Verdict,
        val neededBytes: Long,
        val freeBytes: Long?,
        val capacityBytes: Long?,
        /** Free space left after the run, as a fraction of capacity; null when unknown. */
        val freeFractionAfter: Double?,
    )

    /** Below this fraction of capacity left free after a run, the user is warned first. */
    const val LOW_SPACE_FRACTION = 0.10

    fun checkSpace(neededBytes: Long, freeBytes: Long?, capacityBytes: Long?): SpaceCheck {
        if (freeBytes == null) return SpaceCheck(Verdict.UNKNOWN, neededBytes, null, capacityBytes, null)
        if (freeBytes < neededBytes) return SpaceCheck(Verdict.INSUFFICIENT, neededBytes, freeBytes, capacityBytes, null)
        val after = freeBytes - neededBytes
        val fraction = capacityBytes?.takeIf { it > 0 }?.let { after.toDouble() / it }
        val verdict = if (fraction != null && fraction < LOW_SPACE_FRACTION) Verdict.LOW_SPACE_AFTER else Verdict.OK
        return SpaceCheck(verdict, neededBytes, freeBytes, capacityBytes, fraction)
    }
}

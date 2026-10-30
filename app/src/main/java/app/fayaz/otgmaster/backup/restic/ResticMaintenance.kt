package app.fayaz.otgmaster.backup.restic

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.IsoFields

/** restic's keep rules: a snapshot stays if any rule keeps it. All zero keeps everything. */
data class KeepPolicy(val last: Int = 0, val daily: Int = 0, val weekly: Int = 0, val monthly: Int = 0) {
    val keepsEverything: Boolean get() = last <= 0 && daily <= 0 && weekly <= 0 && monthly <= 0
}

/**
 * `restic forget` and `restic prune` for the app's own jobs.
 *
 * Prune here never repacks: a pack is deleted only once nothing in it is used, and packs
 * still partly in use are kept whole. Repacking is what makes restic's prune risky to
 * interrupt; this one is safe at every step, in this order: an exclusive lock (refused if
 * anyone else holds one), a new index that supersedes the old ones, the old index files
 * removed, and last the packs nothing refers to.
 */
class ResticMaintenance(private val repo: ResticRepository, private val hostname: String, private val username: String) {

    private val backend = repo.backend
    private val random = SecureRandom()

    /** Which of [snapshots] [policy] removes, evaluated newest first as restic does. */
    fun toForget(snapshots: List<ResticRepository.Snapshot>, policy: KeepPolicy, zone: ZoneId = ZoneId.systemDefault()):
        List<ResticRepository.Snapshot> {
        if (policy.keepsEverything) return emptyList()
        val sorted = snapshots.sortedByDescending { OffsetDateTime.parse(it.time).toInstant() }
        val keep = HashSet<String>()
        sorted.take(policy.last.coerceAtLeast(0)).forEach { keep += it.id }
        fun bucketed(n: Int, key: (java.time.ZonedDateTime) -> String) {
            if (n <= 0) return
            val seen = LinkedHashSet<String>()
            for (s in sorted) {
                val k = key(OffsetDateTime.parse(s.time).atZoneSameInstant(zone))
                if (k in seen) continue
                if (seen.size >= n) break
                seen += k
                keep += s.id
            }
        }
        bucketed(policy.daily) { it.toLocalDate().toString() }
        bucketed(policy.weekly) { "${it.get(IsoFields.WEEK_BASED_YEAR)}-${it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}" }
        bucketed(policy.monthly) { "${it.year}-${it.monthValue}" }
        return sorted.filter { it.id !in keep }
    }

    fun forget(ids: Collection<String>) { for (id in ids) backend.delete("snapshots/$id") }

    class PruneResult(val packsDeleted: Int, val bytesFreed: Long, val packsKept: Int)

    fun prune(): PruneResult {
        val others = backend.list("locks")
        if (others.isNotEmpty()) throw IOException("the repository is locked by another backup or restic (${others.size} lock(s)); prune skipped")
        val lockJson = JSONObject().put("time", Instant.now().toString()).put("exclusive", true)
            .put("hostname", hostname).put("username", username).put("pid", 0)
        val sealedLock = repo.key.encrypt(lockJson.toString().toByteArray(), random)
        val lockName = ResticRepository.sha256Hex(sealedLock)
        backend.write("locks/$lockName", sealedLock)
        try {
            // Someone may have locked at the same moment: theirs wins, as restic resolves it.
            if (backend.list("locks").any { it != lockName }) throw IOException("another process locked the repository; prune skipped")

            // Every blob any snapshot reaches.
            val used = HashSet<String>()
            fun mark(treeId: String) {
                if (!used.add(treeId)) return
                for (n in repo.tree(treeId)) {
                    n.subtree?.let(::mark)
                    used.addAll(n.content)
                }
            }
            for (s in repo.snapshots()) mark(s.tree)

            // Packs by what they hold, from every index file.
            val oldIndex = backend.list("index")
            val packs = LinkedHashMap<String, JSONObject>()
            for (name in oldIndex) {
                val json = JSONObject(String(repo.readUnpacked("index/$name"), Charsets.UTF_8))
                val arr = json.optJSONArray("packs") ?: continue
                for (i in 0 until arr.length()) arr.getJSONObject(i).let { packs[it.getString("id")] = it }
            }
            val keep = JSONArray()
            val drop = mutableListOf<String>()
            for ((id, pack) in packs) {
                val blobs = pack.getJSONArray("blobs")
                val inUse = (0 until blobs.length()).any { blobs.getJSONObject(it).getString("id") in used }
                if (inUse) keep.put(pack) else drop += id
            }

            // New index first, superseding the old: until it exists, nothing is removed.
            val index = JSONObject().put("supersedes", JSONArray(oldIndex)).put("packs", keep)
            val sealed = repo.key.encrypt(index.toString().toByteArray(), random)
            val newIndex = ResticRepository.sha256Hex(sealed)
            backend.write("index/$newIndex", sealed)
            for (name in oldIndex) if (name != newIndex) backend.delete("index/$name")

            var freed = 0L
            for (id in drop) {
                val path = "data/${id.substring(0, 2)}/$id"
                freed += backend.size(path)
                backend.delete(path)
            }
            return PruneResult(drop.size, freed, keep.length())
        } finally {
            backend.delete("locks/$lockName")
        }
    }
}

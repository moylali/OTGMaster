package app.fayaz.otgmaster.backup

import app.fayaz.otgmaster.backup.restic.CancelledException
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

/** Progress and cancellation hooks shared by the copy engine, restore and checks. */
interface Progress {
    fun update(phase: String, files: Long, bytes: Long, current: String) {}
    fun isCancelled(): Boolean = false

    object None : Progress
}

/**
 * One-time backups: a plain copy of the source into a dated folder.
 *
 * Unlike a restic repository, the result is ordinary files any file manager or
 * computer opens without an app. A manifest beside them records where the copy came
 * from and the SHA-256 of every file, so it can be verified later ([verify]).
 */
object CopyEngine {
    const val MANIFEST = "otgmaster-copy.json"

    /**
     * Marks a manifest as ours, so Verify can tell it from a user's file of the same name.
     * Manifests from before it existed have no marker and are found by [MANIFEST] alone.
     */
    private const val MANIFEST_KIND = "otgmaster-copy-manifest"

    /** Names tried for the manifest, in order; the first one the source's top folder does not use is taken. */
    private fun manifestNames() = sequenceOf(MANIFEST) + (1..99).asSequence().map { "otgmaster-copy-$it.json" }
    const val MAX_ERRORS = 200

    data class ManifestFile(val path: String, val size: Long, val mtime: Long, val sha256: String)

    data class Manifest(
        val time: Long,
        val jobId: String?,
        val jobName: String?,
        val hostname: String,
        val source: Map<String, String>,
        val destination: Map<String, String>,
        val files: List<ManifestFile>,
        val dirs: Long,
        val totalBytes: Long,
        val errors: List<String>,
        val durationMs: Long,
        val appVersion: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("time", time); putOpt("jobId", jobId); putOpt("jobName", jobName); put("hostname", hostname)
            put("source", JSONObject(source)); put("destination", JSONObject(destination))
            put("files", JSONArray(files.map {
                JSONObject().put("path", it.path).put("size", it.size).put("mtime", it.mtime).put("sha256", it.sha256)
            }))
            put("dirs", dirs); put("totalBytes", totalBytes); put("errors", JSONArray(errors))
            put("durationMs", durationMs); put("appVersion", appVersion)
            put("kind", MANIFEST_KIND)
        }

        companion object {
            fun fromJson(o: JSONObject): Manifest {
                val f = o.getJSONArray("files")
                return Manifest(
                    o.getLong("time"), o.optStringOrNull("jobId"), o.optStringOrNull("jobName"), o.optString("hostname"),
                    o.optJSONObject("source").toStringMap(), o.optJSONObject("destination").toStringMap(),
                    (0 until f.length()).map { f.getJSONObject(it).let { x ->
                        ManifestFile(x.getString("path"), x.getLong("size"), x.optLong("mtime"), x.getString("sha256")) } },
                    o.optLong("dirs"), o.optLong("totalBytes"),
                    o.optJSONArray("errors")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                    o.optLong("durationMs"), o.optString("appVersion"))
            }
        }
    }

    data class Options(
        val jobId: String? = null,
        val jobName: String? = null,
        val hostname: String = "",
        val sourceInfo: Map<String, String> = emptyMap(),
        val destinationInfo: Map<String, String> = emptyMap(),
        /** Read every file back after writing it and compare hashes. */
        val verifyWrites: Boolean = true,
        val appVersion: String = "",
        val excludes: Set<String> = emptySet(),
    )

    /** Copies [source] into [target]/[targetDir] and writes the manifest last. */
    fun copy(source: FileTree, target: FileTree, targetDir: String, options: Options, progress: Progress = Progress.None,
             now: () -> Long = System::currentTimeMillis): Manifest {
        val started = now()
        if (source.stat("")?.isDirectory != true) throw IOException("source ${source.description} is not available")
        if (target.stat(targetDir) != null) throw IOException("$targetDir already exists in ${target.description}")
        target.mkdirs(targetDir)
        // The manifest goes in the copy's top folder, so it takes a name nothing copied
        // there uses. It was always otgmaster-copy.json, written after the files: a source
        // file of that name was copied, verified, then overwritten, and a source folder of
        // that name deleted with what was in it. Case-insensitive, as FAT32 and exFAT are.
        val topNames = runCatching { source.list("") }.getOrDefault(emptyList()).map { it.name.lowercase() }.toSet()
        val manifestName = manifestNames().firstOrNull { it.lowercase() !in topNames }
            ?: throw IOException("the source's top folder uses every name the copy's manifest could take")
        val files = ArrayList<ManifestFile>()
        val errors = ArrayList<String>()
        var dirs = 0L
        var bytes = 0L
        fun walk(path: String) {
            if (progress.isCancelled()) throw CancelledException()
            val entries = try { source.list(path).sortedBy { it.name } } catch (e: Exception) {
                errors += "${path.ifEmpty { "/" }}: ${e.message ?: e.javaClass.simpleName}"; return
            }
            for (e in entries) {
                val p = Paths.join(path, e.name)
                if (p in options.excludes) continue
                val dest = Paths.join(targetDir, p)
                if (e.isDirectory) {
                    dirs++
                    target.mkdirs(dest)
                    walk(p)
                    continue
                }
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    source.openRead(p).use { input ->
                        target.openWrite(dest).use { out ->
                            val buf = ByteArray(1 shl 20)
                            while (true) {
                                if (progress.isCancelled()) throw CancelledException()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                size += n
                            }
                        }
                    }
                    val hash = digest.digest().hex()
                    if (options.verifyWrites && sha256Of(target, dest) != hash) {
                        throw IOException("verification failed: $dest reads back differently")
                    }
                    files += ManifestFile(p, size, e.lastModified, hash)
                    bytes += size
                    progress.update("copy", files.size.toLong(), bytes, p)
                } catch (ce: CancelledException) {
                    throw ce
                } catch (ex: Exception) {
                    if (errors.size < MAX_ERRORS) errors += "$p: ${ex.message ?: ex.javaClass.simpleName}"
                }
            }
        }
        walk("")
        val manifest = Manifest(
            time = started, jobId = options.jobId, jobName = options.jobName, hostname = options.hostname,
            source = options.sourceInfo, destination = options.destinationInfo,
            files = files, dirs = dirs, totalBytes = bytes, errors = errors,
            durationMs = now() - started, appVersion = options.appVersion,
        )
        target.openWrite(Paths.join(targetDir, manifestName)).use { it.write(manifest.toJson().toString(1).toByteArray()) }
        return manifest
    }

    const val DIFFERS = "content differs from the manifest"
    const val MISSING = "file is missing"

    /** Whether a problem from [verify] means the copy is damaged, as opposed to a read that failed. */
    fun isDamage(problem: String) = problem.endsWith(DIFFERS) || problem.endsWith(MISSING)

    /**
     * The manifest of the copy in [copyDir]: the file among [manifestNames] that carries
     * the marker, or, for a copy made before the marker, the one named [MANIFEST].
     */
    private fun readManifest(target: FileTree, copyDir: String): Manifest {
        var legacy: JSONObject? = null
        var unreadable: Exception? = null
        for (name in manifestNames()) {
            val path = Paths.join(copyDir, name)
            if (target.stat(path)?.isDirectory != false) continue
            val o = try {
                JSONObject(String(target.openRead(path).use { it.readBytes() }, Charsets.UTF_8))
            } catch (e: Exception) { if (name == MANIFEST) unreadable = e; continue }
            if (o.optString("kind") == MANIFEST_KIND) return Manifest.fromJson(o)
            if (name == MANIFEST) legacy = o
        }
        legacy?.let { o ->
            return try { Manifest.fromJson(o) } catch (e: org.json.JSONException) {
                throw app.fayaz.otgmaster.backup.restic.DamagedException("the manifest of $copyDir cannot be read", e)
            }
        }
        if (unreadable != null)
            throw app.fayaz.otgmaster.backup.restic.DamagedException("the manifest of $copyDir cannot be read", unreadable)
        throw app.fayaz.otgmaster.backup.restic.DamagedException("the copy $copyDir has no manifest",
            java.io.FileNotFoundException(Paths.join(copyDir, MANIFEST)))
    }

    /** Re-hashes every file of a copy against its manifest; returns the problems found. */
    fun verify(target: FileTree, copyDir: String, progress: Progress = Progress.None): List<String> {
        val manifest = readManifest(target, copyDir)
        val problems = ArrayList<String>()
        // What the copy itself could not read is not in it, so the copy is not complete,
        // however well the rest checks out. Verify used to pass such a copy.
        for (e in manifest.errors) problems += "not copied: $e"
        for ((i, f) in manifest.files.withIndex()) {
            if (progress.isCancelled()) throw CancelledException()
            progress.update("verify", i.toLong(), 0, f.path)
            val actual = runCatching { sha256Of(target, Paths.join(copyDir, f.path)) }
            val failure = actual.exceptionOrNull()
            if (failure is java.io.FileNotFoundException) problems += "${f.path}: $MISSING"
            else if (failure != null) problems += "${f.path}: ${failure.message}"
            else if (actual.getOrNull() != f.sha256) problems += "${f.path}: $DIFFERS"
        }
        return problems
    }

    private fun sha256Of(tree: FileTree, path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        tree.openRead(path).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}

package app.fayaz.otgmaster.backup.restic

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.SecureRandom
import java.time.Instant

/** A file or folder to back up: a phone folder, a mounted drive, or a directory in tests. */
interface BackupSource {
    val name: String
    val isDirectory: Boolean
    val size: Long
    val modifiedMillis: Long
    fun children(): List<BackupSource>
    fun open(): InputStream
}

class FileSource(private val f: java.io.File) : BackupSource {
    override val name: String get() = f.name
    override val isDirectory get() = f.isDirectory
    override val size get() = f.length()
    override val modifiedMillis get() = f.lastModified()
    override fun children() = f.listFiles()?.map(::FileSource).orEmpty()
    override fun open(): InputStream = f.inputStream()
}

/**
 * Writes restic snapshots: chunks file contents with the repository's own polynomial,
 * stores each new blob once in encrypted packs, then writes the index, then the snapshot.
 *
 * That order is what makes an interrupted backup harmless: until the snapshot file is
 * written nothing refers to the new packs, so a crash leaves only unreferenced data that
 * `restic prune` removes, never a snapshot pointing at data that is not there. Blobs are
 * written uncompressed, which format 2 allows and every restic version reads.
 */
class ResticWriter(
    private val repo: ResticRepository,
    private val hostname: String,
    private val username: String,
    private val programVersion: String,
    private val random: SecureRandom = SecureRandom(),
) {
    private val backend = repo.backend
    private val pol = ResticChunker.parsePolynomial(repo.config.getString("chunker_polynomial"))
    private val pending = HashSet<String>()
    private val pack = ByteArrayOutputStream()
    private val entries = mutableListOf<Entry>()
    private val indexPacks = JSONArray()
    private var lock: String? = null

    private class Entry(val type: ResticRepository.BlobType, val id: String, val offset: Int, val length: Int)

    /** Called every file with (files done, bytes read, the file's name); for the progress notification. */
    var onProgress: (Long, Long, String) -> Unit = { _, _, _ -> }

    /** Polled between files; a run asked to stop throws [CancelledException] and writes no snapshot. */
    var isCancelled: () -> Boolean = { false }

    /**
     * Files and folders that could not be read, as "path: reason". They are left out of
     * the snapshot, as restic leaves them out, and the run reports a warning naming them.
     */
    val errors = mutableListOf<String>()
    var dirsDone = 0L; private set
    var filesNew = 0L; private set

    /** Statistics for the run, for the progress notification and the job's record. */
    var filesDone = 0L; private set
    var bytesRead = 0L; private set
    var bytesAdded = 0L; private set

    fun lock() {
        val json = JSONObject().put("time", Instant.now().toString()).put("exclusive", false)
            .put("hostname", hostname).put("username", username).put("pid", 0)
        val sealed = repo.key.encrypt(json.toString().toByteArray(), random)
        val name = ResticRepository.sha256Hex(sealed)
        backend.write("locks/$name", sealed)
        lock = name
    }

    fun unlock() { lock?.let { backend.delete("locks/$it") }; lock = null }

    private fun saveBlob(type: ResticRepository.BlobType, plain: ByteArray): String {
        val id = ResticRepository.sha256Hex(plain)
        if (id in pending || repo.hasBlob(id)) return id
        val sealed = repo.key.encrypt(plain, random)
        entries += Entry(type, id, pack.size(), sealed.size)
        pack.write(sealed)
        pending += id
        bytesAdded += plain.size
        if (pack.size() >= PACK_SIZE) flushPack()
        return id
    }

    private fun flushPack() {
        if (entries.isEmpty()) return
        val header = ByteArrayOutputStream()
        for (e in entries) {
            header.write(if (e.type == ResticRepository.BlobType.TREE) 1 else 0)
            header.write(le32(e.length))
            header.write(unhex(e.id))
        }
        val sealedHeader = repo.key.encrypt(header.toByteArray(), random)
        pack.write(sealedHeader)
        pack.write(le32(sealedHeader.size))
        val bytes = pack.toByteArray()
        val packId = ResticRepository.sha256Hex(bytes)
        backend.write("data/${packId.substring(0, 2)}/$packId", bytes)
        val blobs = JSONArray()
        for (e in entries) blobs.put(JSONObject().put("id", e.id)
            .put("type", if (e.type == ResticRepository.BlobType.TREE) "tree" else "data")
            .put("offset", e.offset).put("length", e.length))
        indexPacks.put(JSONObject().put("id", packId).put("blobs", blobs))
        pack.reset(); entries.clear()
    }

    private fun times(millis: Long) = Instant.ofEpochMilli(millis).toString()

    /** Files skipped because the parent snapshot already holds them unchanged. */
    var filesUnchanged = 0L; private set

    private fun fileNode(s: BackupSource, previous: ResticRepository.Node?): JSONObject {
        if (isCancelled()) throw CancelledException()
        onProgress(filesDone, bytesRead, s.name)
        // Unchanged since the parent snapshot (same size and modification time, as restic
        // decides by default): its chunks are already in the repository, so the file is
        // not read at all. This is what makes a daily backup of a phone folder fast.
        val t = times(s.modifiedMillis)
        if (previous != null && previous.type == "file" && previous.size == s.size && sameMillis(previous.mtime, s.modifiedMillis) &&
            previous.content.all { repo.hasBlob(it) }) {
            filesDone++; filesUnchanged++
            return JSONObject().put("name", s.name).put("type", "file").put("mode", FILE_MODE)
                .put("mtime", t).put("atime", t).put("ctime", t)
                .put("uid", 0).put("gid", 0).put("user", "").put("group", "").put("inode", 0).put("device_id", 0)
                .put("size", s.size).put("links", 1).put("content", JSONArray(previous.content))
        }
        val content = JSONArray()
        var size = 0L
        s.open().use { input ->
            val c = ResticChunker(pol, input.buffered(1 shl 20))
            while (true) {
                val chunk = c.next() ?: break
                size += chunk.size
                bytesRead += chunk.size
                content.put(saveBlob(ResticRepository.BlobType.DATA, chunk))
            }
        }
        filesDone++
        return JSONObject().put("name", s.name).put("type", "file").put("mode", FILE_MODE)
            .put("mtime", t).put("atime", t).put("ctime", t)
            .put("uid", 0).put("gid", 0).put("user", "").put("group", "").put("inode", 0).put("device_id", 0)
            .put("size", size).put("links", 1).put("content", content)
            .also { if (previous == null) filesNew++ }
    }

    /** The tree blob for [dir]'s contents, children first; returns its id. [parentTree] is the same folder in the parent snapshot. */
    fun saveTree(dir: BackupSource, parentTree: String? = null, at: String = ""): String {
        val previous = parentTree?.let { id -> runCatching { repo.tree(id) }.getOrNull() }?.associateBy { it.name }.orEmpty()
        dirsDone++
        val children = try {
            dir.children()
        } catch (e: java.io.IOException) {
            errors += "${at.ifEmpty { "/" }}: ${e.message ?: e.javaClass.simpleName}"
            emptyList()
        }
        val nodes = children.sortedWith { a, b -> compareBytes(a.name, b.name) }.mapNotNull { c ->
            val path = "$at/${c.name}"
            if (c.isDirectory) {
                val t = times(c.modifiedMillis)
                JSONObject().put("name", c.name).put("type", "dir").put("mode", DIR_MODE)
                    .put("mtime", t).put("atime", t).put("ctime", t)
                    .put("uid", 0).put("gid", 0).put("user", "").put("group", "").put("inode", 0).put("device_id", 0)
                    .put("subtree", saveTree(c, previous[c.name]?.takeIf { it.type == "dir" }?.subtree, path))
            } else try {
                fileNode(c, previous[c.name])
            } catch (e: java.io.IOException) {
                // One unreadable file must not lose the whole backup. Chunks of it already
                // saved stay in the pack and cost nothing while no snapshot uses them.
                errors += "$path: ${e.message ?: e.javaClass.simpleName}"
                null
            }
        }
        val json = JSONObject().put("nodes", JSONArray(nodes))
        return saveBlob(ResticRepository.BlobType.TREE, (json.toString() + "\n").toByteArray(Charsets.UTF_8))
    }

    /**
     * Backs up [source] as a snapshot whose path is [path], and returns the snapshot's id.
     * Takes and releases a lock around the run, as restic does.
     */
    fun backup(source: BackupSource, path: String, tags: List<String> = emptyList(), parent: ResticRepository.Snapshot? = null): String {
        lock()
        try {
            // The parent's tree for this path: the same nesting the snapshot uses.
            var parentTree: String? = parent?.tree
            for (part in path.trim('/').split('/').filter { it.isNotEmpty() }) {
                parentTree = parentTree?.let { id -> runCatching { repo.tree(id) }.getOrNull()?.firstOrNull { it.name == part }?.subtree }
            }
            val started = Instant.now()
            val tree = saveTree(source, parentTree)
            // The snapshot's tree is the source's contents under its path, as restic lays a
            // backed-up directory out: one tree per path component, from the root down.
            var top = tree
            val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
            for (i in parts.indices.reversed()) {
                val t = times(source.modifiedMillis)
                val node = JSONObject().put("name", parts[i]).put("type", "dir").put("mode", DIR_MODE)
                    .put("mtime", t).put("atime", t).put("ctime", t)
                    .put("uid", 0).put("gid", 0).put("user", "").put("group", "").put("inode", 0).put("device_id", 0)
                    .put("subtree", top)
                top = saveBlob(ResticRepository.BlobType.TREE,
                    (JSONObject().put("nodes", JSONArray().put(node)).toString() + "\n").toByteArray(Charsets.UTF_8))
            }
            // Packs, then the index naming them, then the snapshot: see the class comment.
            flushPack()
            if (indexPacks.length() > 0) {
                val sealed = repo.key.encrypt(JSONObject().put("packs", indexPacks).toString().toByteArray(), random)
                backend.write("index/${ResticRepository.sha256Hex(sealed)}", sealed)
            }
            val snap = JSONObject().put("time", Instant.now().toString()).put("tree", top)
                .put("paths", JSONArray().put("/" + parts.joinToString("/")))
                .put("hostname", hostname).put("username", username).put("uid", 0).put("gid", 0)
                .put("program_version", programVersion)
            if (tags.isNotEmpty()) snap.put("tags", JSONArray(tags))
            if (parent != null) snap.put("parent", parent.id)
            // restic 0.17's snapshot summary, so `restic snapshots` and the app's timeline
            // show the same counts.
            snap.put("summary", JSONObject()
                .put("backup_start", started.toString()).put("backup_end", Instant.now().toString())
                .put("files_new", filesNew).put("files_changed", filesDone - filesNew - filesUnchanged)
                .put("files_unmodified", filesUnchanged).put("dirs_new", 0).put("dirs_changed", 0)
                .put("dirs_unmodified", 0).put("data_blobs", 0).put("tree_blobs", 0)
                .put("data_added", bytesAdded).put("data_added_packed", bytesAdded)
                .put("total_files_processed", filesDone).put("total_bytes_processed", bytesRead))
            val sealed = repo.key.encrypt(snap.toString().toByteArray(), random)
            val id = ResticRepository.sha256Hex(sealed)
            backend.write("snapshots/$id", sealed)
            return id
        } finally {
            unlock()
        }
    }

    companion object {
        /** restic writes nanoseconds and a local offset, this code milliseconds in UTC: compare as instants. */
        fun sameMillis(rfc3339: String?, millis: Long): Boolean = rfc3339 != null &&
            runCatching { java.time.OffsetDateTime.parse(rfc3339).toInstant().toEpochMilli() == millis }.getOrDefault(false)

        /** restic's default pack size. */
        const val PACK_SIZE = 16 * 1024 * 1024
        const val FILE_MODE = 420L            // 0644
        const val DIR_MODE = 2147484141L      // os.ModeDir | 0755

        fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
        fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

        /** Go sorts names by their UTF-8 bytes; Kotlin's String order differs above U+FFFF. */
        fun compareBytes(a: String, b: String): Int {
            val x = a.toByteArray(Charsets.UTF_8); val y = b.toByteArray(Charsets.UTF_8)
            for (i in 0 until minOf(x.size, y.size)) {
                val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
                if (d != 0) return d
            }
            return x.size - y.size
        }

        /**
         * Creates an empty repository (format 2) with [password]: the directory layout,
         * a config with a fresh id and chunker polynomial, and one key file.
         */
        fun init(backend: ResticBackend, password: ByteArray, hostname: String, username: String,
                 random: SecureRandom = SecureRandom()): ResticRepository {
            require(!backend.exists("config")) { "a repository already exists here" }
            for (d in listOf("keys", "snapshots", "index", "locks", "data")) backend.mkdirs(d)
            for (i in 0 until 256) backend.mkdirs("data/%02x".format(i))
            val master = ResticKey.random(random)
            val id = ByteArray(32).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val config = JSONObject().put("version", 2).put("id", id)
                .put("chunker_polynomial", java.lang.Long.toHexString(Polynomials.randomIrreducible(random)))
            backend.write("config", master.encrypt(config.toString().toByteArray(), random))
            val key = ResticKeyFile.create(master, password, username, hostname, Instant.now().toString(), random = random)
            val bytes = key.toString().toByteArray()
            backend.write("keys/${ResticRepository.sha256Hex(bytes)}", bytes)
            return ResticRepository.open(backend, password) ?: error("the new repository does not open with its password")
        }
    }
}

/** Polynomials over GF(2) in a Long, for choosing a chunker polynomial as restic does. */
object Polynomials {
    private fun deg(p: Long) = ResticChunker.deg(p)
    private fun mod(x: Long, d: Long) = ResticChunker.mod(x, d)

    fun mulMod(a0: Long, b0: Long, m: Long): Long {
        var a = mod(a0, m); var b = b0; var r = 0L
        val dm = deg(m)
        while (b != 0L) {
            if (b and 1L != 0L) r = r xor a
            b = b ushr 1
            a = a shl 1
            if (deg(a) >= dm) a = a xor m
        }
        return r
    }

    fun gcd(a0: Long, b0: Long): Long {
        var a = a0; var b = b0
        while (b != 0L) { val t = mod(a, b); a = b; b = t }
        return a
    }

    /** Ben-Or: irreducible iff gcd(f, x^(2^i) - x mod f) = 1 for every i up to deg/2. */
    fun irreducible(f: Long): Boolean {
        val d = deg(f)
        var xp = 2L // x
        for (i in 1..d / 2) {
            xp = mulMod(xp, xp, f)                // x^(2^i) mod f
            if (gcd(f, xp xor 2L) != 1L) return false
        }
        return true
    }

    /** A random irreducible polynomial of degree 53, as restic's chunker.RandomPolynomial. */
    fun randomIrreducible(random: SecureRandom): Long {
        while (true) {
            var p = random.nextLong()
            p = p and ((1L shl 54) - 1)  // degree at most 53
            p = p or (1L shl 53) or 1L   // exactly 53, and not divisible by x
            if (irreducible(p)) return p
        }
    }
}

class CancelledException : RuntimeException("cancelled")

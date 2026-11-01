package app.fayaz.otgmaster.backup.restic

import io.airlift.compress.zstd.ZstdDecompressor
import io.airlift.compress.zstd.ZstdInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest

/** Where a repository's files live: a folder on a drive, or a local directory in tests. */
interface ResticBackend {
    /** Names of the files directly in [dir] ("keys", "snapshots", "index", "data/ab", ...). */
    fun list(dir: String): List<String>
    fun read(path: String): ByteArray
    fun readRange(path: String, offset: Long, length: Int): ByteArray
    fun write(path: String, data: ByteArray)
    fun exists(path: String): Boolean
    fun size(path: String): Long
    fun delete(path: String)
    fun mkdirs(dir: String)
}

class LocalBackend(private val root: java.io.File) : ResticBackend {
    override fun list(dir: String) = root.resolve(dir).list()?.toList().orEmpty()
    override fun read(path: String) = root.resolve(path).readBytes()
    override fun readRange(path: String, offset: Long, length: Int): ByteArray =
        java.io.RandomAccessFile(root.resolve(path), "r").use { f ->
            ByteArray(length).also { f.seek(offset); f.readFully(it) }
        }
    override fun write(path: String, data: ByteArray) {
        val f = root.resolve(path); f.parentFile?.mkdirs()
        val tmp = java.io.File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(data)
        check(tmp.renameTo(f)) { "could not move $tmp into place" }
    }
    override fun exists(path: String) = root.resolve(path).exists()
    override fun size(path: String) = root.resolve(path).length()
    override fun delete(path: String) { root.resolve(path).delete() }
    override fun mkdirs(dir: String) { root.resolve(dir).mkdirs() }
}

/**
 * Reads a restic repository (format version 1 or 2): what `restic snapshots`, `ls` and
 * `restore` need. Every blob is checked against its SHA-256 id after decryption, as
 * restic does, so damaged data is reported, never handed back as a file's contents.
 */
class ResticRepository private constructor(val backend: ResticBackend, val key: ResticKey, val config: JSONObject) {

    enum class BlobType { DATA, TREE }

    class BlobRef(val pack: String, val type: BlobType, val offset: Long, val length: Int, val uncompressedLength: Int?)

    class Snapshot(val id: String, val json: JSONObject) {
        val time: String get() = json.getString("time")
        val tree: String get() = json.getString("tree")
        val paths: List<String> get() = json.optJSONArray("paths").strings()
        val hostname: String get() = json.optString("hostname")
    }

    class Node(val json: JSONObject) {
        val name: String get() = json.getString("name")
        val type: String get() = json.getString("type")
        val size: Long get() = json.optLong("size")
        val content: List<String> get() = json.optJSONArray("content").strings()
        val subtree: String? get() = json.optString("subtree").ifEmpty { null }
        val linkTarget: String? get() = json.optString("linktarget").ifEmpty { null }
        val mtime: String? get() = json.optString("mtime").ifEmpty { null }
    }

    val version: Int get() = config.getInt("version")

    private val index = HashMap<String, BlobRef>()
    private var indexLoaded = false

    /** Decrypts an "unpacked" file (config, index, snapshot, lock) and undoes v2 compression. */
    fun readUnpacked(path: String): ByteArray = unpack(key.decrypt(backend.read(path)))

    fun loadIndex() {
        if (indexLoaded) return
        for (name in backend.list("index")) {
            val json = JSONObject(String(readUnpacked("index/$name"), Charsets.UTF_8))
            val packs = json.optJSONArray("packs") ?: continue
            for (i in 0 until packs.length()) {
                val pack = packs.getJSONObject(i)
                val packId = pack.getString("id")
                val blobs = pack.getJSONArray("blobs")
                for (j in 0 until blobs.length()) {
                    val b = blobs.getJSONObject(j)
                    index[b.getString("id")] = BlobRef(packId,
                        if (b.getString("type") == "tree") BlobType.TREE else BlobType.DATA,
                        b.getLong("offset"), b.getInt("length"),
                        if (b.has("uncompressed_length")) b.getInt("uncompressed_length") else null)
                }
            }
        }
        indexLoaded = true
    }

    fun hasBlob(id: String): Boolean { loadIndex(); return id in index }

    fun snapshots(): List<Snapshot> = backend.list("snapshots").map { id ->
        Snapshot(id, JSONObject(String(readUnpacked("snapshots/$id"), Charsets.UTF_8)))
    }.sortedBy { it.time }

    /** A blob's plaintext, decompressed, after checking it is the blob [id] names. */
    fun blob(id: String): ByteArray {
        loadIndex()
        val ref = index[id] ?: throw IOException("blob $id is not in the index")
        val sealed = backend.readRange("data/${ref.pack.substring(0, 2)}/${ref.pack}", ref.offset, ref.length)
        val plain = key.decrypt(sealed).let { p -> ref.uncompressedLength?.let { zstd(p, 0, p.size, it) } ?: p }
        if (sha256Hex(plain) != id) throw IOException("blob $id in pack ${ref.pack} does not hash to its id: damaged")
        return plain
    }

    /**
     * A blob's plaintext size, from the index alone: its uncompressed length, or the sealed
     * length less restic's 32 bytes of IV and MAC. Lets a reader seek inside a file without
     * reading the blobs before the offset.
     */
    fun blobSize(id: String): Int {
        loadIndex()
        val ref = index[id] ?: throw IOException("blob $id is not in the index")
        return ref.uncompressedLength ?: (ref.length - 32)
    }

    fun tree(id: String): List<Node> {
        val nodes = JSONObject(String(blob(id), Charsets.UTF_8)).optJSONArray("nodes") ?: JSONArray()
        return (0 until nodes.length()).map { Node(nodes.getJSONObject(it)) }
    }

    /** Streams a file node's contents to [out], one blob at a time. */
    fun readFile(node: Node, out: (ByteArray) -> Unit) {
        for (id in node.content) out(blob(id))
    }

    companion object {
        /** Opens the repository with [password]; null if no key file accepts it. */
        fun open(backend: ResticBackend, password: ByteArray): ResticRepository? {
            for (name in backend.list("keys")) {
                val master = ResticKeyFile.open(JSONObject(String(backend.read("keys/$name"), Charsets.UTF_8)), password)
                    ?: continue
                val config = JSONObject(String(master.decrypt(backend.read("config")), Charsets.UTF_8))
                require(config.getInt("version") in 1..2) { "restic repository version ${config.getInt("version")} is not supported" }
                return ResticRepository(backend, master, config)
            }
            return null
        }

        /** Repository format 2 marks a compressed unpacked file with a leading 2; JSON starts with { or [. */
        fun unpack(plain: ByteArray): ByteArray =
            if (plain.isNotEmpty() && plain[0].toInt() == 2) zstd(plain, 1, plain.size - 1, null) else plain

        fun zstd(src: ByteArray, off: Int, len: Int, size: Int?): ByteArray {
            val known = size ?: ZstdDecompressor.getDecompressedSize(src, off, len).takeIf { it >= 0 }?.toInt()
            if (known != null) {
                val out = ByteArray(known)
                val n = ZstdDecompressor().decompress(src, off, len, out, 0, known)
                if (n != known) throw IOException("zstd gave $n bytes, expected $known")
                return out
            }
            return ZstdInputStream(ByteArrayInputStream(src, off, len)).use { it.readBytes() }
        }

        fun sha256Hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
    }
}

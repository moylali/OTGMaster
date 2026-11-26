package app.fayaz.safprobe

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * An independent SAF client for the architecture milestones (Milestone 0 baseline,
 * upgrade acceptance). It is a separate package with its own UID, so what it can reach
 * is exactly what Android grants an ordinary app through the picker: no access to OTG
 * Master's internals, no MANAGE_DOCUMENTS.
 *
 * Driven over adb, one command per launch; each writes its result as JSON to
 * `files/result-<cmd>.json` in the app's external files directory and logs it under
 * the tag SAFPROBE:
 *
 *   am start -n app.fayaz.safprobe/.ProbeActivity --es cmd grant [--es initial <document URI>]
 *       Opens the folder picker; the chosen tree's read grant is persisted.
 *   am start -n app.fayaz.safprobe/.ProbeActivity --es cmd read --es path <a/b/c> [--es tree <tree URI>]
 *       Walks the tree by display name (no internal IDs assumed), reads the file through
 *       the grant and records its document ID, size, SHA-256 and every column returned.
 *   am start -n app.fayaz.safprobe/.ProbeActivity --es cmd grants
 *       Lists the persisted grants.
 *   am start -n app.fayaz.safprobe/.ProbeActivity --es cmd outside --es docid <id> [--es tree <tree URI>]
 *       Tries to open a document ID through the tree grant, to show the grant is
 *       confined to its tree (a SecurityException is the expected result for an ID
 *       outside it).
 *
 * `tree` defaults to the most recently persisted grant.
 */
class ProbeActivity : Activity() {

    private val tag = "SAFPROBE"
    private val requestTree = 1
    private val requestView = 2
    private var viewPackage = ""
    private var viewMime = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val cmd = intent.getStringExtra("cmd") ?: "grants"
        try {
            when (cmd) {
                "grant" -> {
                    wantWrite = intent.getBooleanExtra("write", false)
                    var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    if (wantWrite) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    val pick = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(flags)
                    intent.getStringExtra("initial")?.let {
                        pick.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(it))
                    }
                    startActivityForResult(pick, requestTree)
                    return
                }
                "read" -> finishWith(cmd, read(tree(), intent.getStringExtra("path") ?: error("--es path is required")))
                "grants" -> finishWith(cmd, grants())
                "hold" -> {
                    // Off the main thread: it sleeps while the test locks the container.
                    val tree = tree(); val rel = intent.getStringExtra("path") ?: error("--es path is required")
                    val wait = intent.getIntExtra("wait", 20)
                    Thread { finishWith(cmd, hold(tree, rel, wait)) }.start()
                    return
                }
                "write" -> finishWith(cmd, write(tree(), intent.getStringExtra("name") ?: "saf-probe-write.bin",
                    intent.getIntExtra("bytes", 3 * 1024 * 1024 + 4097), intent.getBooleanExtra("keep", false)))
                "outside" -> finishWith(cmd, outside(tree(), intent.getStringExtra("docid") ?: error("--es docid is required")))
                "view" -> {
                    // Open with, as a Files app does it: the user picks a file, and it is handed
                    // to --es pkg with ACTION_VIEW, --es mime and a read grant (Container Master's E2E).
                    viewPackage = intent.getStringExtra("pkg") ?: error("--es pkg is required")
                    viewMime = intent.getStringExtra("mime") ?: "application/octet-stream"
                    val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    startActivityForResult(pick, requestView)
                    return
                }
                else -> finishWith(cmd, JSONObject().put("error", "unknown cmd $cmd"))
            }
        } catch (e: Exception) {
            finishWith(cmd, JSONObject().put("error", "${e.javaClass.name}: ${e.message}"))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestView) {
            val out = JSONObject().put("resultCode", resultCode)
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, viewMime).setPackage(viewPackage)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                out.put("viewed", uri.toString()).put("mime", viewMime)
            }
            finishWith("view", out)
            return
        }
        val out = JSONObject().put("resultCode", resultCode)
        val uri = data?.data
        if (resultCode == RESULT_OK && uri != null) {
            var keep = Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (wantWrite) keep = keep or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, keep)
            out.put("tree", uri.toString())
                .put("authority", uri.authority)
                .put("treeDocumentId", DocumentsContract.getTreeDocumentId(uri))
        }
        finishWith("grant", out)
    }

    private var wantWrite = false

    private fun tree(): Uri {
        intent.getStringExtra("tree")?.let { return Uri.parse(it) }
        val last = contentResolver.persistedUriPermissions.maxByOrNull { it.persistedTime }
            ?: error("no persisted grant; run cmd grant first")
        return last.uri
    }

    private fun read(tree: Uri, path: String): JSONObject {
        var docId = DocumentsContract.getTreeDocumentId(tree)
        val walked = JSONArray().put(docId)
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            var next: String? = null
            contentResolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) if (c.getString(1) == part) { next = c.getString(0); break }
            }
            docId = next ?: error("'$part' not found under $docId")
            walked.put(docId)
        }
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        val columns = JSONObject()
        contentResolver.query(doc, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) for (i in 0 until c.columnCount) columns.put(c.getColumnName(i), c.getString(i) ?: JSONObject.NULL)
        }
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        contentResolver.openInputStream(doc)!!.use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n); size += n
            }
        }
        return JSONObject().put("tree", tree.toString()).put("path", path).put("documentId", docId)
            .put("walked", walked).put("columns", columns).put("bytes", size)
            .put("sha256", md.digest().joinToString("") { "%02x".format(it) })
    }

    private fun sha256(doc: Uri): Pair<Long, String> {
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        contentResolver.openInputStream(doc)!!.use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n); size += n
            }
        }
        return size to md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /**
     * Through the provider, in the granted tree's top folder: create [name], write [bytes] of
     * seeded pseudo-random data ("w"), read it back; overwrite it with a third of that ("wt",
     * which truncates), read it back; then delete it unless [keep]. Each step's hash is
     * compared with the bytes written, so a short or misplaced write shows as a mismatch.
     */
    private fun write(tree: Uri, name: String, bytes: Int, keep: Boolean): JSONObject {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(contentResolver, parent, "application/octet-stream", name)
            ?: error("createDocument returned null")
        val first = ByteArray(bytes).also { java.util.Random(184L).nextBytes(it) }
        contentResolver.openOutputStream(doc, "w")!!.use { out ->
            var off = 0
            while (off < first.size) { val n = minOf(100_000, first.size - off); out.write(first, off, n); off += n }
        }
        val (size1, hash1) = sha256(doc)
        val second = first.copyOf(bytes / 3)
        contentResolver.openOutputStream(doc, "wt")!!.use { it.write(second) }
        val (size2, hash2) = sha256(doc)
        val out = JSONObject().put("tree", tree.toString()).put("documentId", DocumentsContract.getDocumentId(doc))
            .put("written", JSONObject().put("bytes", first.size).put("sha256", hex(first)))
            .put("readBack", JSONObject().put("bytes", size1).put("sha256", hash1))
            .put("rewritten", JSONObject().put("bytes", second.size).put("sha256", hex(second)))
            .put("readBackAfterTruncate", JSONObject().put("bytes", size2).put("sha256", hash2))
            .put("match", size1 == first.size.toLong() && hash1 == hex(first) && size2 == second.size.toLong() && hash2 == hex(second))
        if (!keep) out.put("deleted", DocumentsContract.deleteDocument(contentResolver, doc))
        return out
    }

    /**
     * Opens [rel] under the grant and reads its first 64 KiB, keeps the descriptor for [wait]
     * seconds (writing result-hold-open.json first, so the test knows when to lock), then reads
     * 64 KiB at 1 MiB through the same descriptor and closes it. Reports both reads: after a
     * lock the second must fail, and must not return the new session's bytes.
     */
    private fun hold(tree: Uri, rel: String, wait: Int): JSONObject {
        var docId = DocumentsContract.getTreeDocumentId(tree)
        for (part in rel.split('/').filter { it.isNotEmpty() }) {
            var next: String? = null
            contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId), arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) if (c.getString(1) == part) { next = c.getString(0); break }
            }
            docId = next ?: error("'$part' not found under $docId")
        }
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        val out = JSONObject().put("documentId", docId)
        contentResolver.openFileDescriptor(doc, "r")!!.use { pfd ->
            val ch = java.io.FileInputStream(pfd.fileDescriptor).channel
            val first = java.nio.ByteBuffer.allocate(65536)
            out.put("firstRead", runCatching { ch.read(first, 0) }.fold({ "$it bytes" }, { "error: $it" }))
            getExternalFilesDir(null)?.let { File(it, "result-hold-open.json").writeText(out.toString()) }
            Thread.sleep(wait * 1000L)
            val second = java.nio.ByteBuffer.allocate(65536)
            out.put("lateRead", runCatching { ch.read(second, 1L shl 20) }.fold({ "$it bytes" }, { "error: $it" }))
        }
        return out.put("closed", true)
    }

    private fun grants(): JSONObject {
        val list = JSONArray()
        for (p in contentResolver.persistedUriPermissions) {
            list.put(JSONObject().put("uri", p.uri.toString()).put("read", p.isReadPermission)
                .put("write", p.isWritePermission).put("persistedTime", p.persistedTime))
        }
        return JSONObject().put("grants", list)
    }

    private fun outside(tree: Uri, docId: String): JSONObject {
        val out = JSONObject().put("tree", tree.toString()).put("documentId", docId)
        return try {
            contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, docId))!!.use {
                out.put("opened", true).put("firstByte", it.read())
            }
        } catch (e: Exception) {
            out.put("opened", false).put("refusal", "${e.javaClass.name}: ${e.message}")
        }
    }

    private fun finishWith(cmd: String, result: JSONObject) {
        result.put("cmd", cmd).put("time", System.currentTimeMillis())
        val text = result.toString(2)
        Log.i(tag, text)
        getExternalFilesDir(null)?.let { File(it, "result-$cmd.json").writeText(text) }
        finish()
    }
}

package app.fayaz.containermaster

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.fayaz.otgmaster.backup.restic.ResticBackend
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * A restic repository in a folder the user picked (ACTION_OPEN_DOCUMENT_TREE), read through the
 * folder's provider: the phone's own storage, an SD card, or another app's documents. Read-only:
 * Container Master mounts backups, it does not make them.
 *
 * Paths are resolved name by name from the tree's root and the document IDs cached, since a
 * repository's layout ("keys", "snapshots", "index", "data/ab/…") does not change while it is
 * mounted. A range read opens the pack and reads positionally, so a 4 MiB pack is not read whole
 * for one 1 KiB blob.
 */
class SafTreeBackend(context: Context, private val tree: Uri) : ResticBackend {
    private val resolver = context.applicationContext.contentResolver
    private val rootId = DocumentsContract.getTreeDocumentId(tree)
    private val ids = ConcurrentHashMap<String, String>().apply { put("", rootId) }
    private val listings = ConcurrentHashMap<String, Map<String, Child>>()

    private class Child(val id: String, val isDir: Boolean, val size: Long)

    private fun children(dir: String): Map<String, Child> = listings.getOrPut(dir) {
        val dirId = docId(dir) ?: return emptyMap()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
        val out = HashMap<String, Child>()
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out[c.getString(1)] = Child(c.getString(0), c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    if (c.isNull(3)) -1 else c.getLong(3))
            }
        } ?: throw IOException("The backup folder could not be listed")
        out
    }

    private fun docId(path: String): String? {
        ids[path]?.let { return it }
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val id = children(parent)[name]?.id ?: return null
        ids[path] = id
        return id
    }

    private fun uri(path: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, docId(path) ?: throw FileNotFoundException("$path is not in the backup folder"))

    override fun list(dir: String): List<String> = children(dir).filterValues { !it.isDir }.keys.toList()

    override fun read(path: String): ByteArray =
        resolver.openInputStream(uri(path))?.use { it.readBytes() } ?: throw IOException("$path could not be opened")

    override fun readRange(path: String, offset: Long, length: Int): ByteArray {
        val pfd = resolver.openFileDescriptor(uri(path), "r") ?: throw IOException("$path could not be opened")
        pfd.use {
            val ch = FileInputStream(it.fileDescriptor).channel
            val out = ByteBuffer.allocate(length)
            var p = offset
            while (out.hasRemaining()) {
                val n = ch.read(out, p)
                if (n < 0) throw IOException("$path ends before ${offset + length}")
                p += n
            }
            return out.array()
        }
    }

    override fun exists(path: String): Boolean = docId(path) != null

    /** Up to [length] bytes of [path] from [offset], fewer at its end; null if there is no such file. */
    fun readUpTo(path: String, offset: Long, length: Int): ByteArray? {
        val id = docId(path) ?: return null
        val pfd = resolver.openFileDescriptor(DocumentsContract.buildDocumentUriUsingTree(tree, id), "r") ?: return null
        pfd.use {
            val ch = FileInputStream(it.fileDescriptor).channel
            val out = ByteBuffer.allocate(length)
            var p = offset
            while (out.hasRemaining()) { val n = ch.read(out, p); if (n <= 0) break; p += n }
            return out.array().copyOf(out.position())
        }
    }

    override fun size(path: String): Long {
        val parent = path.substringBeforeLast('/', "")
        return children(parent)[path.substringAfterLast('/')]?.size ?: throw FileNotFoundException(path)
    }

    private fun refuse(): Nothing = throw IOException("Container Master mounts backups read-only")
    override fun write(path: String, data: ByteArray) = refuse()
    override fun delete(path: String) = refuse()
    override fun mkdirs(dir: String) = refuse()
}

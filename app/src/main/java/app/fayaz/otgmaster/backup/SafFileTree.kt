package app.fayaz.otgmaster.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A [FileTree] over a Storage Access Framework folder grant.
 *
 * The same code serves a folder in internal storage (ExternalStorageProvider)
 * and a folder on a drive mounted by OTG Master (its DocumentsProvider), so
 * every backup direction is just two of these.
 *
 * Path lookups go through a per-directory listing cache, filled by [list] and
 * kept current by this class's own writes. Without it every open would re-list
 * each directory from the root, which on a USB drive means USB round trips (and
 * decryption) per path component. The cache assumes nothing else changes the
 * tree during one operation; a new instance per run keeps that true enough.
 */
class SafFileTree(
    private val resolver: ContentResolver,
    val treeUri: Uri,
) : FileTree {

    private val authority: String = treeUri.authority ?: throw IllegalArgumentException("no authority in $treeUri")
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)

    private data class Child(val docId: String, val entry: FileTree.Entry)

    /** dir path -> (name -> child). */
    private val listings = HashMap<String, LinkedHashMap<String, Child>>()

    override val description: String get() = treeUri.toString()

    private fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun queryEntry(docId: String): FileTree.Entry? {
        resolver.query(docUri(docId), PROJECTION, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            return entryFrom(c)
        }
        return null
    }

    private fun entryFrom(c: android.database.Cursor): FileTree.Entry {
        val mime = c.getString(1)
        val isDir = mime == Document.MIME_TYPE_DIR
        return FileTree.Entry(
            name = c.getString(2) ?: "",
            isDirectory = isDir,
            size = if (isDir || c.isNull(3)) 0 else c.getLong(3),
            lastModified = if (c.isNull(4)) 0 else c.getLong(4),
        )
    }

    private fun loadListing(dir: String, dirDocId: String): LinkedHashMap<String, Child> {
        listings[dir]?.let { return it }
        val map = LinkedHashMap<String, Child>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
        val cursor = resolver.query(uri, PROJECTION, null, null, null)
            ?: throw IOException("cannot list $dir in $treeUri")
        cursor.use { c ->
            while (c.moveToNext()) {
                val e = entryFrom(c)
                map[e.name] = Child(c.getString(0), e)
            }
        }
        listings[dir] = map
        return map
    }

    /** Document id at [path], or null if absent. */
    private fun resolve(path: String): String? {
        if (path.isEmpty()) return rootDocId
        val parent = Paths.parent(path)
        val parentId = resolve(parent) ?: return null
        return loadListing(parent, parentId)[Paths.name(path)]?.docId
    }

    override fun stat(path: String): FileTree.Entry? {
        if (path.isEmpty()) return queryEntry(rootDocId)?.copy(name = "")
        val parentId = resolve(Paths.parent(path)) ?: return null
        val listing = try { loadListing(Paths.parent(path), parentId) } catch (_: Exception) { return null }
        return listing[Paths.name(path)]?.entry
    }

    override fun list(path: String): List<FileTree.Entry> {
        val id = resolve(path) ?: throw FileNotFoundException("$path not found in $treeUri")
        return loadListing(path, id).values.map { it.entry }
    }

    override fun openRead(path: String): InputStream {
        val id = resolve(path) ?: throw FileNotFoundException("$path not found in $treeUri")
        return resolver.openInputStream(docUri(id)) ?: throw IOException("cannot open $path")
    }

    override fun openWrite(path: String): OutputStream {
        val name = Paths.name(path)
        Paths.requireSafeName(name)
        val parent = Paths.parent(path)
        mkdirs(parent)
        val parentId = resolve(parent) ?: throw IOException("cannot create $parent")
        val listing = loadListing(parent, parentId)
        val existing = listing[name]
        if (existing != null && existing.entry.isDirectory) delete(path)
        val docId = listing[name]?.docId ?: create(parent, parentId, name, MIME_BINARY)
        val out = resolver.openOutputStream(docUri(docId), "wt") ?: throw IOException("cannot write $path")
        return object : java.io.FilterOutputStream(out) {
            private var written = 0L
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); written += len }
            override fun write(b: Int) { out.write(b); written++ }
            override fun close() {
                super.close()
                listings[parent]?.get(name)?.let {
                    listings[parent]!![name] = it.copy(entry = it.entry.copy(size = written, lastModified = System.currentTimeMillis()))
                }
            }
        }
    }

    private fun create(parent: String, parentId: String, name: String, mime: String): String {
        val uri = DocumentsContract.createDocument(resolver, docUri(parentId), mime, name)
            ?: throw IOException("could not create $name in ${parent.ifEmpty { "/" }}")
        val docId = DocumentsContract.getDocumentId(uri)
        // Providers may adjust a name (e.g. de-duplicate); trust what they report.
        val entry = queryEntry(docId) ?: FileTree.Entry(name, mime == Document.MIME_TYPE_DIR, 0, System.currentTimeMillis())
        if (entry.name != name) {
            DocumentsContract.deleteDocument(resolver, uri)
            throw IOException("provider created '${entry.name}' instead of '$name'")
        }
        loadListing(parent, parentId)[name] = Child(docId, entry)
        if (entry.isDirectory) listings[Paths.join(parent, name)] = LinkedHashMap()
        return docId
    }

    override fun mkdirs(path: String) {
        if (path.isEmpty()) return
        val parent = Paths.parent(path)
        mkdirs(parent)
        val parentId = resolve(parent) ?: throw IOException("cannot create $parent")
        val listing = loadListing(parent, parentId)
        val name = Paths.name(path)
        val existing = listing[name]
        if (existing != null) {
            if (existing.entry.isDirectory) return
            delete(path)
        }
        Paths.requireSafeName(name)
        create(parent, parentId, name, Document.MIME_TYPE_DIR)
    }

    /** Recursive, because not every provider deletes a non-empty directory. */
    override fun delete(path: String) {
        if (path.isEmpty()) throw IOException("refusing to delete the root of $treeUri")
        val id = resolve(path) ?: return
        val entry = listings[Paths.parent(path)]?.get(Paths.name(path))?.entry
        if (entry?.isDirectory == true) {
            for (child in list(path).map { it.name }) delete(Paths.join(path, child))
        }
        if (!DocumentsContract.deleteDocument(resolver, docUri(id))) throw IOException("could not delete $path")
        listings[Paths.parent(path)]?.remove(Paths.name(path))
        val prefix = "$path/"
        listings.keys.removeAll { it == path || it.startsWith(prefix) }
    }

    override fun rename(path: String, newName: String) {
        Paths.requireSafeName(newName)
        val parent = Paths.parent(path)
        val id = resolve(path) ?: throw FileNotFoundException("$path not found in $treeUri")
        if (loadListing(parent, resolve(parent)!!).containsKey(newName)) throw IOException("$newName already exists in ${parent.ifEmpty { "/" }}")
        val newUri = DocumentsContract.renameDocument(resolver, docUri(id), newName)
            ?: throw IOException("could not rename $path to $newName")
        val newId = DocumentsContract.getDocumentId(newUri)
        val listing = listings[parent]!!
        val old = listing.remove(Paths.name(path))!!
        val entry = queryEntry(newId) ?: old.entry.copy(name = newName)
        if (entry.name != newName) throw IOException("provider renamed $path to '${entry.name}' instead of '$newName'")
        listing[newName] = Child(newId, entry)
    }

    /** Forgets cached listings, e.g. after something else changed the tree. */
    fun invalidate() = listings.clear()

    companion object {
        const val MIME_BINARY = "application/octet-stream"
        private val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}

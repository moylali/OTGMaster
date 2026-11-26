package app.fayaz.otgmaster.provider

import app.fayaz.otgmaster.core.DocumentHandle
import app.fayaz.otgmaster.core.EngineLog
import app.fayaz.otgmaster.core.MountRegistry
import app.fayaz.otgmaster.core.DocumentNode
import app.fayaz.otgmaster.core.DocumentSource
import app.fayaz.otgmaster.core.resolve

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.FileNotFoundException
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager

/**
 * A DocumentsProvider over the mounted trees of a [MountRegistry]: the SAF serving and the
 * session safety any mounted tree needs (docs/ENGINE_SPLIT.md). Each app subclasses it once,
 * as its manifest entry point, installs its registry and [failureHook] from its companion,
 * and supplies the root's presentation; it may extend [onDocumentServed].
 *
 * The state is process-wide, in the companion, because the app reaches it without a provider
 * instance: [onDrive] for its own filesystem calls, [retire] before Format, [drainCallbacks]
 * before an unmount, [driveInUse] for Eject and idle unmount. One subclass per process.
 */
abstract class TreeDocumentsProvider : DocumentsProvider() {

    /** The root document's display name. */
    protected abstract val rootDocumentName: String

    /** The roots' summary line. */
    protected abstract val rootSummary: String
    /** A drive's summary line in the Files app; [rootSummary] for every drive unless overridden. */
    protected open fun rootSummaryFor(drive: app.fayaz.otgmaster.core.MountedTree): String = rootSummary

    /** The roots' icon, a resource of the app. */
    protected abstract val rootIcon: Int

    /** The authority when no context is attached yet: the store package's. */
    protected abstract val fallbackAuthority: String

    /**
     * A client was handed [documentId]: its row (queryDocument), a descriptor (openDocument)
     * or a thumbnail request. Called on the binder thread, with the caller's identity.
     */
    protected open fun onDocumentServed(documentId: String?) {}

    companion object {
        private object NoMounts : MountRegistry {
            override fun mounted(): List<app.fayaz.otgmaster.core.MountedTree> = emptyList()
        }

        /** The mounted trees this provider serves; the app installs its own. */
        @Volatile
        var mounts: MountRegistry = NoMounts

        /**
         * Called after a filesystem call under [onDrive] failed, with the drive and the
         * failure, after it was logged; the app uses it to tell the user a drive is full.
         */
        @Volatile
        var failureHook: ((driveId: String?, e: Throwable) -> Unit)? = null

        /**
         * Called when a client opens a file, or reads or writes one, on [driveId]: content I/O,
         * which an idle timeout counts as use. Listing roots does not call it. Container Master
         * renews its idle time here (architecture proposal, N2).
         */
        @Volatile
        var activityHook: ((driveId: String) -> Unit)? = null

        /** Free space for the roots, read without blocking the binder thread; see [RootFreeSpace]. */
        internal val rootFreeSpace = RootFreeSpace()

        /** See [queryDocument] and [queryChildDocuments]. */
        private const val QUERY_WAIT_MS = 3_000L
        private const val QUERY_WAIT_MAX_MS = 12_000L
        /** A listing read after its query gave up is kept this long for the client's next ask. */
        private const val LATE_LISTING_MS = 60_000L
        private val documentQueries = BoundedCalls<List<Map<String, Any?>>>()
        private val childQueries = BoundedCalls<List<Map<String, Any?>>>()
        private val lateListings = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Map<String, Any?>>>>()
        /** The last row read for each document, answered while its drive is busy. */
        private val knownRows = KnownRows(4096)

        /** A small LRU map, synchronised; plain Kotlin so the JVM tests can load this class. */
        private class KnownRows(private val max: Int) {
            private val map = object : LinkedHashMap<String, Map<String, Any?>>(256, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, Any?>>?) = size > max
            }
            @Synchronized fun get(key: String) = map[key]
            @Synchronized fun put(key: String, row: Map<String, Any?>) { map[key] = row }
            @Synchronized fun removeAll(pred: (String) -> Boolean) { map.keys.removeAll(pred) }
        }

        /**
         * The cache key for [docId]: the document on the filesystem mounted now, so a row
         * read before an unmount, Format or Encrypt is never answered for what replaced it.
         */
        private fun queryKey(docId: String?): String? {
            val drive = driveIdOf(docId)?.let { mounts.find(it) } ?: return null
            return "${System.identityHashCode(drive.session)}|$docId"
        }

        /** Forgets cached rows for [docId] and everything under it; after a change to it. */
        private fun forgetRows(docId: String?) {
            val key = queryKey(docId) ?: return
            val under = key.trimEnd('/') + "/"
            knownRows.removeAll { it == key || it.startsWith(under) }
            lateListings.keys.removeAll { it == key || it.startsWith(under) }
        }

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE
        )
        
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
        )

        fun rootIdForDrive(driveId: String): String = "root_$driveId"

        /** Whether a root query with [projection] needs the free-space column (null means all). */
        fun wantsAvailableBytes(projection: Array<out String>?): Boolean =
            projection == null || DocumentsContract.Root.COLUMN_AVAILABLE_BYTES in projection

        fun rootDocIdForDrive(driveId: String): String = "$driveId:/"

        // Short reads: DocumentHandle.read returns the bytes actually read (UsbFileDocumentSource.Handle).

        /**
         * One lock per drive, serialising all access to that drive's filesystem.
         *
         * libaums has no locking of its own: its FAT cache, cluster chains and
         * directory entries are plain mutable state. DocumentsProvider methods run on
         * binder threads while file I/O runs on the drive's proxy thread, so a listing and a read
         * genuinely overlap — and f58d556 showed those structures can disagree even
         * single-threaded. exFAT is already safe (ExFatFileSystem holds a
         * filesystem-wide ReentrantLock); this gives FAT32 the same guarantee at the
         * one boundary every caller crosses.
         *
         * Reentrant, because these operations nest: createFile → search → listFiles,
         * write → flush → parent.write.
         *
         * Not a substitute for locking inside libaums, which would also cover direct
         * UsbFile callers. It covers the accessors that exist today.
         */
        private val driveLocks =
            java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>()

        /** The lock of [docId]'s drive; [onDrive] takes it. The E2E cases hold it to make a drive busy. */
        fun lockFor(docId: String?): java.util.concurrent.locks.ReentrantLock =
            driveLocks.computeIfAbsent(docId?.substringBefore(':') ?: "") { java.util.concurrent.locks.ReentrantLock() }

        /** Runs [body] with the filesystem of [docId]'s drive exclusively held. */
        fun <T> onDrive(docId: String?, op: String = "request", body: () -> T): T {
            val lock = lockFor(docId)
            lock.lock()
            try {
                return body()
            } catch (e: Throwable) {
                // The caller (the Files app, a gallery, a backup app) gets the exception,
                // and the user sees "couldn't copy" at best; the reason was nowhere a
                // report could carry. No file name: reports are anonymous by design.
                if (e !is FileNotFoundException) {
                    EngineLog.logFailure("provider-$op", "Files app: $op on a mounted drive failed", e)
                }
                failureHook?.invoke(docId?.substringBefore(':'), e)
                throw e
            } finally {
                lock.unlock()
            }
        }

        /**
         * One thread per drive serving its open files' reads and writes. There used to be
         * one for every drive: a read waiting on a busy drive (a long write or delete holds
         * its lock) then stalled every open file on every other drive too, and an app reading
         * on its own main thread was reported "not responding" for it.
         */
        private val proxyHandlers = java.util.concurrent.ConcurrentHashMap<String, android.os.Handler>()

        private fun proxyHandlerFor(documentId: String?): android.os.Handler =
            proxyHandlers.computeIfAbsent(driveIdOf(documentId) ?: "") { id ->
                val thread = android.os.HandlerThread("ProxyFileDescriptorThread-$id")
                thread.start()
                android.os.Handler(thread.looper)
            }

        /**
         * Reads from a mounted backup. A backup's files live in a repository on another
         * drive, so its onRead opens and reads that drive's files through this provider.
         * Those proxy descriptors are served by [proxyHandlerFor], so this read has to wait on
         * another thread: Android refuses to open one from its own handler's thread, and if it
         * did not, the read would wait on itself.
         */
        private val backupProxyHandler: android.os.Handler by lazy {
            val thread = android.os.HandlerThread("BackupProxyFileDescriptorThread")
            thread.start()
            android.os.Handler(thread.looper)
        }

        private fun readHandlerFor(documentId: String?): android.os.Handler {
            val tree = driveIdOf(documentId)?.let { mounts.find(it) }
            return if (tree?.readsThroughOtherTrees == true) backupProxyHandler else proxyHandlerFor(documentId)
        }

        /** Documents open for writing, each with how many handles have it open. */
        private val openWriters = HashMap<String, Int>()

        private fun writerOpened(docId: String) = synchronized(openWriters) {
            openWriters[docId] = (openWriters[docId] ?: 0) + 1
        }

        private fun writerClosed(docId: String) = synchronized(openWriters) {
            val left = (openWriters[docId] ?: 1) - 1
            if (left > 0) openWriters[docId] = left else openWriters.remove(docId)
        }

        private val openHandles = HashMap<String, Int>()
        private val lastHandleEvent = HashMap<String, Long>()

        /** The part of a document id before its path, which names the mounted drive. */
        private fun driveIdOf(docId: String?) = docId?.substringBefore(':')

        private fun handleOpened(docId: String?) = driveIdOf(docId)?.let { id -> synchronized(openHandles) {
            openHandles[id] = (openHandles[id] ?: 0) + 1
            lastHandleEvent[id] = android.os.SystemClock.uptimeMillis()
        } }

        private fun handleClosed(docId: String?) = driveIdOf(docId)?.let { id -> synchronized(openHandles) {
            val left = (openHandles[id] ?: 1) - 1
            if (left > 0) openHandles[id] = left else openHandles.remove(id)
            lastHandleEvent[id] = android.os.SystemClock.uptimeMillis()
        } }

        /**
         * True if a file on [driveId] is open, or one was opened or closed in the last [settleMs].
         *
         * The second half is for a copy of several files. The file manager closes one and opens
         * the next a moment later, so "nothing is open" alone would be true between two files.
         */
        fun driveInUse(driveId: String, settleMs: Long = IDLE_SETTLE_MS): Boolean = synchronized(openHandles) {
            (openHandles[driveId] ?: 0) > 0 ||
                lastHandleEvent[driveId]?.let { android.os.SystemClock.uptimeMillis() - it < settleMs } == true
        }

        const val IDLE_SETTLE_MS = 3_000L

        private fun writerInside(docId: String) = synchronized(openWriters) {
            openWriters.keys.any { it == docId || it.startsWith("$docId/") }
        }

        /**
         * Waits for writers of [docId], or of anything below it, to finish closing.
         *
         * A client's close() returns before this process runs onRelease, and that closes the
         * file, which writes to the volume. Deleting first let that write land on what the delete
         * had freed: FAT32 allocated a cluster for the deleted folder, which only fsck found
         * (1 orphan cluster, free count off by one), and ext4 would write the cleared inode back.
         * Called without the drive's lock, which onRelease needs.
         *
         * @return false if one is still open after [waitMs], so is really in use
         */
        fun awaitWriters(docId: String, waitMs: Long = 5000): Boolean {
            val end = android.os.SystemClock.uptimeMillis() + waitMs
            while (writerInside(docId)) {
                if (android.os.SystemClock.uptimeMillis() >= end) return false
                android.os.SystemClock.sleep(20)
            }
            return true
        }

        /**
         * Resolves [documentId] for an open, truncates it if [truncate], and registers the
         * handle (and, if [write], the writer), all under the drive's lock.
         *
         * openDocument() used to do all of this outside the lock, on the binder thread.
         * Resolution walks directories and truncation frees clusters, so both could run
         * at the same time as a write on the proxy thread or another binder call on the
         * same volume: on FAT32, which has no lock of its own, two threads then changed
         * the allocation table at once. And because the writer was registered after
         * resolution, a delete could check for writers, find none, and then remove the
         * file an open had just resolved. Registering under the lock lets
         * deleteDocument() check again under the same lock and see every open that
         * finished before it. Review finding 8, 2026-11-09.
         */
        fun openUnderLock(documentId: String?, write: Boolean, truncate: Boolean,
                                   resolve: () -> DocumentHandle?): OpenFile = onDrive(documentId, "open") {
            // The mount the file belongs to, read under the same lock as the
            // resolution, so the handle is tied to the volume it was really opened on.
            val fs = driveIdOf(documentId)?.let { mounts.find(it)?.session }
            val file = resolve() ?: throw FileNotFoundException("File not found")
            if (truncate) file.truncate()
            if (write) documentId?.let { writerOpened(it) }
            handleOpened(documentId)
            driveIdOf(documentId)?.let { id -> activityHook?.invoke(id) }
            OpenFile(documentId, file, fs)
        }

        /**
         * An open file and the mounted filesystem it was opened on.
         *
         * Review finding 7, 2026-11-09: a descriptor kept working after its drive was
         * unmounted, and after Format or Encrypt put a new filesystem on the partition,
         * because the callbacks held the old file object and only took the provider's
         * lock. Its writes went through the old filesystem's allocation state onto the
         * new one, and its close wrote the old directory table over the new. Each call now
         * checks that the drive is still mounted with the same filesystem; when it is not,
         * reads, writes and syncs fail with an I/O error. The release still closes after a
         * plain unmount, as before, since that writes the file's size to its own volume;
         * it skips the close only once Format or Encrypt has [retire]d the filesystem, or
         * the drive is mounted again with a different one.
         */
        class OpenFile(val documentId: String?, val file: DocumentHandle, private val fs: Any?) {
            val isLive: Boolean
                get() = fs != null && driveIdOf(documentId)?.let { mounts.find(it)?.session } === fs

            fun <T> use(op: String, body: (DocumentHandle) -> T): T = onDrive(documentId, op) {
                if (!isLive) throw java.io.IOException(
                    "The drive this file was opened on was unmounted, formatted or encrypted since")
                driveIdOf(documentId)?.let { id -> activityHook?.invoke(id) }
                body(file)
            }

            /** Closes the file unless its filesystem was erased or replaced; then only lets it go. */
            fun release() = onDrive(documentId, "close") {
                val now = driveIdOf(documentId)?.let { mounts.find(it)?.session }
                val replaced = now != null && now !== fs
                if (!replaced && !isRetired(fs)) file.close()
            }
        }

        /** Filesystems Format or Encrypt is replacing; weak, so they do not outlive the volume. */
        private val retired = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())

        private fun isRetired(fs: Any?) = fs != null && synchronized(retired) { fs in retired }

        /**
         * Marks [fs] as about to be overwritten by Format or Encrypt, so a handle still
         * open on it can no longer write to the partition, not even its closing flush.
         */
        fun retire(fs: Any) { synchronized(retired) { retired.add(fs) } }

        /**
         * Blocks until the file callbacks already queued for [driveId] (every drive's, when
         * null) have run, then returns. Call this on a background thread before unmounting a
         * filesystem to ensure any in-flight onRelease() callbacks (which call file.close())
         * finish before the native exfat_unmount frees the ef pointer.
         */
        fun drainCallbacks(driveId: String? = null, timeoutSeconds: Long = 5) {
            val handlers = if (driveId != null) listOfNotNull(proxyHandlers[driveId]) else proxyHandlers.values.toList()
            val latch = java.util.concurrent.CountDownLatch(handlers.size)
            handlers.forEach { h -> if (!h.post { latch.countDown() }) latch.countDown() }
            latch.await(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    override fun onCreate(): Boolean = true

    override fun createDocument(documentId: String?, mimeType: String?, displayName: String?): String =
        onDrive(documentId, "create") { createDocumentImpl(documentId, mimeType, displayName) }
            .also { forgetRows(documentId) }

    private fun createDocumentImpl(
        documentId: String?,
        mimeType: String?,
        displayName: String?
    ): String {
        val name = DocumentNames.require(displayName)
        val (source, parent) = resolveDoc(documentId) ?: throw FileNotFoundException("Parent not found")
        val created = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            source.createDirectory(parent, name)
        } else {
            source.createFile(parent, name)
        }
        return getDocIdForChild(documentId, created.name)
    }

    override fun deleteDocument(documentId: String?) {
        val settled = documentId == null || awaitWriters(documentId)
        onDrive(documentId, "delete") {
            // Checked again under the lock: an open that registered its writer after the
            // wait above did so under this lock (openUnderLock), so it is seen here.
            if (!settled || (documentId != null && writerInside(documentId)))
                throw java.io.IOException("Cannot delete: a file there is still open for writing")
            deleteDocumentImpl(documentId)
        }
        forgetRows(documentId)
        forgetRows(parentDocId(documentId))
    }

    /** The folder [docId] is in; null for a root. */
    private fun parentDocId(docId: String?): String? {
        val p = parseDocId(docId) ?: return null
        if (p.path == "/") return null
        return p.driveId + ":" + p.path.substringBeforeLast('/').ifEmpty { "/" }
    }

    private fun deleteDocumentImpl(documentId: String?) {
        val (source, node) = resolveDoc(documentId) ?: throw FileNotFoundException("File not found")
        source.delete(node)
    }

    override fun renameDocument(documentId: String?, displayName: String?): String =
        onDrive(documentId, "rename") { renameDocumentImpl(documentId, displayName) }
            .also { forgetRows(documentId); forgetRows(parentDocId(documentId)) }

    private fun renameDocumentImpl(documentId: String?, displayName: String?): String {
        // Checked before the file is looked up, so a refused name changes nothing.
        val name = DocumentNames.require(displayName)
        val id = DocumentIds.parse(documentId) ?: throw FileNotFoundException("Invalid document ID")
        if (id.isRoot) throw IllegalArgumentException("A drive's root cannot be renamed")
        val (source, node) = resolveDoc(documentId) ?: throw FileNotFoundException("File not found")
        source.rename(node, name)
        return DocumentIds.child(DocumentIds.Id(id.driveId, id.components.dropLast(1)), name).toString()
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)

        val drives = mounts.mounted()
        rootFreeSpace.retainOnly(drives.map { it.id })
        for (drive in drives) {
            val row = result.newRow()
            row.add(DocumentsContract.Root.COLUMN_ROOT_ID, rootIdForDrive(drive.id))
            row.add(DocumentsContract.Root.COLUMN_SUMMARY, rootSummaryFor(drive))
            // A read-only volume does not advertise create, so the Files app does
            // not offer to save into it.
            var rootFlags = DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD or
                DocumentsContract.Root.FLAG_LOCAL_ONLY
            if (drive.readOnlyReason == null) rootFlags = rootFlags or DocumentsContract.Root.FLAG_SUPPORTS_CREATE
            row.add(DocumentsContract.Root.COLUMN_FLAGS, rootFlags)
            row.add(DocumentsContract.Root.COLUMN_TITLE, drive.name)
            row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, rootDocIdForDrive(drive.id))
            row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
            // Never read under the volume's lock here: a long write holds it for minutes.
            // And only when the caller asked for the column: an ID-only query used to pay
            // for a free-space read per drive (architecture proposal, F6).
            if (wantsAvailableBytes(projection)) {
                row.add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
                    rootFreeSpace.availableBytes(drive.id) { drive.freeBytes() })
            }
            row.add(DocumentsContract.Root.COLUMN_ICON, rootIcon)
        }

        return result
    }

    /**
     * The document's row, read on a worker and waited for at most [QUERY_WAIT_MS]; see
     * [BoundedCalls]. While the drive is busy the last row read for it is the answer, and
     * with none, the read is waited for up to [QUERY_WAIT_MAX_MS] before the query fails.
     * The read grant is made here, on the binder thread: it needs the caller's identity.
     */
    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        val key = queryKey(documentId) ?: return MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val notify = DocumentsContract.buildDocumentUri(registeredAuthority(), documentId)
        val out = documentQueries.call(key, QUERY_WAIT_MS, onLate = { r ->
            r.getOrNull()?.let { rows -> rows.firstOrNull()?.let { knownRows.put(key, it) } }
            context?.contentResolver?.notifyChange(notify, null)
        }) { onDrive(documentId, "query") { documentRows(documentId) } }
        val rows = when (out) {
            is BoundedCalls.Done -> out.value
            BoundedCalls.Late -> knownRows.get(key)?.let { listOf(it) }
                ?: (documentQueries.awaitOutstanding(key, QUERY_WAIT_MAX_MS - QUERY_WAIT_MS) as? BoundedCalls.Done)?.value
                ?: throw busy(documentId)
        }
        rows.firstOrNull()?.let { knownRows.put(key, it) }
        if (rows.firstOrNull()?.get(DocumentsContract.Document.COLUMN_MIME_TYPE) != DocumentsContract.Document.MIME_TYPE_DIR &&
            rows.isNotEmpty()) onDocumentServed(documentId)
        return cursorOf(projection ?: DEFAULT_DOCUMENT_PROJECTION, rows).apply {
            context?.contentResolver?.let { setNotificationUri(it, notify) }
        }
    }

    private fun documentRows(documentId: String?): List<Map<String, Any?>> {
        val node = nodeFor(documentId) ?: return emptyList()
        return listOf(fileRow(documentId, node))
    }

    /**
     * The folder's listing, read on a worker and waited for at most [QUERY_WAIT_MS]. While the
     * drive is busy the answer is an empty listing marked as loading (EXTRA_LOADING), with a
     * notification URI: when the read finishes, that URI is notified, the Files app asks again
     * and gets the listing kept for it in [lateListings].
     */
    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cols = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val key = queryKey(parentDocumentId) ?: return MatrixCursor(cols)
        val notify = DocumentsContract.buildChildDocumentsUri(registeredAuthority(), parentDocumentId)
        val resolver = context?.contentResolver
        lateListings.remove(key)?.takeIf { android.os.SystemClock.elapsedRealtime() - it.first < LATE_LISTING_MS }?.let {
            return cursorOf(cols, it.second).apply { resolver?.let { r -> setNotificationUri(r, notify) } }
        }
        val out = childQueries.call(key, QUERY_WAIT_MS, onLate = { r ->
            r.getOrNull()?.let { lateListings[key] = android.os.SystemClock.elapsedRealtime() to it }
            resolver?.notifyChange(notify, null)
        }) { onDrive(parentDocumentId, "list") { childRows(parentDocumentId) } }
        return when (out) {
            is BoundedCalls.Done -> cursorOf(cols, out.value)
            BoundedCalls.Late -> cursorOf(cols, emptyList()).apply {
                extras = android.os.Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
            }
        }.apply { resolver?.let { setNotificationUri(it, notify) } }
    }

    private fun childRows(parentDocumentId: String?): List<Map<String, Any?>> {
        val id = DocumentIds.parse(parentDocumentId) ?: return emptyList()
        val source = sourceFor(id.driveId) ?: return emptyList()
        val parent = source.resolve(id.components)
        if (parent == null || !parent.isDirectory) return emptyList()
        return source.children(parent).map { child ->
            val childId = getDocIdForChild(parentDocumentId, child.name)
            fileRow(childId, child).also { row -> queryKey(childId)?.let { knownRows.put(it, row) } }
        }
    }

    /** The authority this provider is registered under: the applicationId's, so the bench build's too. */
    private fun registeredAuthority(): String = context?.packageName?.let { "$it.documents" } ?: fallbackAuthority

    private fun busy(documentId: String?): FileNotFoundException {
        val name = parseDocId(documentId)?.driveId?.let { mounts.find(it)?.name }
        EngineLog.log("Files app: ${name ?: "a drive"} was busy for ${QUERY_WAIT_MAX_MS / 1000} s with a long operation, so a file could not be shown yet")
        return FileNotFoundException("The drive is busy with a long operation; try again when it finishes")
    }

    /** Not supported; but the caller asking is a viewer that may come back with "?cpn=". */
    override fun openDocumentThumbnail(
        documentId: String?,
        sizeHint: android.graphics.Point?,
        signal: CancellationSignal?
    ): android.content.res.AssetFileDescriptor {
        onDocumentServed(documentId)
        throw UnsupportedOperationException("Thumbnails not supported")
    }

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val isWrite = mode?.contains("w") == true || mode?.contains("a") == true || mode?.contains("t") == true
        val storageManager = context?.getSystemService(StorageManager::class.java)
            ?: throw IllegalStateException("StorageManager not available")
        var isDirectory = false
        val handle = openUnderLock(documentId, write = isWrite, truncate = isWrite && mode?.contains("t") == true) {
            resolveDoc(documentId)?.let { (source, node) -> isDirectory = node.isDirectory; source.open(node) }
        }
        if (!isDirectory) onDocumentServed(documentId)

        if (isWrite) {
            val callback = object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = errno("size") { handle.use("size") { it.size() } }

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int =
                    errno("read") { handle.use("read") { it.read(offset, data, size) } }

                override fun onWrite(offset: Long, size: Int, data: ByteArray): Int =
                    errno("write") {
                        handle.use("write") {
                            it.write(offset, data, size)
                            size
                        }
                    }

                override fun onFsync() = errno("fsync") { handle.use("sync") { it.flush() } }

                override fun onRelease() {
                    try {
                        handle.release()
                    } finally {
                        documentId?.let { writerClosed(it) }
                        handleClosed(documentId)
                    }
                }
            }

            val pfdMode = ParcelFileDescriptor.parseMode(mode ?: "r")
            try {
                return storageManager.openProxyFileDescriptor(pfdMode, callback, proxyHandlerFor(documentId))
            } catch (e: Exception) {
                // No descriptor, so no onRelease will ever come for this one.
                documentId?.let { writerClosed(it) }
                handleClosed(documentId)
                throw e
            }
        }

        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = errno("size") { handle.use("size") { it.size() } }

            override fun onRead(offset: Long, size: Int, data: ByteArray): Int =
                errno("read") { handle.use("read") { it.read(offset, data, size) } }

            override fun onRelease() {
                try {
                    handle.release()
                } finally {
                    handleClosed(documentId)
                }
            }
        }

        try {
            return storageManager.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY, callback, readHandlerFor(documentId)
            )
        } catch (e: Exception) {
            handleClosed(documentId)
            throw e
        }
    }
    
    /** The tree and node a document ID names, matched exactly at each level, or null. */
    private fun resolveDoc(docId: String?): Pair<DocumentSource, DocumentNode>? {
        val id = DocumentIds.parse(docId) ?: return null
        val source = sourceFor(id.driveId) ?: return null
        return source.resolve(id.components)?.let { source to it }
    }

    /** The mounted tree a drive ID names, through the [DocumentSource] contract. */
    private fun sourceFor(driveId: String): DocumentSource? = mounts.find(driveId)?.tree

    /** The node a document ID names, matched exactly at each level, or null. */
    private fun nodeFor(docId: String?): DocumentNode? {
        val id = DocumentIds.parse(docId) ?: return null
        return sourceFor(id.driveId)?.resolve(id.components)
    }

    // String work only: it used to wait on the drive's lock, behind any long write.
    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean =
        isChildDocumentImpl(parentDocumentId, documentId)

    private fun isChildDocumentImpl(parentDocumentId: String?, documentId: String?): Boolean {
        val parent = DocumentIds.parse(parentDocumentId) ?: return false
        val child = DocumentIds.parse(documentId) ?: return false
        return DocumentIds.isSameOrUnder(parent, child)
    }

    /**
     * The chain of documents from a drive's root (or from [parentDocumentId]) down to
     * [childDocumentId].
     *
     * The system folder picker calls this to open at a given place
     * (DocumentsContract.EXTRA_INITIAL_URI) and to show breadcrumbs for a granted
     * tree. Without it the picker ignored the initial location for these drives and
     * opened internal storage, so an app could not send the user back to the folder
     * they picked last time. Document ids here are paths, so this is string work and
     * touches no filesystem.
     */
    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String): DocumentsContract.Path {
        val child = DocumentIds.parse(childDocumentId) ?: throw FileNotFoundException("Invalid document ID")
        if (mounts.find(child.driveId) == null) throw FileNotFoundException("Drive not mounted")
        val chain = DocumentIds.chain(child)
        if (parentDocumentId != null) {
            val parent = DocumentIds.parse(parentDocumentId) ?: throw FileNotFoundException("Invalid parent ID")
            if (!DocumentIds.isSameOrUnder(parent, child)) throw FileNotFoundException("$childDocumentId is not under $parentDocumentId")
            return DocumentsContract.Path(null, chain.drop(parent.components.size).map { it.toString() })
        }
        return DocumentsContract.Path(rootIdForDrive(child.driveId), chain.map { it.toString() })
    }

    /** Puts [rows] (column to value) into a cursor of [columns]; columns a row lacks stay null. */
    private fun cursorOf(columns: Array<out String>, rows: List<Map<String, Any?>>): MatrixCursor {
        val result = MatrixCursor(columns)
        for (r in rows) {
            val row = result.newRow()
            for ((col, value) in r) row.add(col, value)
        }
        return result
    }

    private fun fileRow(docId: String?, file: DocumentNode): Map<String, Any?> {
        val row = LinkedHashMap<String, Any?>()
        fun MutableMap<String, Any?>.add(col: String, value: Any?) { put(col, value) }
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, if (file.isRoot) rootDocumentName else file.name)
        // 0 for a folder or when the size cannot be read: libaums throws for several metadata
        // accessors on directories and the root ("This is a directory!", "root dir!"), and
        // DocumentNode turns any failure into "unknown".
        row.add(DocumentsContract.Document.COLUMN_SIZE, file.size)

        // On a read-only volume the filesystem refuses every write anyway; not
        // advertising the capabilities keeps the Files app from offering them.
        val readOnly = parseDocId(docId)?.let { mounts.find(it.driveId)?.readOnlyReason != null } == true
        var flags = if (readOnly) 0 else
            DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
        val mimeType: String
        if (file.isDirectory) {
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR
            if (!readOnly) flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            val extension = file.name.substringAfterLast('.', "")
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: "application/octet-stream"
            if (!readOnly) flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        }

        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType)
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified)
        row.add(DocumentsContract.Document.COLUMN_FLAGS, flags)
        return row
    }

    private data class ParsedDocId(val driveId: String, val path: String)

    /**
     * Runs a proxy callback, turning a failure into the ErrnoException Android passes to
     * the client; anything else reaches it as EBADF. See [ProxyErrors].
     */
    private inline fun <T> errno(op: String, body: () -> T): T = try {
        body()
    } catch (e: android.system.ErrnoException) {
        throw e
    } catch (e: Exception) {
        throw android.system.ErrnoException(op, ProxyErrors.errnoFor(e), e)
    }

    /** Canonical IDs only; see [DocumentIds]. */
    private fun parseDocId(docId: String?): ParsedDocId? =
        DocumentIds.parse(docId)?.let { ParsedDocId(it.driveId, it.path) }

    private fun getDocIdForChild(parentDocId: String?, childName: String): String {
        val parent = DocumentIds.parse(parentDocId) ?: return childName
        return DocumentIds.child(parent, childName).toString()
    }

}

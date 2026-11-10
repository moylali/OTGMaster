package app.fayaz.otgmaster.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import app.fayaz.otgmaster.OtgMasterState
import app.fayaz.otgmaster.extension.ExtensionContract
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager

class VeraCryptDocumentProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = ExtensionContract.AUTHORITY

        /**
         * Every (package, document URI) [extendReadGrant] granted, to revoke exactly those.
         * Also kept in [GRANTS_PREFS]: grants made with grantUriPermission outlive this
         * process until reboot, so after a crash or a kill [revokeLeftoverGrants] finds them.
         */
        private val extendedGrants: MutableSet<Pair<String, android.net.Uri>> =
            java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
        private const val GRANTS_PREFS = "extended_grants"

        private fun key(g: Pair<String, android.net.Uri>) = "${g.first} ${g.second}"

        private fun remember(context: android.content.Context, g: Pair<String, android.net.Uri>, keep: Boolean) {
            val prefs = context.getSharedPreferences(GRANTS_PREFS, android.content.Context.MODE_PRIVATE)
            if (keep) prefs.edit().putBoolean(key(g), true).apply() else prefs.edit().remove(key(g)).apply()
        }

        private fun revoke(context: android.content.Context, g: Pair<String, android.net.Uri>) {
            try {
                // The per-package form revokes only what this app granted with
                // grantUriPermission: grants other apps persisted via ACTION_OPEN_DOCUMENT stay.
                context.revokeUriPermission(g.first, g.second, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                android.util.Log.w("OTGMaster", "could not revoke read access for ${g.first}: ${e.message}")
            }
            extendedGrants.remove(g)
            remember(context, g, keep = false)
        }

        /**
         * Revokes the grants for documents on drives not in [mountedDriveIds]. Called on every
         * change to the mounted list, so a drive unmounted or unplugged loses its grants at
         * once while the others keep theirs.
         */
        fun revokeExtendedGrants(context: android.content.Context, mountedDriveIds: Set<String>) {
            for (g in extendedGrants.toList()) {
                val docId = runCatching { DocumentsContract.getDocumentId(g.second) }.getOrNull()
                if (docId == null || docId.substringBefore(':') !in mountedDriveIds) revoke(context, g)
            }
        }

        /** At start-up, before anything mounts: grants a previous process made and never revoked. */
        fun revokeLeftoverGrants(context: android.content.Context) {
            val prefs = context.getSharedPreferences(GRANTS_PREFS, android.content.Context.MODE_PRIVATE)
            for (k in prefs.all.keys) {
                val uri = runCatching { android.net.Uri.parse(k.substringAfter(' ')) }.getOrNull() ?: continue
                revoke(context, k.substringBefore(' ') to uri)
            }
            prefs.edit().clear().apply()
        }

        internal fun recordGrant(context: android.content.Context, g: Pair<String, android.net.Uri>): Boolean {
            if (!extendedGrants.add(g)) return false
            remember(context, g, keep = true)
            return true
        }

        internal fun forgetGrant(context: android.content.Context, g: Pair<String, android.net.Uri>) {
            extendedGrants.remove(g)
            remember(context, g, keep = false)
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

        fun rootDocIdForDrive(driveId: String): String = "$driveId:/"

        /**
         * Fills [data] from [file] and reports how many bytes were actually read.
         *
         * Both onRead callbacks used to return the requested length unconditionally.
         * A short or failed read therefore handed the client whatever the recycled
         * SAF buffer already contained, presented as file content — silent
         * corruption with no error anywhere. The ByteBuffer's final position is the
         * only honest answer, so use it.
         */
        private fun readInto(
            file: me.jahnen.libaums.core.fs.UsbFile,
            offset: Long,
            size: Int,
            data: ByteArray,
        ): Int {
            val length = file.length
            if (offset >= length) return 0
            val toRead = Math.min(size.toLong(), length - offset).toInt()
            val buffer = ByteBuffer.wrap(data, 0, toRead)
            file.read(offset, buffer)
            return buffer.position()
        }

        /**
         * One lock per drive, serialising all access to that drive's filesystem.
         *
         * libaums has no locking of its own: its FAT cache, cluster chains and
         * directory entries are plain mutable state. DocumentsProvider methods run on
         * binder threads while file I/O runs on proxyHandler, so a listing and a read
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

        /** Runs [body] with the filesystem of [docId]'s drive exclusively held. */
        fun <T> onDrive(docId: String?, op: String = "request", body: () -> T): T {
            val lock = driveLocks.computeIfAbsent(docId?.substringBefore(':') ?: "") {
                java.util.concurrent.locks.ReentrantLock()
            }
            lock.lock()
            try {
                return body()
            } catch (e: Throwable) {
                // The caller (the Files app, a gallery, a backup app) gets the exception,
                // and the user sees "couldn't copy" at best; the reason was nowhere a
                // report could carry. No file name: reports are anonymous by design.
                if (e !is FileNotFoundException) {
                    OtgMasterState.logFailure("provider-$op", "Files app: $op on a mounted drive failed", e)
                }
                throw e
            } finally {
                lock.unlock()
            }
        }

        private val proxyHandler: android.os.Handler by lazy {
            val thread = android.os.HandlerThread("ProxyFileDescriptorThread")
            thread.start()
            android.os.Handler(thread.looper)
        }

        /**
         * Reads from a mounted backup. A backup's files live in a repository on another
         * drive, so its onRead opens and reads that drive's files through this provider.
         * Those proxy descriptors are served by [proxyHandler], so this read has to wait on
         * another thread: Android refuses to open one from its own handler's thread, and if it
         * did not, the read would wait on itself.
         */
        private val backupProxyHandler: android.os.Handler by lazy {
            val thread = android.os.HandlerThread("BackupProxyFileDescriptorThread")
            thread.start()
            android.os.Handler(thread.looper)
        }

        private fun readHandlerFor(documentId: String?): android.os.Handler {
            val drive = OtgMasterState.mountedDrives.firstOrNull { it.id == documentId?.substringBefore(':') }
            return if (drive?.fileSystem is app.fayaz.otgmaster.backup.restic.ResticFileSystem) backupProxyHandler else proxyHandler
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
        internal fun awaitWriters(docId: String, waitMs: Long = 5000): Boolean {
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
        internal fun openUnderLock(documentId: String?, write: Boolean, truncate: Boolean,
                                   resolve: () -> UsbFile?): OpenFile = onDrive(documentId, "open") {
            // The filesystem the file belongs to, read under the same lock as the
            // resolution, so the handle is tied to the volume it was really opened on.
            val fs = driveIdOf(documentId)?.let { OtgMasterState.getDrive(it)?.fileSystem }
            val file = resolve() ?: throw FileNotFoundException("File not found")
            if (truncate) file.length = 0
            if (write) documentId?.let { writerOpened(it) }
            handleOpened(documentId)
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
        internal class OpenFile(val documentId: String?, val file: UsbFile, private val fs: FileSystem?) {
            val isLive: Boolean
                get() = fs != null && driveIdOf(documentId)?.let { OtgMasterState.getDrive(it)?.fileSystem } === fs

            fun <T> use(op: String, body: (UsbFile) -> T): T = onDrive(documentId, op) {
                if (!isLive) throw java.io.IOException(
                    "The drive this file was opened on was unmounted, formatted or encrypted since")
                body(file)
            }

            /** Closes the file unless its filesystem was erased or replaced; then only lets it go. */
            fun release() = onDrive(documentId, "close") {
                val now = driveIdOf(documentId)?.let { OtgMasterState.getDrive(it)?.fileSystem }
                val replaced = now != null && now !== fs
                if (!replaced && !isRetired(fs)) file.close()
            }
        }

        /** Filesystems Format or Encrypt is replacing; weak, so they do not outlive the volume. */
        private val retired = java.util.Collections.newSetFromMap(java.util.WeakHashMap<FileSystem, Boolean>())

        private fun isRetired(fs: FileSystem?) = fs != null && synchronized(retired) { fs in retired }

        /**
         * Marks [fs] as about to be overwritten by Format or Encrypt, so a handle still
         * open on it can no longer write to the partition, not even its closing flush.
         */
        fun retire(fs: FileSystem) { synchronized(retired) { retired.add(fs) } }

        /**
         * Blocks until all previously-queued proxyHandler messages have run, then returns.
         * Call this on a background thread before unmounting a filesystem to ensure any
         * in-flight onRelease() callbacks (which call file.close()) finish before the
         * native exfat_unmount frees the ef pointer.
         */
        fun drainCallbacks(timeoutSeconds: Long = 5) {
            val latch = java.util.concurrent.CountDownLatch(1)
            proxyHandler.post { latch.countDown() }
            latch.await(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    override fun onCreate(): Boolean {
        return true
    }

    override fun createDocument(documentId: String?, mimeType: String?, displayName: String?): String =
        onDrive(documentId, "create") { createDocumentImpl(documentId, mimeType, displayName) }

    private fun createDocumentImpl(
        documentId: String?,
        mimeType: String?,
        displayName: String?
    ): String {
        if (displayName == null) throw IllegalArgumentException("displayName cannot be null")
        val parent = getFileForDocId(documentId) ?: throw FileNotFoundException("Parent not found")
        val newFile = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            parent.createDirectory(displayName)
        } else {
            parent.createFile(displayName)
        }
        return getDocIdForChild(documentId, newFile)
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
    }

    private fun deleteDocumentImpl(documentId: String?) {
        val file = getFileForDocId(documentId) ?: throw FileNotFoundException("File not found")
        file.delete()
    }

    override fun renameDocument(documentId: String?, displayName: String?): String =
        onDrive(documentId, "rename") { renameDocumentImpl(documentId, displayName) }

    private fun renameDocumentImpl(documentId: String?, displayName: String?): String {
        val file = getFileForDocId(documentId) ?: throw FileNotFoundException("File not found")
        if (displayName == null) throw IllegalArgumentException("Display name cannot be null")
        file.name = displayName
        
        val parsed = parseDocId(documentId) ?: throw FileNotFoundException("Invalid document ID")
        val parentPath = parsed.path.substringBeforeLast('/')
        val newPath = if (parentPath.isEmpty()) "/" + displayName else parentPath + "/" + displayName
        return parsed.driveId + ":" + newPath
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)

        for (drive in OtgMasterState.mountedDrives) {
            val row = result.newRow()
            row.add(DocumentsContract.Root.COLUMN_ROOT_ID, rootIdForDrive(drive.id))
            row.add(DocumentsContract.Root.COLUMN_SUMMARY, "Unlocked Volume")
            // A read-only volume does not advertise create, so the Files app does
            // not offer to save into it.
            var rootFlags = DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD or
                DocumentsContract.Root.FLAG_LOCAL_ONLY
            if (!drive.isReadOnly) rootFlags = rootFlags or DocumentsContract.Root.FLAG_SUPPORTS_CREATE
            row.add(DocumentsContract.Root.COLUMN_FLAGS, rootFlags)
            row.add(DocumentsContract.Root.COLUMN_TITLE, drive.name)
            row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, rootDocIdForDrive(drive.id))
            row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
            row.add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, drive.fileSystem.freeSpace)
            // Use the standard launcher icon
            row.add(DocumentsContract.Root.COLUMN_ICON, app.fayaz.otgmaster.R.mipmap.ic_launcher)
        }

        return result
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor =
        onDrive(documentId) { queryDocumentImpl(documentId, projection) }

    private fun queryDocumentImpl(documentId: String?, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val file = getFileForDocId(documentId)
        if (file != null) {
            includeFile(result, documentId, file, projection)
            if (!file.isDirectory) extendReadGrant(documentId)
        }
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor = onDrive(parentDocumentId) {
        queryChildDocumentsImpl(parentDocumentId, projection, sortOrder)
    }

    private fun queryChildDocumentsImpl(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = getFileForDocId(parentDocumentId)

        if (parent != null && parent.isDirectory) {
            for (child in parent.listFiles()) {
                val childId = getDocIdForChild(parentDocumentId, child)
                includeFile(result, childId, child, projection)
            }
        }
        return result
    }

    /**
     * Gives the calling app read access to [documentId]'s URI with any query string on it,
     * which is the same document it can already read.
     *
     * Google Drive's video viewer, opened from the Files app, re-requests the document as
     * ".../document/<id>?cpn=<nonce>". The grant the Files app gave it covers the URI without
     * the query, so Android refused the read before this provider ran ("Permission Denial ...
     * requires that you obtain access using ACTION_OPEN_DOCUMENT") and videos would not play,
     * while images, opened without a query, did. A prefix grant is matched on the path alone,
     * so it covers the query variants. Only for apps that reached the document without
     * MANAGE_DOCUMENTS (that is, through a grant), only read, only files — a prefix grant on
     * a directory would also cover its children listing — and revoked when the last drive
     * leaves ([revokeExtendedGrants]), or at the next start after a crash ([revokeLeftoverGrants]).
     */
    private fun extendReadGrant(documentId: String?) {
        val ctx = context ?: return
        val caller = callingPackage ?: return
        if (documentId == null || caller == ctx.packageName) return
        if (ctx.checkCallingPermission(android.Manifest.permission.MANAGE_DOCUMENTS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val uri = DocumentsContract.buildDocumentUri(AUTHORITY, documentId)
        if (!recordGrant(ctx, caller to uri)) return
        try {
            ctx.grantUriPermission(caller, uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        } catch (e: Exception) {
            forgetGrant(ctx, caller to uri)
            android.util.Log.w("OTGMaster", "could not extend read access for $caller: ${e.message}")
        }
    }

    /** Not supported; but the caller asking is a viewer that may come back with "?cpn=". */
    override fun openDocumentThumbnail(
        documentId: String?,
        sizeHint: android.graphics.Point?,
        signal: CancellationSignal?
    ): android.content.res.AssetFileDescriptor {
        extendReadGrant(documentId)
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
        val handle = openUnderLock(documentId, write = isWrite, truncate = isWrite && mode?.contains("t") == true) {
            getFileForDocId(documentId)
        }
        val file = handle.file
        if (!file.isDirectory) extendReadGrant(documentId)

        if (isWrite) {
            val callback = object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = handle.use("size") { it.length }

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int =
                    handle.use("read") { readInto(it, offset, size, data) }

                override fun onWrite(offset: Long, size: Int, data: ByteArray): Int =
                    handle.use("write") {
                        it.write(offset, ByteBuffer.wrap(data, 0, size))
                        size
                    }

                override fun onFsync() = handle.use("sync") { it.flush() }

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
                return storageManager.openProxyFileDescriptor(pfdMode, callback, proxyHandler)
            } catch (e: Exception) {
                // No descriptor, so no onRelease will ever come for this one.
                documentId?.let { writerClosed(it) }
                handleClosed(documentId)
                throw e
            }
        }

        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = handle.use("size") { it.length }

            override fun onRead(offset: Long, size: Int, data: ByteArray): Int =
                handle.use("read") { readInto(it, offset, size, data) }

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
    
    private fun getFileForDocId(docId: String?): UsbFile? {
        val parsed = parseDocId(docId) ?: return null
        val drive = OtgMasterState.getDrive(parsed.driveId) ?: return null
        if (parsed.path == "/") return drive.fileSystem.rootDirectory
        
        var currentFile = drive.fileSystem.rootDirectory
        val parts = parsed.path.split("/")
        for (part in parts) {
            if (part.isEmpty()) continue
            var found = false
            for (child in currentFile.listFiles()) {
                if (child.name == part) {
                    currentFile = child
                    found = true
                    break
                }
            }
            if (!found) return null
        }
        return currentFile
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean =
        onDrive(documentId) { isChildDocumentImpl(parentDocumentId, documentId) }

    private fun isChildDocumentImpl(parentDocumentId: String?, documentId: String?): Boolean {
        val parent = parseDocId(parentDocumentId) ?: return false
        val child = parseDocId(documentId) ?: return false
        if (parent.driveId != child.driveId) return false
        return child.path == parent.path || child.path.startsWith(parent.path.ensureTrailingSlash())
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
        val child = parseDocId(childDocumentId) ?: throw FileNotFoundException("Invalid document ID")
        if (OtgMasterState.getDrive(child.driveId) == null) throw FileNotFoundException("Drive not mounted")
        val parts = child.path.split('/').filter { it.isNotEmpty() }
        val chain = ArrayList<String>(parts.size + 1)
        chain += rootDocIdForDrive(child.driveId)
        var path = ""
        for (part in parts) {
            path += "/$part"
            chain += "${child.driveId}:$path"
        }
        if (parentDocumentId != null) {
            val parent = parseDocId(parentDocumentId) ?: throw FileNotFoundException("Invalid parent ID")
            val start = chain.indexOfFirst { parseDocId(it)?.path == parent.path }
            if (parent.driveId != child.driveId || start < 0) throw FileNotFoundException("$childDocumentId is not under $parentDocumentId")
            return DocumentsContract.Path(null, chain.subList(start, chain.size))
        }
        return DocumentsContract.Path(rootIdForDrive(child.driveId), chain)
    }

    private fun includeFile(
        result: MatrixCursor,
        docId: String?,
        file: UsbFile,
        projection: Array<out String>? = null,
    ) {
        val row = result.newRow()
        // Companion-app columns (ExtensionContract). Only computed when asked for,
        // which a caller can only do through a grant covering this drive.
        if (projection != null && projection.any { it in ExtensionContract.VOLUME_COLUMNS }) {
            parseDocId(docId)?.let { OtgMasterState.getDrive(it.driveId) }?.let { drive ->
                val values = ExtensionContract.volumeValues(
                    drive, wantFree = ExtensionContract.COLUMN_FREE_BYTES in projection)
                for ((column, value) in values) row.add(column, value)
            }
        }
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, if (file.isRoot) "VeraCrypt Drive" else file.name)
        // libaums throws for several metadata accessors on directories and/or the root
        // specifically (length: "This is a directory!", lastModified: "root dir!") — rather
        // than chase each one individually, treat any failure here as "unknown" (0).
        row.add(DocumentsContract.Document.COLUMN_SIZE, runCatching { if (file.isDirectory) 0L else file.length }.getOrDefault(0L))

        // On a read-only volume the filesystem refuses every write anyway; not
        // advertising the capabilities keeps the Files app from offering them.
        val readOnly = parseDocId(docId)?.let { OtgMasterState.getDrive(it.driveId)?.isReadOnly } == true
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
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, runCatching { file.lastModified() }.getOrDefault(0L))
        row.add(DocumentsContract.Document.COLUMN_FLAGS, flags)
    }

    private data class ParsedDocId(val driveId: String, val path: String)

    private fun parseDocId(docId: String?): ParsedDocId? {
        if (docId == null) return null
        val parts = docId.split(":", limit = 2)
        if (parts.size != 2 || parts[0].isBlank()) return null
        val path = parts[1].ifBlank { "/" }
        return ParsedDocId(parts[0], if (path.startsWith("/")) path else "/$path")
    }

    private fun getDocIdForChild(parentDocId: String?, child: UsbFile): String {
        val parsed = parseDocId(parentDocId) ?: return child.name
        val childPath = if (parsed.path == "/") "/${child.name}" else "${parsed.path}/${child.name}"
        return "${parsed.driveId}:$childPath"
    }

    private fun String.ensureTrailingSlash(): String = if (endsWith("/")) this else "$this/"

}

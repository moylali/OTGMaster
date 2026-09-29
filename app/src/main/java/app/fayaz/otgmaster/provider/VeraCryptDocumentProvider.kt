package app.fayaz.otgmaster.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import app.fayaz.otgmaster.OtgMasterState
import me.jahnen.libaums.core.fs.UsbFile
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager

class VeraCryptDocumentProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "app.fayaz.otgmaster.documents"
        
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
        fun <T> onDrive(docId: String?, body: () -> T): T {
            val lock = driveLocks.computeIfAbsent(docId?.substringBefore(':') ?: "") {
                java.util.concurrent.locks.ReentrantLock()
            }
            lock.lock()
            try { return body() } finally { lock.unlock() }
        }

        private val proxyHandler: android.os.Handler by lazy {
            val thread = android.os.HandlerThread("ProxyFileDescriptorThread")
            thread.start()
            android.os.Handler(thread.looper)
        }

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
        onDrive(documentId) { createDocumentImpl(documentId, mimeType, displayName) }

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

    override fun deleteDocument(documentId: String?) =
        onDrive(documentId) { deleteDocumentImpl(documentId) }

    private fun deleteDocumentImpl(documentId: String?) {
        val file = getFileForDocId(documentId) ?: throw FileNotFoundException("File not found")
        file.delete()
    }

    override fun renameDocument(documentId: String?, displayName: String?): String =
        onDrive(documentId) { renameDocumentImpl(documentId, displayName) }

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
            includeFile(result, documentId, file)
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
                includeFile(result, childId, child)
            }
        }
        return result
    }

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = getFileForDocId(documentId) ?: throw FileNotFoundException("File not found")
        
        val isWrite = mode?.contains("w") == true || mode?.contains("a") == true || mode?.contains("t") == true
        if (isWrite) {
            val storageManager = context?.getSystemService(StorageManager::class.java)
                ?: throw IllegalStateException("StorageManager not available")

            if (mode?.contains("t") == true) {
                file.length = 0 // truncate
            }
            
            val callback = object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = onDrive(documentId) { file.length }

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int =
                    onDrive(documentId) { readInto(file, offset, size, data) }

                override fun onWrite(offset: Long, size: Int, data: ByteArray): Int =
                    onDrive(documentId) {
                        file.write(offset, ByteBuffer.wrap(data, 0, size))
                        size
                    }

                override fun onFsync() = onDrive(documentId) { file.flush() }

                override fun onRelease() = onDrive(documentId) { file.close() }
            }

            val pfdMode = ParcelFileDescriptor.parseMode(mode ?: "r")
            return storageManager.openProxyFileDescriptor(pfdMode, callback, proxyHandler)
        }

        val storageManager = context?.getSystemService(StorageManager::class.java)
            ?: throw IllegalStateException("StorageManager not available")

        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = onDrive(documentId) { file.length }

            override fun onRead(offset: Long, size: Int, data: ByteArray): Int =
                onDrive(documentId) { readInto(file, offset, size, data) }

            override fun onRelease() = onDrive(documentId) { file.close() }
        }

        return storageManager.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY, callback, proxyHandler
        )
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

    private fun includeFile(result: MatrixCursor, docId: String?, file: UsbFile) {
        val row = result.newRow()
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

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
import java.io.FileOutputStream
import java.io.IOException
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
    }

    override fun onCreate(): Boolean {
        return true
    }

    override fun createDocument(
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
        val file = getFileForDocId(documentId) ?: throw FileNotFoundException("File not found")
        file.delete()
    }

    override fun renameDocument(documentId: String?, displayName: String?): String {
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
            row.add(
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD or 
                DocumentsContract.Root.FLAG_LOCAL_ONLY or 
                DocumentsContract.Root.FLAG_SUPPORTS_CREATE
            )
            row.add(DocumentsContract.Root.COLUMN_TITLE, drive.name)
            row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, rootDocIdForDrive(drive.id))
            row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
            row.add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, drive.fileSystem.freeSpace)
            // Use the standard launcher icon
            row.add(DocumentsContract.Root.COLUMN_ICON, app.fayaz.otgmaster.R.mipmap.ic_launcher)
        }

        return result
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
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
                override fun onGetSize(): Long {
                    return file.length
                }

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    if (offset >= file.length) return 0
                    val toRead = Math.min(size.toLong(), file.length - offset).toInt()
                    file.read(offset, ByteBuffer.wrap(data, 0, toRead))
                    return toRead
                }

                override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
                    file.write(offset, ByteBuffer.wrap(data, 0, size))
                    return size
                }

                override fun onFsync() {
                    file.flush()
                }

                override fun onRelease() {
                    file.close()
                }
            }

            val pfdMode = ParcelFileDescriptor.parseMode(mode ?: "r")
            return storageManager.openProxyFileDescriptor(pfdMode, callback, null)
        }

        // To stream data from libaums to the OS, we use a pipe
        val pipe = ParcelFileDescriptor.createPipe()
        val readFd = pipe[0]
        val writeFd = pipe[1]

        Thread {
            try {
                FileOutputStream(writeFd.fileDescriptor).use { out ->
                    val buffer = ByteArray(65536)
                    var offset: Long = 0
                    while (offset < file.length) {
                        if (signal?.isCanceled == true) break
                        
                        val toRead = Math.min(65536.toLong(), file.length - offset).toInt()
                        file.read(offset, ByteBuffer.wrap(buffer, 0, toRead))
                        out.write(buffer, 0, toRead)
                        offset += toRead
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    writeFd.close()
                } catch (e: IOException) {
                }
            }
        }.start()

        return readFd
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

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
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

        var flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
        val mimeType: String
        if (file.isDirectory) {
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR
            flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            val extension = file.name.substringAfterLast('.', "")
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: "application/octet-stream"
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
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

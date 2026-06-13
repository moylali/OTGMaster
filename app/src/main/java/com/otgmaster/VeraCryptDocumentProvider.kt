package com.otgmaster

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class VeraCryptDocumentProvider : DocumentsProvider() {

    override fun onCreate(): Boolean {
        return true
    }

    override fun queryRoots(projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)

        val drives = OtgMasterState.mountedDrives
        val flags = Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY

        for (drive in drives) {
            result.newRow().apply {
                add(Root.COLUMN_ROOT_ID, "root_${drive.id}")
                add(Root.COLUMN_SUMMARY, "VeraCrypt OTG Drive")
                add(Root.COLUMN_FLAGS, flags)
                add(Root.COLUMN_TITLE, drive.name)
                add(Root.COLUMN_DOCUMENT_ID, getDocIdForFile(drive.id, "/"))
                add(Root.COLUMN_MIME_TYPES, Document.MIME_TYPE_DIR)
                add(Root.COLUMN_AVAILABLE_BYTES, drive.fileSystem.freeSpace)
                add(Root.COLUMN_CAPACITY_BYTES, drive.fileSystem.capacity)
            }
        }

        return result
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val file = getFileForDocId(documentId)
        if (file != null) {
            includeFile(result, documentId, file)
        }
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = getFileForDocId(parentDocumentId)
        
        if (parent != null && parent.isDirectory) {
            try {
                for (file in parent.listFiles()) {
                    val docId = getDocIdForFile(parentDocumentId, file)
                    includeFile(result, docId, file)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to list children for $parentDocumentId", e)
            }
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = getFileForDocId(documentId)
            ?: throw java.io.FileNotFoundException("Document not found: $documentId")
            
        if (file.isDirectory) {
            throw IllegalArgumentException("Cannot open a directory: $documentId")
        }

        // To bridge libaums UsbFile to ParcelFileDescriptor, we can use a pipe.
        // The system provides createReliablePipe() or we can just use createPipe().
        val pipe = ParcelFileDescriptor.createPipe()
        val readFd = pipe[0]
        val writeFd = pipe[1]

        Thread {
            try {
                FileOutputStream(writeFd.fileDescriptor).use { out ->
                    UsbFileInputStream(file).use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to stream document $documentId", e)
            } finally {
                try {
                    writeFd.close()
                } catch (e: IOException) {
                    // Ignore
                }
            }
        }.start()

        return readFd
    }

    private fun getFileForDocId(docId: String): UsbFile? {
        // docId format: "driveId:path" e.g., "123:/" or "123:/folder/file.txt"
        val parts = docId.split(":", limit = 2)
        if (parts.size != 2) return null
        
        val driveId = parts[0]
        val path = parts[1]
        
        val drive = OtgMasterState.getDrive(driveId) ?: return null
        val fs = drive.fileSystem
        
        if (path == "/") return fs.rootDirectory

        // Traverse from root to find the file
        var current = fs.rootDirectory
        val pathParts = path.split("/").filter { it.isNotEmpty() }
        
        for (part in pathParts) {
            current = current.listFiles().find { it.name == part } ?: return null
        }
        
        return current
    }

    private fun getDocIdForFile(parentDocId: String, file: UsbFile): String {
        return if (parentDocId.endsWith(":/")) "${parentDocId}${file.name}" else "$parentDocId/${file.name}"
    }
    
    private fun getDocIdForFile(driveId: String, path: String): String {
        return "$driveId:$path"
    }

    private fun includeFile(result: MatrixCursor, docId: String, file: UsbFile) {
        var flags = 0
        if (file.isDirectory) {
            flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }

        val displayName = if (docId.endsWith(":/")) "VeraCrypt Drive" else file.name
        val mimeType = if (file.isDirectory) Document.MIME_TYPE_DIR else getMimeType(file.name)

        result.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docId)
            add(Document.COLUMN_DISPLAY_NAME, displayName)
            add(Document.COLUMN_SIZE, if (file.isDirectory) 0 else file.length)
            add(Document.COLUMN_MIME_TYPE, mimeType)
            add(Document.COLUMN_LAST_MODIFIED, if (file.isRoot) 0L else file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun getMimeType(name: String): String {
        val extension = name.substringAfterLast('.', "")
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
            ?: "application/octet-stream"
    }

    companion object {
        private const val TAG = "VeraCryptDocProvider"
        private const val ROOT_ID = "veracrypt_root"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_AVAILABLE_BYTES,
            Root.COLUMN_CAPACITY_BYTES,
            Root.COLUMN_ICON
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
        )
    }
}

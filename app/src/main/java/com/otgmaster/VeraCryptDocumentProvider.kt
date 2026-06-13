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

        // If no file system is currently mounted, we still show the root but maybe it will be empty
        // or we can choose not to show the root if OtgMasterState.currentFileSystem is null.
        // For better UX, we show the root, and if they click it before unlock, it's empty.
        
        val flags = Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY

        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_SUMMARY, "VeraCrypt OTG Drive")
            add(Root.COLUMN_FLAGS, flags)
            add(Root.COLUMN_TITLE, "OTG Master")
            add(Root.COLUMN_DOCUMENT_ID, getDocIdForFile("/"))
            add(Root.COLUMN_MIME_TYPES, Document.MIME_TYPE_DIR)
            // Available bytes can be obtained if the filesystem is mounted
            val fs = OtgMasterState.currentFileSystem
            if (fs != null) {
                add(Root.COLUMN_AVAILABLE_BYTES, fs.freeSpace)
                add(Root.COLUMN_CAPACITY_BYTES, fs.capacity)
            }
            // No custom icon needed, system provides default
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
        val fs = OtgMasterState.currentFileSystem ?: return null
        if (docId == "/") return fs.rootDirectory

        // Traverse from root to find the file
        var current = fs.rootDirectory
        val parts = docId.split("/").filter { it.isNotEmpty() }
        
        for (part in parts) {
            current = current.listFiles().find { it.name == part } ?: return null
        }
        
        return current
    }

    private fun getDocIdForFile(parentDocId: String, file: UsbFile): String {
        return if (parentDocId == "/") "/${file.name}" else "$parentDocId/${file.name}"
    }
    
    private fun getDocIdForFile(path: String): String {
        return path
    }

    private fun includeFile(result: MatrixCursor, docId: String, file: UsbFile) {
        var flags = 0
        if (file.isDirectory) {
            flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }

        val displayName = if (docId == "/") "VeraCrypt Drive" else file.name
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

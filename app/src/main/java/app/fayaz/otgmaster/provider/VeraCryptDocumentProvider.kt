package app.fayaz.otgmaster.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

class VeraCryptDocumentProvider : DocumentsProvider() {

    companion object {
        private const val AUTHORITY = "app.fayaz.otgmaster.documents"
        private const val ROOT_DOCUMENT_ID = "root"
        
        // We hold a global reference to the mounted filesystem.
        // In a real app, you might want to handle multiple devices or pass this via a more robust state manager.
        var mountedFileSystem: FileSystem? = null
        
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
    }

    override fun onCreate(): Boolean {
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)

        val fs = mountedFileSystem
        if (fs != null) {
            val row = result.newRow()
            row.add(DocumentsContract.Root.COLUMN_ROOT_ID, "veracrypt_root")
            row.add(DocumentsContract.Root.COLUMN_SUMMARY, "Unlocked Volume")
            row.add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
            row.add(DocumentsContract.Root.COLUMN_TITLE, "OTGMaster (Unlocked)")
            row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
            row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
            row.add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, fs.freeSpace)
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
                val childId = if (parentDocumentId == ROOT_DOCUMENT_ID) {
                    child.name
                } else {
                    "$parentDocumentId/${child.name}"
                }
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
        
        // We only support reading for now
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        
        val isWrite = mode?.contains("w") == true
        if (isWrite) {
            throw FileNotFoundException("Read-only file system")
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
        val fs = mountedFileSystem ?: return null
        if (docId == null || docId == ROOT_DOCUMENT_ID) {
            return fs.rootDirectory
        }
        
        var currentFile = fs.rootDirectory
        val parts = docId.split("/")
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

    private fun includeFile(result: MatrixCursor, docId: String?, file: UsbFile) {
        val row = result.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name)
        // libaums throws for several metadata accessors on directories and/or the root
        // specifically (length: "This is a directory!", lastModified: "root dir!") — rather
        // than chase each one individually, treat any failure here as "unknown" (0).
        row.add(DocumentsContract.Document.COLUMN_SIZE, runCatching { if (file.isDirectory) 0L else file.length }.getOrDefault(0L))

        var flags = 0
        val mimeType: String
        if (file.isDirectory) {
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR
            // No WRITE flag for now
        } else {
            val extension = file.name.substringAfterLast('.', "")
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: "application/octet-stream"
        }

        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType)
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, runCatching { file.lastModified() }.getOrDefault(0L))
        row.add(DocumentsContract.Document.COLUMN_FLAGS, flags)
    }
}

package com.filo.transfer.core.storage.provider

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.filo.transfer.core.storage.model.FileMediaType
import com.filo.transfer.core.storage.model.StorageError
import com.filo.transfer.core.storage.model.StorageFile
import com.filo.transfer.core.storage.model.StorageResult
import java.io.File
import java.io.FileNotFoundException

/**
 * Resolves comprehensive metadata for any given content:// (or file://) URI.
 *
 * Guarantees that resources (Cursors, FileDescriptors) are safely closed immediately,
 * and extracts metadata without reading the actual file data into memory.
 */
class FileMetadataResolver(
    private val contentResolver: ContentResolver
) {

    /**
     * Resolves metadata for the provided URI into an immutable [StorageFile].
     */
    fun resolve(uri: Uri?): StorageResult<StorageFile> {
        if (uri == null) {
            return StorageResult.Failure(StorageError.InvalidUri(null, "URI is null"))
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != ContentResolver.SCHEME_CONTENT && scheme != ContentResolver.SCHEME_FILE) {
            return StorageResult.Failure(
                StorageError.InvalidUri(uri, "Unsupported URI scheme: ${uri.scheme}. Expected 'content' or 'file'")
            )
        }

        return try {
            if (scheme == ContentResolver.SCHEME_FILE) {
                resolveFileUri(uri)
            } else {
                resolveContentUri(uri)
            }
        } catch (e: SecurityException) {
            StorageResult.Failure(StorageError.SecurityError(uri, e))
        } catch (e: FileNotFoundException) {
            StorageResult.Failure(StorageError.FileNotFound(uri, e))
        } catch (e: IllegalArgumentException) {
            StorageResult.Failure(StorageError.InvalidUri(uri, e.message, e))
        } catch (e: Exception) {
            StorageResult.Failure(StorageError.MetadataUnavailable(uri, e.message, e))
        }
    }

    private fun resolveContentUri(uri: Uri): StorageResult<StorageFile> {
        var displayName: String? = null
        var size: Long = StorageFile.UNKNOWN_SIZE
        var lastModified: Long = StorageFile.UNKNOWN_TIMESTAMP

        // 1. Query ContentResolver with OpenableColumns projection
        val projection = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE
        )

        try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex)
                    }

                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (e: Exception) {
            // Some specialized content providers throw on OpenableColumns projection query.
            // We will gracefully fall back to alternative inspection below.
        }

        // 2. Query for date_modified or document last_modified timestamp if available
        lastModified = queryLastModifiedTimestamp(uri)

        // 3. Fallback for size if OpenableColumns was unavailable or returned 0/negative
        if (size <= 0L) {
            size = querySizeFromFileDescriptor(uri)
        }

        // 4. Resolve MIME type
        val mimeType = resolveMimeType(uri, displayName)

        // 5. Fallback for display name
        val finalDisplayName = displayName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "file_${System.currentTimeMillis()}"

        val mediaType = FileMediaType.fromMimeAndName(mimeType, finalDisplayName)

        val storageFile = StorageFile(
            id = uri.toString(),
            uri = uri,
            displayName = finalDisplayName,
            mimeType = mimeType,
            size = size,
            lastModified = lastModified,
            mediaType = mediaType
        )

        return StorageResult.Success(storageFile)
    }

    private fun resolveFileUri(uri: Uri): StorageResult<StorageFile> {
        val path = uri.path ?: return StorageResult.Failure(
            StorageError.InvalidUri(uri, "File URI contains no path")
        )
        val file = File(path)
        if (!file.exists()) {
            return StorageResult.Failure(StorageError.FileNotFound(uri))
        }

        val displayName = file.name.takeIf { it.isNotBlank() } ?: "file"
        val mimeType = resolveMimeType(uri, displayName)
        val mediaType = FileMediaType.fromMimeAndName(mimeType, displayName)

        return StorageResult.Success(
            StorageFile(
                id = uri.toString(),
                uri = uri,
                displayName = displayName,
                mimeType = mimeType,
                size = file.length(),
                lastModified = file.lastModified(),
                mediaType = mediaType
            )
        )
    }

    private fun queryLastModifiedTimestamp(uri: Uri): Long {
        // Try DocumentsContract column first if applicable
        try {
            val docProjection = arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            contentResolver.query(uri, docProjection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (index != -1 && !cursor.isNull(index)) {
                        val timestamp = cursor.getLong(index)
                        if (timestamp > 0L) return timestamp
                    }
                }
            }
        } catch (_: Exception) {
        }

        // Try MediaStore column next
        try {
            val mediaProjection = arrayOf(MediaStore.MediaColumns.DATE_MODIFIED)
            contentResolver.query(uri, mediaProjection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    if (index != -1 && !cursor.isNull(index)) {
                        val seconds = cursor.getLong(index)
                        if (seconds > 0L) return seconds * 1000L // MediaStore stores seconds
                    }
                }
            }
        } catch (_: Exception) {
        }

        return StorageFile.UNKNOWN_TIMESTAMP
    }

    private fun querySizeFromFileDescriptor(uri: Uri): Long {
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val statSize = pfd.statSize
                if (statSize > 0L) statSize else StorageFile.UNKNOWN_SIZE
            } ?: StorageFile.UNKNOWN_SIZE
        } catch (_: Exception) {
            StorageFile.UNKNOWN_SIZE
        }
    }

    private fun resolveMimeType(uri: Uri, displayName: String?): String {
        return try {
            contentResolver.getType(uri)?.takeIf { it.isNotBlank() }
                ?: guessMimeTypeFromExtension(displayName)
                ?: StorageFile.DEFAULT_MIME_TYPE
        } catch (_: Exception) {
            guessMimeTypeFromExtension(displayName) ?: StorageFile.DEFAULT_MIME_TYPE
        }
    }

    private fun guessMimeTypeFromExtension(displayName: String?): String? {
        val ext = displayName?.substringAfterLast('.', "")?.lowercase() ?: return null
        if (ext.isBlank()) return null
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }
}

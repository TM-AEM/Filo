package com.filo.transfer.core.storage.provider

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.filo.transfer.core.storage.model.StorageError
import com.filo.transfer.core.storage.model.StorageResult

/**
 * Recursively enumerates a SAF document *tree* [Uri] into a flat list of leaf files together
 * with their paths relative to the tree root.
 *
 * Folders themselves are never transferred (the protocol only carries files), so a folder
 * containing only empty subdirectories contributes nothing. The relative paths preserve the
 * source structure so the receiver can rebuild the hierarchy inside its destination root.
 */
class FolderEnumerator(
    private val contentResolver: ContentResolver
) {

    /** A leaf file discovered inside an enumerated folder tree. */
    data class FolderFile(
        val uri: Uri,
        val relativePath: String
    )

    /**
     * Enumerates [treeUri], which must be a SAF tree Uri returned by the folder picker
     * (see [DocumentsContract.isTreeUri]).
     *
     * @return the flat list of leaf files, or a [StorageResult.Failure] when [treeUri] is not
     * a tree Uri or the provider cannot be queried.
     */
    fun enumerate(treeUri: Uri): StorageResult<List<FolderFile>> {
        if (!DocumentsContract.isTreeUri(treeUri)) {
            return StorageResult.Failure(
                error = StorageError.Unknown(
                    uri = treeUri,
                    message = "Not a SAF tree Uri: $treeUri"
                )
            )
        }

        return try {
            val results = ArrayList<FolderFile>()
            val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
            enumerateChildren(childrenUri, "", results)
            StorageResult.Success(results)
        } catch (e: Exception) {
            StorageResult.Failure(
                error = StorageError.Unknown(
                    uri = treeUri,
                    message = "Failed to enumerate folder tree: ${e.message}",
                    cause = e
                )
            )
        }
    }

    private fun enumerateChildren(
        childrenUri: Uri,
        relativeDir: String,
        results: ArrayList<FolderFile>
    ) {
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null,
                null,
                null
            )
            if (cursor == null) return

            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext()) {
                val documentId = if (idIndex >= 0) cursor.getString(idIndex) else continue
                val displayName = if (nameIndex >= 0) cursor.getString(nameIndex) else continue
                val mimeType = if (mimeIndex >= 0) cursor.getString(mimeIndex) else null

                val childRelative = if (relativeDir.isEmpty()) displayName else "$relativeDir/$displayName"

                if (DocumentsContract.Document.MIME_TYPE_DIR == mimeType) {
                    val grandChildrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(childrenUri, documentId)
                    enumerateChildren(grandChildrenUri, childRelative, results)
                } else {
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(childrenUri, documentId)
                    results.add(FolderFile(uri = childUri, relativePath = childRelative))
                }
            }
        } finally {
            cursor?.close()
        }
    }
}

package com.filo.transfer.core.storage.provider

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.filo.transfer.core.storage.model.FileMediaType
import com.filo.transfer.core.storage.model.StorageError
import com.filo.transfer.core.storage.model.StorageFile
import com.filo.transfer.core.storage.model.StorageResult

/**
 * Provides structured query access to device media using Android's [MediaStore] API.
 *
 * All database operations are cursor-leak-safe, paginated, and only query row metadata.
 */
class MediaStoreProvider(
    private val contentResolver: ContentResolver
) {

    /**
     * Queries images from [MediaStore.Images.Media.EXTERNAL_CONTENT_URI].
     */
    fun queryImages(limit: Int = 100, offset: Int = 0): StorageResult<List<StorageFile>> {
        return queryCollection(
            contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            defaultMediaType = FileMediaType.IMAGE,
            limit = limit,
            offset = offset
        )
    }

    /**
     * Queries videos from [MediaStore.Video.Media.EXTERNAL_CONTENT_URI].
     */
    fun queryVideos(limit: Int = 100, offset: Int = 0): StorageResult<List<StorageFile>> {
        return queryCollection(
            contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            defaultMediaType = FileMediaType.VIDEO,
            limit = limit,
            offset = offset
        )
    }

    /**
     * Queries audio tracks from [MediaStore.Audio.Media.EXTERNAL_CONTENT_URI].
     */
    fun queryAudio(limit: Int = 100, offset: Int = 0): StorageResult<List<StorageFile>> {
        return queryCollection(
            contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            defaultMediaType = FileMediaType.AUDIO,
            limit = limit,
            offset = offset
        )
    }

    /**
     * Queries document and download files from [MediaStore.Files.getContentUri].
     */
    fun queryDocuments(limit: Int = 100, offset: Int = 0): StorageResult<List<StorageFile>> {
        val filesUri = MediaStore.Files.getContentUri("external")
        val mimeTypes = arrayOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain",
            "application/zip"
        )
        val selection = "${MediaStore.MediaColumns.MIME_TYPE} IN (${mimeTypes.joinToString(",") { "'$it'" }})"

        return queryCollection(
            contentUri = filesUri,
            defaultMediaType = FileMediaType.DOCUMENT,
            limit = limit,
            offset = offset,
            selection = selection
        )
    }

    /**
     * Generic query on any MediaStore collection URI.
     */
    fun queryCollection(
        contentUri: Uri,
        defaultMediaType: FileMediaType,
        limit: Int = 100,
        offset: Int = 0,
        selection: String? = null,
        selectionArgs: Array<String>? = null
    ): StorageResult<List<StorageFile>> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        return try {
            val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val queryArgs = Bundle().apply {
                    putInt(ContentResolver.QUERY_ARG_LIMIT, limit.coerceAtLeast(1))
                    putInt(ContentResolver.QUERY_ARG_OFFSET, offset.coerceAtLeast(0))
                    putStringArray(
                        ContentResolver.QUERY_ARG_SORT_COLUMNS,
                        arrayOf(MediaStore.MediaColumns.DATE_MODIFIED)
                    )
                    putInt(
                        ContentResolver.QUERY_ARG_SORT_DIRECTION,
                        ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                    )
                    if (selection != null) {
                        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    }
                    if (selectionArgs != null) {
                        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                    }
                }
                contentResolver.query(contentUri, projection, queryArgs, null)
            } else {
                val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC LIMIT $limit OFFSET $offset"
                contentResolver.query(contentUri, projection, selection, selectionArgs, sortOrder)
            }

            cursor?.use { c ->
                val resultList = ArrayList<StorageFile>(c.count)
                val idIndex = c.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIndex = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIndex = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val mimeIndex = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val dateModifiedIndex = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)

                while (c.moveToNext()) {
                    val id = if (idIndex != -1) c.getLong(idIndex) else -1L
                    val itemUri = if (id != -1L) ContentUris.withAppendedId(contentUri, id) else contentUri
                    val displayName = if (nameIndex != -1) c.getString(nameIndex) ?: "file_$id" else "file_$id"
                    val size = if (sizeIndex != -1 && !c.isNull(sizeIndex)) c.getLong(sizeIndex) else StorageFile.UNKNOWN_SIZE
                    val mimeType = if (mimeIndex != -1 && !c.isNull(mimeIndex)) c.getString(mimeIndex) else StorageFile.DEFAULT_MIME_TYPE
                    val dateModifiedSeconds = if (dateModifiedIndex != -1 && !c.isNull(dateModifiedIndex)) c.getLong(dateModifiedIndex) else 0L

                    val mediaType = if (defaultMediaType != FileMediaType.OTHER) {
                        defaultMediaType
                    } else {
                        FileMediaType.fromMimeAndName(mimeType, displayName)
                    }

                    resultList.add(
                        StorageFile(
                            id = itemUri.toString(),
                            uri = itemUri,
                            displayName = displayName,
                            mimeType = mimeType,
                            size = size,
                            lastModified = dateModifiedSeconds * 1000L,
                            mediaType = mediaType
                        )
                    )
                }
                StorageResult.Success(resultList)
            } ?: StorageResult.Success(emptyList())
        } catch (e: SecurityException) {
            StorageResult.Failure(StorageError.SecurityError(contentUri, e))
        } catch (e: Exception) {
            StorageResult.Failure(StorageError.MetadataUnavailable(contentUri, e.message, e))
        }
    }
}

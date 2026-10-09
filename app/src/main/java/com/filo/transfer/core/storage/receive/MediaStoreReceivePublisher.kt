package com.filo.transfer.core.storage.receive

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.filo.transfer.core.storage.model.FileMediaType
import java.io.File

/**
 * Publishes checksum-verified received files into MediaStore with pending semantics.
 *
 * Incomplete files are never inserted, or are deleted if a pending row was created
 * and the copy failed. SAF destination-selection UX is deferred to TASK 38.
 */
class MediaStoreReceivePublisher(
    private val contentResolver: ContentResolver
) : CompletedFilePublisher {

    override fun publish(completedFile: File, displayName: String, mimeType: String): Boolean {
        if (!completedFile.exists() || completedFile.length() <= 0L) {
            return false
        }
        val collection = collectionFor(mimeType, displayName)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePathFor(mimeType, displayName))
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = try {
            contentResolver.insert(collection, values)
        } catch (_: Exception) {
            null
        } ?: return false

        return try {
            contentResolver.openOutputStream(uri)?.use { output ->
                completedFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: run {
                contentResolver.delete(uri, null, null)
                return false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val published = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                contentResolver.update(uri, published, null, null)
            }
            true
        } catch (_: Exception) {
            try {
                contentResolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            false
        }
    }

    override fun deleteUnpublished(displayName: String, mimeType: String) {
        val collection = collectionFor(mimeType, displayName)
        try {
            contentResolver.delete(
                collection,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(displayName)
            )
        } catch (_: Exception) {
        }
    }

    companion object {
        const val FILO_SUBDIR = "Filo"

        fun collectionFor(mimeType: String, displayName: String): Uri {
            return when (FileMediaType.fromMimeAndName(mimeType, displayName)) {
                FileMediaType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                FileMediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                FileMediaType.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Files.getContentUri("external")
                }
            }
        }

        fun relativePathFor(mimeType: String, displayName: String): String {
            val base = when (FileMediaType.fromMimeAndName(mimeType, displayName)) {
                FileMediaType.IMAGE -> Environment.DIRECTORY_PICTURES
                FileMediaType.VIDEO -> Environment.DIRECTORY_MOVIES
                FileMediaType.AUDIO -> Environment.DIRECTORY_MUSIC
                else -> Environment.DIRECTORY_DOWNLOADS
            }
            return "$base/$FILO_SUBDIR"
        }
    }
}

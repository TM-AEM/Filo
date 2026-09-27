package com.filo.transfer.core.storage.model

import android.net.Uri

/**
 * Immutable model representing a transferable file or media item.
 *
 * Designed to be completely detached from Android UI/Context lifecycles
 * and does not hold open I/O resources or streams.
 */
data class StorageFile(
    val id: String,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val lastModified: Long,
    val mediaType: FileMediaType
) {
    /**
     * True if the file size was successfully determined and is greater than or equal to 0.
     */
    val hasKnownSize: Boolean
        get() = size >= 0L

    companion object {
        const val UNKNOWN_SIZE = -1L
        const val UNKNOWN_TIMESTAMP = 0L
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
    }
}

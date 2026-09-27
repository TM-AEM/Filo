package com.filo.transfer.core.storage.provider

import android.net.Uri
import com.filo.transfer.core.storage.model.FileMediaType
import com.filo.transfer.core.storage.model.StorageFile
import com.filo.transfer.core.storage.model.StorageResult
import java.io.InputStream

/**
 * Unified storage abstraction for Filo file operations.
 *
 * Provides a clean boundary decoupling storage access from network and UI layers.
 */
interface StorageProvider {

    /**
     * Resolves metadata for the given content:// or file:// URI.
     */
    fun getFileMetadata(uri: Uri?): StorageResult<StorageFile>

    /**
     * Opens a streaming [InputStream] for the given URI.
     * The caller assumes ownership and must close the stream.
     */
    fun openInputStream(uri: Uri?): StorageResult<InputStream>

    /**
     * Opens a streaming [InputStream] positioned at the given byte [offset].
     */
    fun openInputStreamAtOffset(uri: Uri?, offset: Long): StorageResult<InputStream>

    /**
     * Checks the seek capability for the given URI.
     */
    fun getSeekCapability(uri: Uri?): SeekCapability

    /**
     * Queries files of a specific [mediaType] from MediaStore with pagination.
     */
    fun queryMedia(
        mediaType: FileMediaType,
        limit: Int = 100,
        offset: Int = 0
    ): StorageResult<List<StorageFile>>

    /**
     * Persists URI read permissions for SAF URIs.
     */
    fun takePersistablePermission(uri: Uri?): StorageResult<Unit>

    /**
     * Releases persisted URI permissions.
     */
    fun releasePersistablePermission(uri: Uri?): StorageResult<Unit>

    /**
     * Checks if persistable read permission is held for the URI.
     */
    fun hasPersistedPermission(uri: Uri?): Boolean
}

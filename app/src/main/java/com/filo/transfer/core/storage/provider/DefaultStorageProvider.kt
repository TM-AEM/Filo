package com.filo.transfer.core.storage.provider

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.filo.transfer.core.storage.model.FileMediaType
import com.filo.transfer.core.storage.model.StorageFile
import com.filo.transfer.core.storage.model.StorageResult
import java.io.InputStream

/**
 * Default implementation of [StorageProvider] backed by Android's ContentResolver,
 * MediaStore, and Storage Access Framework.
 */
class DefaultStorageProvider(
    private val contentResolver: ContentResolver
) : StorageProvider {

    constructor(context: Context) : this(context.applicationContext.contentResolver)

    private val metadataResolver = FileMetadataResolver(contentResolver)
    private val streamProvider = FileStreamProvider(contentResolver)
    private val mediaStoreProvider = MediaStoreProvider(contentResolver)
    private val safManager = SafPermissionManager(contentResolver)

    override fun getFileMetadata(uri: Uri?): StorageResult<StorageFile> {
        return metadataResolver.resolve(uri)
    }

    override fun openInputStream(uri: Uri?): StorageResult<InputStream> {
        return streamProvider.openInputStream(uri)
    }

    override fun openInputStreamAtOffset(uri: Uri?, offset: Long): StorageResult<InputStream> {
        return streamProvider.openInputStreamAtOffset(uri, offset)
    }

    override fun getSeekCapability(uri: Uri?): SeekCapability {
        return streamProvider.getSeekCapability(uri)
    }

    override fun queryMedia(
        mediaType: FileMediaType,
        limit: Int,
        offset: Int
    ): StorageResult<List<StorageFile>> {
        return when (mediaType) {
            FileMediaType.IMAGE -> mediaStoreProvider.queryImages(limit, offset)
            FileMediaType.VIDEO -> mediaStoreProvider.queryVideos(limit, offset)
            FileMediaType.AUDIO -> mediaStoreProvider.queryAudio(limit, offset)
            FileMediaType.DOCUMENT, FileMediaType.ARCHIVE, FileMediaType.APK ->
                mediaStoreProvider.queryDocuments(limit, offset)
            FileMediaType.OTHER -> mediaStoreProvider.queryDocuments(limit, offset)
        }
    }

    override fun takePersistablePermission(uri: Uri?): StorageResult<Unit> {
        return safManager.takePersistablePermission(uri)
    }

    override fun releasePersistablePermission(uri: Uri?): StorageResult<Unit> {
        return safManager.releasePersistablePermission(uri)
    }

    override fun hasPersistedPermission(uri: Uri?): Boolean {
        return safManager.hasPersistedPermission(uri)
    }
}

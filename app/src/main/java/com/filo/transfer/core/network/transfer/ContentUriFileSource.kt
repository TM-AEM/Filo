package com.filo.transfer.core.network.transfer

import android.net.Uri
import com.filo.transfer.core.storage.model.StorageResult
import com.filo.transfer.core.storage.provider.FileStreamProvider
import java.io.IOException
import java.io.InputStream

/**
 * [TransferFileSource] backed by an Android content:// (or file://) [Uri] using [FileStreamProvider].
 *
 * Streams content directly from [android.content.ContentResolver] with zero intermediate buffer copies,
 * and supports seeking to a requested byte offset for resume operations.
 */
class ContentUriFileSource(
    val uri: Uri,
    override val fileId: String,
    override val fileName: String,
    override val size: Long,
    override val mimeType: String = "application/octet-stream",
    override val lastModified: Long = 0L,
    private val streamProvider: FileStreamProvider
) : TransferFileSource {

    override fun openStream(offset: Long): InputStream {
        return when (val result = streamProvider.openInputStreamAtOffset(uri, offset)) {
            is StorageResult.Success -> result.data
            is StorageResult.Failure -> throw IOException(
                "Failed to open stream for URI $uri at offset $offset: ${result.error.message}"
            )
        }
    }
}

package com.filo.transfer.core.storage.model

import android.net.Uri

/**
 * Domain-level error hierarchy for file storage operations.
 * Completely decoupled from Android UI or presentation layers.
 */
sealed interface StorageError {
    val uri: Uri?
    val message: String?
    val cause: Throwable?

    data class InvalidUri(
        override val uri: Uri?,
        override val message: String? = "URI is null, empty, or has an unsupported scheme",
        override val cause: Throwable? = null
    ) : StorageError

    data class PermissionDenied(
        override val uri: Uri,
        override val message: String? = "Access permission denied for URI",
        override val cause: Throwable? = null
    ) : StorageError

    data class FileNotFound(
        override val uri: Uri,
        override val cause: Throwable? = null,
        override val message: String? = cause?.message ?: "File referenced by URI was not found"
    ) : StorageError

    data class ProviderUnavailable(
        override val uri: Uri,
        override val message: String? = "ContentProvider handling the URI is unavailable",
        override val cause: Throwable? = null
    ) : StorageError

    data class MetadataUnavailable(
        override val uri: Uri,
        override val message: String? = "Unable to resolve metadata for URI",
        override val cause: Throwable? = null
    ) : StorageError

    data class OpenFailed(
        override val uri: Uri,
        override val message: String? = "Failed to open input stream for URI",
        override val cause: Throwable? = null
    ) : StorageError

    data class SeekUnsupported(
        override val uri: Uri,
        override val message: String? = "Seeking to requested offset is not supported for this URI",
        override val cause: Throwable? = null
    ) : StorageError

    data class SecurityError(
        override val uri: Uri,
        override val cause: SecurityException? = null,
        override val message: String? = cause?.message ?: "SecurityException while accessing storage URI"
    ) : StorageError

    data class Unknown(
        override val uri: Uri?,
        override val message: String? = "An unexpected storage error occurred",
        override val cause: Throwable? = null
    ) : StorageError
}

package com.filo.transfer.core.storage.provider

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import com.filo.transfer.core.storage.model.StorageError
import com.filo.transfer.core.storage.model.StorageResult

/**
 * Manages persistable URI permissions granted via Storage Access Framework (SAF).
 *
 * Ensures permissions can be taken and released cleanly with proper error handling.
 */
class SafPermissionManager(
    private val contentResolver: ContentResolver
) {

    /**
     * Persists read (and optionally write) permissions for the chosen SAF URI across device reboots.
     */
    fun takePersistablePermission(
        uri: Uri?,
        flags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
    ): StorageResult<Unit> {
        if (uri == null) {
            return StorageResult.Failure(StorageError.InvalidUri(null, "URI cannot be null"))
        }

        return try {
            contentResolver.takePersistableUriPermission(uri, flags)
            StorageResult.Success(Unit)
        } catch (e: SecurityException) {
            StorageResult.Failure(StorageError.PermissionDenied(uri, "Unable to persist permission for URI", e))
        } catch (e: IllegalArgumentException) {
            StorageResult.Failure(StorageError.InvalidUri(uri, e.message, e))
        } catch (e: Exception) {
            StorageResult.Failure(StorageError.Unknown(uri, e.message, e))
        }
    }

    /**
     * Releases previously persisted permissions for the chosen SAF URI.
     */
    fun releasePersistablePermission(
        uri: Uri?,
        flags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
    ): StorageResult<Unit> {
        if (uri == null) {
            return StorageResult.Failure(StorageError.InvalidUri(null, "URI cannot be null"))
        }

        return try {
            contentResolver.releasePersistableUriPermission(uri, flags)
            StorageResult.Success(Unit)
        } catch (e: SecurityException) {
            StorageResult.Failure(StorageError.PermissionDenied(uri, "Failed to release permission for URI", e))
        } catch (e: Exception) {
            StorageResult.Failure(StorageError.Unknown(uri, e.message, e))
        }
    }

    /**
     * Checks if persistable read permission is currently held for the given URI.
     */
    fun hasPersistedPermission(uri: Uri?): Boolean {
        if (uri == null) return false
        return try {
            contentResolver.persistedUriPermissions.any { permission ->
                permission.uri == uri && permission.isReadPermission
            }
        } catch (_: Exception) {
            false
        }
    }
}

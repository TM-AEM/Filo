package com.filo.transfer.core.network.model

/**
 * Metadata entry for a single file included in a [TransferManifest].
 *
 * NOTE: Never contains full local absolute device paths (security rule).
 */
/**
 * @param relativePath Optional location *inside* the destination root for folder transfers,
 * e.g. `"docs/report.pdf"`. Empty means the legacy flat case (file goes directly in the
 * destination root). Always relative and always validated by [RelativePathValidator].
 */
data class ManifestFileItem(
    val fileId: String,
    val fileName: String,
    val size: Long,
    val mimeType: String,
    val lastModified: Long = 0L,
    val relativePath: String = ""
)

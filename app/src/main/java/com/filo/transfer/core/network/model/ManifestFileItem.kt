package com.filo.transfer.core.network.model

/**
 * Metadata entry for a single file included in a [TransferManifest].
 *
 * NOTE: Never contains full local absolute device paths (security rule).
 */
data class ManifestFileItem(
    val fileId: String,
    val fileName: String,
    val size: Long,
    val mimeType: String,
    val lastModified: Long
)

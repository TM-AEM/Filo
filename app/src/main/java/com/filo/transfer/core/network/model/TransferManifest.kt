package com.filo.transfer.core.network.model

/**
 * Manifest describing all files intended for transmission in a session.
 */
data class TransferManifest(
    val transferId: String,
    val senderDeviceName: String,
    val files: List<ManifestFileItem>
) {
    val totalFiles: Int
        get() = files.size

    val totalBytes: Long
        get() = files.sumOf { it.size.coerceAtLeast(0L) }
}

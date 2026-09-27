package com.filo.transfer.core.network.model

/**
 * Real-time progress metric for active file transfer sessions.
 */
data class TransferProgress(
    val currentFileName: String,
    val currentFileIndex: Int,
    val totalFiles: Int,
    val bytesTransferredForCurrentFile: Long,
    val currentFileSize: Long,
    val totalBytesTransferred: Long,
    val totalBytesOverall: Long,
    val speedBytesPerSecond: Long,
    val etaSeconds: Long
) {
    val overallPercentage: Float
        get() = if (totalBytesOverall > 0) {
            ((totalBytesTransferred.toDouble() / totalBytesOverall) * 100).coerceIn(0.0, 100.0).toFloat()
        } else {
            0f
        }

    val currentFilePercentage: Float
        get() = if (currentFileSize > 0) {
            ((bytesTransferredForCurrentFile.toDouble() / currentFileSize) * 100).coerceIn(0.0, 100.0).toFloat()
        } else {
            0f
        }
}

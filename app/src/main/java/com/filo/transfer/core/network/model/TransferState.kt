package com.filo.transfer.core.network.model

/**
 * Deterministic transfer state machine for Filo TCP transfer sessions.
 */
sealed interface TransferState {
    data object Idle : TransferState
    data object Connecting : TransferState
    data object Handshaking : TransferState

    data class Transferring(
        val progress: TransferProgress
    ) : TransferState

    data class Verifying(
        val fileName: String,
        val fileIndex: Int,
        val totalFiles: Int
    ) : TransferState

    data class Completed(
        val totalFiles: Int,
        val totalBytes: Long,
        val durationMs: Long
    ) : TransferState

    data class Paused(
        val progress: TransferProgress
    ) : TransferState

    data object Cancelled : TransferState

    data class Failed(
        val error: NetworkError
    ) : TransferState
}

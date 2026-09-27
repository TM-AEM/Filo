package com.filo.transfer.core.network.model

/**
 * Encapsulates the runtime context and metadata of an active or pending TCP transfer session.
 */
data class TransferSession(
    val sessionId: String,
    val isSender: Boolean,
    val remoteAddress: String,
    val remotePort: Int,
    val peerDeviceName: String,
    val manifest: TransferManifest,
    val protocolVersion: Int
)

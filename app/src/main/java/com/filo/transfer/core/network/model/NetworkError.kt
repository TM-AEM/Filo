package com.filo.transfer.core.network.model

import java.io.IOException

/**
 * Domain-level typed network errors for TCP transfer.
 */
sealed class NetworkError(
    override val message: String,
    override val cause: Throwable? = null
) : IOException(message, cause) {

    data class ConnectionFailed(
        override val message: String,
        override val cause: Throwable? = null
    ) : NetworkError(message, cause)

    data class HandshakeFailed(
        override val message: String,
        override val cause: Throwable? = null
    ) : NetworkError(message, cause)

    data class PeerFingerprintMismatch(
        val expectedFingerprint: String,
        override val message: String = "Peer identity fingerprint does not match expected pin",
        override val cause: Throwable? = null
    ) : NetworkError(message, cause)

    data class ProtocolVersionMismatch(
        val expected: Int,
        val actual: Int,
        override val message: String = "Protocol version mismatch: expected $expected, got $actual",
        override val cause: Throwable? = null
    ) : NetworkError(message, cause)

    data class InvalidFrame(
        override val message: String,
        override val cause: Throwable? = null
    ) : NetworkError(message, cause)

    data class OversizedPayload(
        val length: Int,
        val maxAllowed: Int,
        override val message: String = "Frame payload $length bytes exceeds maximum limit of $maxAllowed bytes",
        override val cause: Throwable? = null
    ) : NetworkError(message, cause)

    data class UnsafeFilename(
        val filename: String,
        override val message: String = "Rejected potentially unsafe filename: '$filename'",
        override val cause: Throwable? = null
    ) : NetworkError(message, cause)

    data class UnsafeRelativePath(
        val relativePath: String,
        override val message: String = "Rejected potentially unsafe relative path: '$relativePath'",
        override val cause: Throwable? = null
    ) : NetworkError(message, cause)

    data class InvalidOffset(
        val offset: Long,
        val fileSize: Long,
        override val message: String = "Invalid resume offset: $offset for file size: $fileSize",
        override val cause: Throwable? = null
    ) : NetworkError(message, cause)

    data class ChecksumMismatch(
        val expected: String,
        val actual: String,
        override val message: String = "Integrity check failed. Expected: $expected, Calculated: $actual",
        override val cause: Throwable? = null
    ) : NetworkError(message, cause)

    data class TransferCancelled(
        override val message: String = "Transfer operation was cancelled by user or peer",
        override val cause: Throwable? = null
    ) : NetworkError(message, cause)

    data class Timeout(
        override val message: String = "Network socket operation timed out",
        override val cause: Throwable? = null
    ) : NetworkError(message, cause)

    data class IoError(
        override val message: String,
        override val cause: Throwable? = null
    ) : NetworkError(message, cause)
}

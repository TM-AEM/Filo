package com.filo.transfer.core.network.security.handshake

/**
 * Sealed error hierarchy for handshake failures.
 *
 * Error messages identify a category only. No cryptographic material
 * (keys, secrets, nonces, signatures) is included in error messages.
 */
sealed class HandshakeError(val category: String) : Exception(category) {

    data object InvalidSignature : HandshakeError("INVALID_SIGNATURE")
    data object InvalidTranscript : HandshakeError("INVALID_TRANSCRIPT")
    data object UnsupportedAlgorithm : HandshakeError("UNSUPPORTED_ALGORITHM")
    data object InvalidPublicKey : HandshakeError("INVALID_PUBLIC_KEY")
    data object KeyAgreementFailed : HandshakeError("KEY_AGREEMENT_FAILED")
    data object InvalidState : HandshakeError("INVALID_STATE")
    data object ReplayDetected : HandshakeError("REPLAY_DETECTED")
    data object MalformedHandshake : HandshakeError("MALFORMED_HANDSHAKE")

    /** Convenience for throwing. */
    fun throwIfNot(value: Boolean, error: HandshakeError) {
        if (!value) throw error
    }
}

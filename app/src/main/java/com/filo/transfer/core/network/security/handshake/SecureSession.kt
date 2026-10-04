package com.filo.transfer.core.network.security.handshake

/**
 * Immutable session object representing a successfully authenticated handshake.
 *
 * Contains only the minimum state required for future transport encryption:
 * - session identifier
 * - peer fingerprint (SHA-256 of the PEER's identity public key)
 * - negotiated key-agreement algorithm
 * - full transcript hash (for audit, not secret)
 * - 3 x 32-byte directional keys for future AES-GCM encryption
 *
 * Does NOT expose raw private keys or the ECDH shared secret.
 */
class SecureSession private constructor(
    val sessionId: String,
    val peerFingerprint: String,
    val keyAgreementAlg: String,
    val transcriptHash: ByteArray,
    val keyA: ByteArray,
    val keyB: ByteArray,
    val bindingKey: ByteArray
) {

    init {
        require(transcriptHash.size == 32) { "transcriptHash must be 32 bytes" }
        require(keyA.size == 32) { "keyA must be 32 bytes" }
        require(keyB.size == 32) { "keyB must be 32 bytes" }
        require(bindingKey.size == 32) { "bindingKey must be 32 bytes" }
    }

    /**
     * Create a SecureSession after a successful authenticated handshake.
     *
     * @param transcriptHash SHA-256 of the full handshake transcript
     * @param peerIdentityPubKey the PEER's identity public key (SPKI bytes)
     * @param keyA first directional session key (32 bytes)
     * @param keyB second directional session key (32 bytes)
     * @param bindingKey session-binding material (32 bytes)
     * @param keyAgreementAlg the agreed key-agreement algorithm name
     */
    companion object {
        fun create(
            transcriptHash: ByteArray,
            peerIdentityPubKey: ByteArray,
            keyA: ByteArray,
            keyB: ByteArray,
            bindingKey: ByteArray,
            keyAgreementAlg: String
        ): SecureSession {
            val peerFingerprint = computeFingerprint(peerIdentityPubKey)
            val sessionId = java.util.UUID.randomUUID().toString()
            return SecureSession(
                sessionId = sessionId,
                peerFingerprint = peerFingerprint,
                keyAgreementAlg = keyAgreementAlg,
                transcriptHash = transcriptHash,
                keyA = keyA,
                keyB = keyB,
                bindingKey = bindingKey
            )
        }

        internal fun computeFingerprint(spki: ByteArray): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            return digest.digest(spki).joinToString("") { "%02x".format(it) }
        }
    }
}

package com.filo.transfer.core.network.security.handshake

/**
 * A single handshake message exchanged between two Filo peers.
 *
 * Each peer creates one message, signs its own transcript, and sends it.
 * The peer then verifies the other side's signature over their transcript.
 */
data class HandshakeMessage(
    val protocolVersion: Byte,
    val role: Byte,
    val idAlgorithm: String,
    val idPublicKey: ByteArray,
    val ephPublicKey: ByteArray,
    val nonce: ByteArray,
    val keyAgreementAlg: String,
    val signature: ByteArray
) {

    /** Build the transcript that was signed to produce [signature]. */
    fun buildTranscript(): HandshakeTranscript {
        return HandshakeTranscript(
            protocolVersion = protocolVersion,
            role = role,
            idAlgorithm = idAlgorithm,
            idPublicKey = idPublicKey,
            ephPublicKey = ephPublicKey,
            nonce = nonce,
            keyAgreementAlg = keyAgreementAlg
        )
    }

    /** Verify the signature against this message's transcript. */
    fun verifySignature(): Boolean {
        val transcript = buildTranscript()
        return IdentityVerifier.verify(
            identityPublicKeySpki = idPublicKey,
            identityAlgorithm = idAlgorithm,
            transcript = transcript.encode(),
            signature = signature
        )
    }
}

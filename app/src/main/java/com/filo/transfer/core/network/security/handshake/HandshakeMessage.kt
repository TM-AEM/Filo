package com.filo.transfer.core.network.security.handshake

/**
 * A single participant's handshake data sent to the peer.
 * No signature here — the signature is over the full transcript,
 * which is constructed after both messages are exchanged.
 */
data class HandshakeMessage(
    val protocolVersion: Byte,
    val role: Byte,
    val idAlgorithm: String,
    val idPublicKey: ByteArray,
    val ephPublicKey: ByteArray,
    val nonce: ByteArray,
    val keyAgreementAlg: String
) {
    /** Convert to the shared [HandshakeData] record used in the full transcript. */
    fun toData(): HandshakeData = HandshakeData(
        role = role,
        idAlgorithm = idAlgorithm,
        idPublicKey = idPublicKey,
        ephPublicKey = ephPublicKey,
        nonce = nonce
    )
}

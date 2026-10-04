package com.filo.transfer.core.network.security.handshake

import com.filo.transfer.core.network.security.crypto.Hkdf
import com.filo.transfer.core.network.security.crypto.SecureRandomWrapper

/**
 * Main handshake protocol logic.
 *
 * Two peers exchange HandshakeMessages, verify each other's identity
 * signatures, compute the ECDH shared secret, and derive directional
 * session keys via HKDF-SHA256.
 *
 * Both sides call [initiate] to create their local message,
 * then [complete] to verify the peer's message and produce a SecureSession.
 *
 * This class is stateless — all state is in the returned objects.
 */
object SecureHandshake {

    const val PROTOCOL_VERSION: Byte = 1
    private const val NONCE_LENGTH = 32

    /**
     * Create the local handshake message as the INITIATOR.
     *
     * @param identity the local signing identity (Keystore-backed in production,
     *                 in-memory P-256 in tests)
     * @return the message to send to the peer
     */
    fun initiate(identity: SigningIdentity): HandshakeMessage {
        val eph = EphemeralKeyPair.generateP256()
        val nonce = SecureRandomWrapper.nextNonce()

        val transcript = HandshakeTranscript(
            protocolVersion = PROTOCOL_VERSION,
            role = 0, // initiator
            idAlgorithm = identity.getIdentityAlgorithm(),
            idPublicKey = identity.getIdentityPublicKey(),
            ephPublicKey = eph.publicKeySpki,
            nonce = nonce,
            keyAgreementAlg = "P-256"
        )
        val signature = identity.signTranscript(transcript.encode())

        return HandshakeMessage(
            protocolVersion = PROTOCOL_VERSION,
            role = 0,
            idAlgorithm = identity.getIdentityAlgorithm(),
            idPublicKey = identity.getIdentityPublicKey(),
            ephPublicKey = eph.publicKeySpki,
            nonce = nonce,
            keyAgreementAlg = "P-256",
            signature = signature
        )
    }

    /**
     * Create the local handshake message as the RESPONDER.
     *
     * @param identity the local signing identity
     * @return the message to send to the peer
     */
    fun respond(identity: SigningIdentity): HandshakeMessage {
        val eph = EphemeralKeyPair.generateP256()
        val nonce = SecureRandomWrapper.nextNonce()

        val transcript = HandshakeTranscript(
            protocolVersion = PROTOCOL_VERSION,
            role = 1, // responder
            idAlgorithm = identity.getIdentityAlgorithm(),
            idPublicKey = identity.getIdentityPublicKey(),
            ephPublicKey = eph.publicKeySpki,
            nonce = nonce,
            keyAgreementAlg = "P-256"
        )
        val signature = identity.signTranscript(transcript.encode())

        return HandshakeMessage(
            protocolVersion = PROTOCOL_VERSION,
            role = 1,
            idAlgorithm = identity.getIdentityAlgorithm(),
            idPublicKey = identity.getIdentityPublicKey(),
            ephPublicKey = eph.publicKeySpki,
            nonce = nonce,
            keyAgreementAlg = "P-256",
            signature = signature
        )
    }

    /**
     * Complete the handshake from the INITIATOR side.
     *
     * @param localMsg the initiator's message (from [initiate])
     * @param peerMsg the responder's message
     * @param localEphemeral the initiator's ephemeral key pair
     * @return the authenticated SecureSession
     */
    fun completeAsInitiator(
        localMsg: HandshakeMessage,
        peerMsg: HandshakeMessage,
        localEphemeral: EphemeralKeyPair
    ): SecureSession {
        verifyPeerMessage(localMsg, peerMsg)
        val sharedSecret = EphemeralKeyPair.sharedSecret(
            localEphemeral.privateKey,
            peerMsg.ephPublicKey,
            "P-256"
        )
        val peerEph = EphemeralKeyPair(
            publicKeySpki = peerMsg.ephPublicKey,
            privateKey = placeholder(), // not used by initiator
            algorithm = "P-256"
        )
        return SecureSession.create(localMsg, peerMsg, sharedSecret, localEphemeral, peerEph)
    }

    /**
     * Complete the handshake from the RESPONDER side.
     *
     * @param localMsg the responder's message (from [respond])
     * @param peerMsg the initiator's message
     * @param localEphemeral the responder's ephemeral key pair
     * @return the authenticated SecureSession
     */
    fun completeAsResponder(
        localMsg: HandshakeMessage,
        peerMsg: HandshakeMessage,
        localEphemeral: EphemeralKeyPair
    ): SecureSession {
        verifyPeerMessage(localMsg, peerMsg)
        val sharedSecret = EphemeralKeyPair.sharedSecret(
            localEphemeral.privateKey,
            peerMsg.ephPublicKey,
            "P-256"
        )
        val peerEph = EphemeralKeyPair(
            publicKeySpki = peerMsg.ephPublicKey,
            privateKey = placeholder(),
            algorithm = "P-256"
        )
        return SecureSession.create(localMsg, peerMsg, sharedSecret, localEphemeral, peerEph)
    }

    /**
     * Verify a peer's message: protocol version, algorithm, signature,
     * and transcript integrity.
     */
    private fun verifyPeerMessage(localMsg: HandshakeMessage, peerMsg: HandshakeMessage) {
        if (peerMsg.protocolVersion != PROTOCOL_VERSION) throw HandshakeError.MalformedHandshake
        if (peerMsg.role == localMsg.role) throw HandshakeError.InvalidState
        if (peerMsg.keyAgreementAlg != localMsg.keyAgreementAlg) throw HandshakeError.UnsupportedAlgorithm
        if (!peerMsg.verifySignature()) throw HandshakeError.InvalidSignature
        if (peerMsg.idAlgorithm != "Ed25519" && peerMsg.idAlgorithm != "SHA256withECDSA") {
            throw HandshakeError.UnsupportedAlgorithm
        }
    }

    /**
     * Generate an ephemeral key pair for use with a handshake message.
     * This is a convenience method that pairs [initiate] or [respond].
     */
    fun generateEphemeral(): EphemeralKeyPair = EphemeralKeyPair.generateP256()

    private fun placeholder(): java.security.Key {
        // This is never used for actual key agreement — it's a placeholder
        // for the peer's ephemeral key which we don't hold the private part of.
        // The actual shared secret is computed by the side that HAS the private key.
        return EphemeralKeyPair.generateP256().privateKey
    }
}

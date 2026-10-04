package com.filo.transfer.core.network.security.handshake

import com.filo.transfer.core.network.security.crypto.Hkdf
import com.filo.transfer.core.network.security.crypto.SecureRandomWrapper

/**
 * State retained by the initiator after calling [SecureHandshake.initiate].
 * Holds the ephemeral private key so it can be reused for completion.
 */
data class HandshakeInitiatorState(
    val identity: SigningIdentity,
    val ephemeral: EphemeralKeyPair,
    val localMessage: HandshakeMessage
)

/**
 * State retained by the responder after calling [SecureHandshake.respond].
 * Holds the ephemeral private key and the full-transcript signature.
 */
data class HandshakeResponderState(
    val identity: SigningIdentity,
    val ephemeral: EphemeralKeyPair,
    val localMessage: HandshakeMessage,
    val fullTranscriptSignature: ByteArray
)

/**
 * Result of [SecureHandshake.completeAsInitiator].
 * Contains the session and the initiator's signature to send back to the peer.
 */
data class InitiatorCompletion(
    val session: SecureSession,
    val signatureToSend: ByteArray
)

/**
 * Main handshake protocol.
 *
 * Protocol flow:
 * 1. Initiator calls [initiate] → sends localMessage to peer
 * 2. Responder calls [respond] with initiator's message → sends localMessage + signature
 * 3. Initiator calls [completeAsInitiator] with state + responder's message + signature
 *    → returns [InitiatorCompletion] with session + initiator's signature to send
 * 4. Responder calls [completeAsResponder] with state + initiator's message + initiator's signature
 *
 * Both peers sign and verify the SAME full transcript (binding both participants).
 */
object SecureHandshake {

    const val PROTOCOL_VERSION: Byte = 1

    // ── Phase 1: Local message creation ─────────────────────────────────

    /**
     * Create the initiator's local handshake data.
     * Generates a fresh ephemeral key pair and nonce.
     *
     * @return state to retain until [completeAsInitiator] is called
     */
    fun initiate(identity: SigningIdentity): HandshakeInitiatorState {
        val eph = EphemeralKeyPair.generateP256()
        val nonce = SecureRandomWrapper.nextNonce()
        val msg = HandshakeMessage(
            protocolVersion = PROTOCOL_VERSION,
            role = 0,
            idAlgorithm = identity.getIdentityAlgorithm(),
            idPublicKey = identity.getIdentityPublicKey(),
            ephPublicKey = eph.publicKeySpki,
            nonce = nonce,
            keyAgreementAlg = "P-256"
        )
        return HandshakeInitiatorState(identity, eph, msg)
    }

    /**
     * Create the responder's local handshake data and sign the full transcript.
     *
     * @param identity the responder's signing identity
     * @param initiatorMsg the initiator's message (received from the wire)
     * @return state with the full-transcript signature to send back
     */
    fun respond(identity: SigningIdentity, initiatorMsg: HandshakeMessage): HandshakeResponderState {
        validateIncomingMessage(initiatorMsg)
        val eph = EphemeralKeyPair.generateP256()
        val nonce = SecureRandomWrapper.nextNonce()
        val localMsg = HandshakeMessage(
            protocolVersion = PROTOCOL_VERSION,
            role = 1,
            idAlgorithm = identity.getIdentityAlgorithm(),
            idPublicKey = identity.getIdentityPublicKey(),
            ephPublicKey = eph.publicKeySpki,
            nonce = nonce,
            keyAgreementAlg = "P-256"
        )
        val fullTranscript = FullHandshakeTranscript(
            protocolVersion = PROTOCOL_VERSION,
            keyAgreementAlg = "P-256",
            initiator = initiatorMsg.toData(),
            responder = localMsg.toData()
        )
        val sig = identity.signTranscript(fullTranscript.encode())
        return HandshakeResponderState(identity, eph, localMsg, sig)
    }

    // ── Phase 2: Completion ────────────────────────────────────────────

    /**
     * Complete the handshake as the INITIATOR.
     *
     * @param state the initiator's state from [initiate]
     * @param peerMsg the responder's message
     * @param peerSig the responder's signature over the full transcript
     * @return [InitiatorCompletion] with the session and the initiator's signature to send back
     */
    fun completeAsInitiator(
        state: HandshakeInitiatorState,
        peerMsg: HandshakeMessage,
        peerSig: ByteArray
    ): InitiatorCompletion {
        validateIncomingMessage(peerMsg)

        val fullTranscript = FullHandshakeTranscript(
            protocolVersion = PROTOCOL_VERSION,
            keyAgreementAlg = "P-256",
            initiator = state.localMessage.toData(),
            responder = peerMsg.toData()
        )

        // Verify peer's signature over the full transcript
        val peerVerified = IdentityVerifier.verify(
            identityPublicKeySpki = peerMsg.idPublicKey,
            identityAlgorithm = peerMsg.idAlgorithm,
            transcript = fullTranscript.encode(),
            signature = peerSig
        )
        if (!peerVerified) throw HandshakeError.InvalidSignature

        // Sign the full transcript with our identity
        val ourSig = state.identity.signTranscript(fullTranscript.encode())

        // ECDH
        val sharedSecret = EphemeralKeyPair.sharedSecret(
            state.ephemeral.privateKey,
            peerMsg.ephPublicKey,
            "P-256"
        )

        val session = buildSession(fullTranscript, sharedSecret, peerMsg.idPublicKey, state.ephemeral)
        return InitiatorCompletion(session, ourSig)
    }

    /**
     * Complete the handshake as the RESPONDER.
     *
     * @param state the responder's state from [respond]
     * @param peerMsg the initiator's message
     * @param peerSig the initiator's signature over the full transcript
     * @return authenticated [SecureSession]
     */
    fun completeAsResponder(
        state: HandshakeResponderState,
        peerMsg: HandshakeMessage,
        peerSig: ByteArray
    ): SecureSession {
        validateIncomingMessage(peerMsg)

        val fullTranscript = FullHandshakeTranscript(
            protocolVersion = PROTOCOL_VERSION,
            keyAgreementAlg = "P-256",
            initiator = peerMsg.toData(),
            responder = state.localMessage.toData()
        )

        // Verify peer's signature over the full transcript
        val peerVerified = IdentityVerifier.verify(
            identityPublicKeySpki = peerMsg.idPublicKey,
            identityAlgorithm = peerMsg.idAlgorithm,
            transcript = fullTranscript.encode(),
            signature = peerSig
        )
        if (!peerVerified) throw HandshakeError.InvalidSignature

        // ECDH
        val sharedSecret = EphemeralKeyPair.sharedSecret(
            state.ephemeral.privateKey,
            peerMsg.ephPublicKey,
            "P-256"
        )

        return buildSession(fullTranscript, sharedSecret, peerMsg.idPublicKey, state.ephemeral)
    }

    // ── Internal helpers ───────────────────────────────────────────────

    private fun buildSession(
        fullTranscript: FullHandshakeTranscript,
        sharedSecret: ByteArray,
        peerIdentityPubKey: ByteArray,
        localEphemeral: EphemeralKeyPair
    ): SecureSession {
        val transcriptHash = fullTranscript.hash()

        // Derive 96 bytes: keyA(32) || keyB(32) || binding(32)
        val material = Hkdf.deriveSessionMaterial(sharedSecret, transcriptHash)
        val keyA = material.copyOfRange(0, 32)
        val keyB = material.copyOfRange(32, 64)
        val binding = material.copyOfRange(64, 96)

        localEphemeral.destroy()

        return SecureSession.create(
            transcriptHash = transcriptHash,
            peerIdentityPubKey = peerIdentityPubKey,
            keyA = keyA,
            keyB = keyB,
            bindingKey = binding,
            keyAgreementAlg = "P-256"
        )
    }

    private fun validateIncomingMessage(msg: HandshakeMessage) {
        if (msg.protocolVersion != PROTOCOL_VERSION) throw HandshakeError.MalformedHandshake
        if (msg.idAlgorithm != "Ed25519" && msg.idAlgorithm != "SHA256withECDSA") {
            throw HandshakeError.UnsupportedAlgorithm
        }
        if (msg.keyAgreementAlg != "P-256") throw HandshakeError.UnsupportedAlgorithm
        if (msg.nonce.size != 32) throw HandshakeError.MalformedHandshake
    }
}

package com.filo.transfer.core.network.security.handshake

import java.io.ByteArrayOutputStream

/**
 * Immutable session object representing a successfully authenticated handshake.
 *
 * Contains only the minimum state required for future transport encryption:
 * - session identifier
 * - peer fingerprint
 * - negotiated algorithm
 * - transcript hash (for audit/debugging, not secret)
 * - directional keys for future AES-GCM encryption
 *
 * Does NOT expose raw private keys or the ECDH shared secret.
 */
class SecureSession private constructor(
    val sessionId: String,
    val peerFingerprint: String,
    val negotiatedAlgorithm: String,
    val transcriptHash: ByteArray,
    val clientToServerKey: ByteArray,
    val serverToClientKey: ByteArray,
    val handshakeKey: ByteArray
) {

    init {
        require(transcriptHash.size == 32) { "transcriptHash must be 32 bytes" }
        require(clientToServerKey.size == 32) { "clientToServerKey must be 32 bytes" }
        require(serverToClientKey.size == 32) { "serverToClientKey must be 32 bytes" }
        require(handshakeKey.size == 32) { "handshakeKey must be 32 bytes" }
    }

    /** Human-readable fingerprint of this session (for logging, not for verification). */
    fun sessionFingerprint(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(sessionId.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    companion object {

        /**
         * Create a SecureSession from both handshake messages and the ECDH shared secret.
         *
         * @param initiatorMsg the initiator's signed message
         * @param responderMsg the responder's signed message
         * @param sharedSecret ECDH shared secret (32 bytes for P-256)
         * @param initiatorKeyPair the initiator's ephemeral key pair (for destroying)
         * @param responderKeyPair the responder's ephemeral key pair (for destroying)
         */
        fun create(
            initiatorMsg: HandshakeMessage,
            responderMsg: HandshakeMessage,
            sharedSecret: ByteArray,
            initiatorKeyPair: EphemeralKeyPair,
            responderKeyPair: EphemeralKeyPair
        ): SecureSession {
            // Validate that roles are complementary (one initiator, one responder)
            if (initiatorMsg.role == responderMsg.role) {
                throw HandshakeError.InvalidState
            }

            // Compute the full transcript hash binding both messages
            val fullTranscript = HkdfSessionContext.encodeBothMessages(initiatorMsg, responderMsg)
            val transcriptHash = fullTranscript.hash()

            // Derive 96 bytes of session material:
            //   clientToServerKey (32) || serverToClientKey (32) || handshakeKey (32)
            val material = com.filo.transfer.core.network.security.crypto.Hkdf
                .deriveSessionMaterial(sharedSecret, transcriptHash)

            val c2s = material.copyOfRange(0, 32)
            val s2c = material.copyOfRange(32, 64)
            val hk = material.copyOfRange(64, 96)

            val sessionId = java.util.UUID.randomUUID().toString()

            // Destroy ephemeral key pairs — they must not be reused
            initiatorKeyPair.destroy()
            responderKeyPair.destroy()

            return SecureSession(
                sessionId = sessionId,
                peerFingerprint = computeFingerprint(responderMsg.idPublicKey),
                negotiatedAlgorithm = initiatorMsg.keyAgreementAlg,
                transcriptHash = transcriptHash,
                clientToServerKey = c2s,
                serverToClientKey = s2c,
                handshakeKey = hk
            )
        }

        private fun computeFingerprint(idPublicKeySpki: ByteArray): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            return digest.digest(idPublicKeySpki)
                .joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Internal helper for encoding both handshake messages into a single
 * full-transcript hash.
 */
internal object HkdfSessionContext {

    fun encodeBothMessages(
        initiator: HandshakeMessage,
        responder: HandshakeMessage
    ): HandshakeTranscript {
        // Use the responder's transcript as the canonical binding:
        // it contains the responder's nonce, which is bound to the
        // full session via the HKDF info string.
        // We combine both transcripts by hashing:
        //   SHA-256(initiator.encode() || responder.encode())
        // This is done via a synthetic transcript that binds both.
        val combined = ByteArrayOutputStream()
        combined.write(initiator.buildTranscript().encode())
        combined.write(responder.buildTranscript().encode())
        val combinedHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(combined.toByteArray())
        // Return a transcript whose hash matches the combined hash
        // (this is an internal helper, the actual hash is computed above)
        return HandshakeTranscript(
            protocolVersion = responder.protocolVersion,
            role = responder.role,
            idAlgorithm = responder.idAlgorithm,
            idPublicKey = responder.idPublicKey,
            ephPublicKey = responder.ephPublicKey,
            nonce = combinedHash.copyOf(32),
            keyAgreementAlg = responder.keyAgreementAlg
        )
    }
}

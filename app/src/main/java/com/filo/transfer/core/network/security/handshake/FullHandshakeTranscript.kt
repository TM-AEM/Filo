package com.filo.transfer.core.network.security.handshake

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * A participant's handshake data (one side of the exchange).
 */
data class HandshakeData(
    val role: Byte,
    val idAlgorithm: String,
    val idPublicKey: ByteArray,
    val ephPublicKey: ByteArray,
    val nonce: ByteArray
)

/**
 * Complete authenticated handshake transcript binding BOTH participants.
 *
 * Canonical encoding (deterministic, unambiguous, role-aware, versioned,
 * length-delimited):
 *
 *   protocolVersion       : 1 byte
 *   keyAgreementAlg       : 4-byte big-endian length + UTF-8 bytes
 *   initiator block:
 *     idAlgorithm         : 4-byte big-endian length + UTF-8
 *     idPublicKey         : 4-byte big-endian length + SPKI
 *     ephPublicKey        : 4-byte big-endian length + bytes
 *     nonce               : 32 bytes (fixed)
 *   responder block:
 *     idAlgorithm         : 4-byte big-endian length + UTF-8
 *     idPublicKey         : 4-byte big-endian length + SPKI
 *     ephPublicKey        : 4-byte big-endian length + bytes
 *     nonce               : 32 bytes (fixed)
 */
data class FullHandshakeTranscript(
    val protocolVersion: Byte,
    val keyAgreementAlg: String,
    val initiator: HandshakeData,
    val responder: HandshakeData
) {

    init {
        require(initiator.role.toInt() == 0) { "Initiator role must be 0, got ${initiator.role}" }
        require(responder.role.toInt() == 1) { "Responder role must be 1, got ${responder.role}" }
    }

    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(protocolVersion.toInt() and 0xFF)

        fun writeLenPrefixed(data: ByteArray) {
            val len = data.size
            out.write(len shr 24 and 0xFF)
            out.write(len shr 16 and 0xFF)
            out.write(len shr 8 and 0xFF)
            out.write(len and 0xFF)
            out.write(data, 0, len)
        }

        writeLenPrefixed(keyAgreementAlg.toByteArray())

        fun writeParticipant(p: HandshakeData) {
            writeLenPrefixed(p.idAlgorithm.toByteArray())
            writeLenPrefixed(p.idPublicKey)
            writeLenPrefixed(p.ephPublicKey)
            require(p.nonce.size == 32) { "Nonce must be 32 bytes, got ${p.nonce.size}" }
            out.write(p.nonce, 0, 32)
        }

        writeParticipant(initiator)
        writeParticipant(responder)

        return out.toByteArray()
    }

    /** SHA-256 of the canonical full-transcript encoding. */
    fun hash(): ByteArray = MessageDigest.getInstance("SHA-256").digest(encode())
}

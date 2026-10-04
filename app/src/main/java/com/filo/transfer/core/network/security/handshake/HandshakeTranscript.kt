package com.filo.transfer.core.network.security.handshake

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Deterministic handshake transcript model.
 *
 * The transcript binds all handshake parameters so that modifying
 * any single field changes the transcript hash.
 *
 * Canonical encoding (length-prefixed, unambiguous):
 *
 *   protocolVersion       : 1 byte
 *   role                  : 1 byte (0=initiator, 1=responder)
 *   idAlgorithm           : 4-byte big-endian length + UTF-8 bytes
 *   idPublicKey           : 4-byte big-endian length + SPKI bytes
 *   ephPublicKey          : 4-byte big-endian length + public key bytes
 *   nonce                 : 32 bytes (fixed)
 *   keyAgreementAlg       : 4-byte big-endian length + UTF-8 bytes
 */
data class HandshakeTranscript(
    val protocolVersion: Byte,
    val role: Byte,
    val idAlgorithm: String,
    val idPublicKey: ByteArray,
    val ephPublicKey: ByteArray,
    val nonce: ByteArray,
    val keyAgreementAlg: String
) {

    /**
     * Canonical binary encoding of this transcript.
     * Deterministic: same inputs always produce the same bytes.
     */
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(protocolVersion.toInt() and 0xFF)
        out.write(role.toInt() and 0xFF)

        fun writeLenPrefixed(data: ByteArray) {
            val len = data.size
            out.write(len shr 24 and 0xFF)
            out.write(len shr 16 and 0xFF)
            out.write(len shr 8 and 0xFF)
            out.write(len and 0xFF)
            out.write(data, 0, len)
        }

        writeLenPrefixed(idAlgorithm.toByteArray())
        writeLenPrefixed(idPublicKey)
        writeLenPrefixed(ephPublicKey)

        require(nonce.size == 32) { "Nonce must be exactly 32 bytes, got ${nonce.size}" }
        out.write(nonce, 0, 32)

        writeLenPrefixed(keyAgreementAlg.toByteArray())

        return out.toByteArray()
    }

    /** SHA-256 of the canonical encoding. */
    fun hash(): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(encode())
    }
}

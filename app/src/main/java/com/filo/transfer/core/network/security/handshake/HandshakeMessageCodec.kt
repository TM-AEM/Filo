package com.filo.transfer.core.network.security.handshake

import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * Byte-level encoder/decoder for [HandshakeMessage] conforming to the
 * Task21G authoritative wire specification.
 *
 * Wire layout (self-describing, length-delimited, big-endian):
 *
 *   protocolVersion : 1 byte
 *   role            : 1 byte
 *   idAlgorithm     : u32be length + UTF-8 bytes
 *   idPublicKey     : u32be length + SPKI bytes
 *   ephPublicKey    : u32be length + SPKI bytes
 *   nonce           : 32 bytes (fixed, no prefix)
 *   keyAgreementAlg : u32be length + UTF-8 bytes
 *
 * Total header without variable fields: 6 bytes (version + role + two u32be prefixes).
 * Fixed content: 32-byte nonce.
 * Minimum payload size: 2(version+role) + 4*4(len prefixes) + 4*1(min alg/key/ag) + 32(nonce) = 53 bytes.
 */
object HandshakeMessageCodec {

    private const val VERSION_OFFSET = 0
    private const val ROLE_OFFSET = 1
    private const val FIXED_NONCE_SIZE = 32
    private const val MINIMUM_PAYLOAD_SIZE = 53

    /**
     * Computes the exact byte size of a [HandshakeMessage] on the wire
     * without allocating the payload buffer.
     *
     * Layout: version(1) + role(1) + len-prefixes(4*4) + idAlg(bytes)
     *         + idPub(bytes) + ephPub(bytes) + nonce(32) + keyAg(bytes)
     */
    fun encodedSize(msg: HandshakeMessage): Int {
        return 2 + 4 * 4 +                          // version + role + 4 length prefixes
                msg.idAlgorithm.toByteArray().size +
                msg.idPublicKey.size + msg.ephPublicKey.size +
                FIXED_NONCE_SIZE + msg.keyAgreementAlg.toByteArray().size
    }

    /**
     * Encodes a [HandshakeMessage] into exact wire bytes.
     *
     * Output is deterministic and byte-identical to
     * [HandshakeTranscript.encode] given identical field values.
     */
    fun encode(msg: HandshakeMessage): ByteArray {
        val out = ByteArray(encodedSize(msg))
        var off = 0

        out[off++] = msg.protocolVersion
        out[off++] = msg.role
        off = writeU32be(off, out, msg.idAlgorithm.toByteArray().size)
        off = writeBytes(off, out, msg.idAlgorithm.toByteArray())
        off = writeU32be(off, out, msg.idPublicKey.size)
        off = writeBytes(off, out, msg.idPublicKey)
        off = writeU32be(off, out, msg.ephPublicKey.size)
        off = writeBytes(off, out, msg.ephPublicKey)
        off = writeBytes(off, out, msg.nonce)
        off = writeU32be(off, out, msg.keyAgreementAlg.toByteArray().size)
        off = writeBytes(off, out, msg.keyAgreementAlg.toByteArray())

        require(off == out.size) { "encode size mismatch" }
        return out
    }

    /**
     * Decodes [bytes] into a [HandshakeMessage].
     *
     * Rejects truncated input, invalid length prefixes, unsupported
     * algorithms, invalid nonce size, empty keys, and trailing bytes.
     */
    fun decode(bytes: ByteArray): HandshakeMessage {
        if (bytes.size < MINIMUM_PAYLOAD_SIZE) throw HandshakeError.MalformedHandshake

        var off = 0

        // protocolVersion
        if (bytes.size < 1) throw HandshakeError.MalformedHandshake
        val protocolVersion = bytes[off++]
        if (protocolVersion != SecureHandshake.PROTOCOL_VERSION) {
            throw HandshakeError.MalformedHandshake
        }

        // role
        if (bytes.size < 2) throw HandshakeError.MalformedHandshake
        val role = bytes[off++]
        if (role.toInt() != 0 && role.toInt() != 1) {
            throw HandshakeError.MalformedHandshake
        }

        // idAlgorithm
        if (bytes.size - off < 4) throw HandshakeError.MalformedHandshake
        val idAlgorithmLen = readU32be(off, bytes); off += 4
        if (idAlgorithmLen < 0 || idAlgorithmLen > bytes.size - off) throw HandshakeError.MalformedHandshake
        val idAlgorithm = parseUtf8(off, bytes, idAlgorithmLen); off += idAlgorithmLen
        if (idAlgorithm != "Ed25519" && idAlgorithm != "SHA256withECDSA") {
            throw HandshakeError.UnsupportedAlgorithm
        }

        // idPublicKey
        if (bytes.size - off < 4) throw HandshakeError.MalformedHandshake
        val idPubLen = readU32be(off, bytes); off += 4
        if (idPubLen < 0 || idPubLen > bytes.size - off) throw HandshakeError.MalformedHandshake
        val idPublicKey = bytes.copyOfRange(off, off + idPubLen); off += idPubLen
        if (idPublicKey.isEmpty()) throw HandshakeError.MalformedHandshake

        // ephPublicKey
        if (bytes.size - off < 4) throw HandshakeError.MalformedHandshake
        val ephPubLen = readU32be(off, bytes); off += 4
        if (ephPubLen < 0 || ephPubLen > bytes.size - off) throw HandshakeError.MalformedHandshake
        val ephPublicKey = bytes.copyOfRange(off, off + ephPubLen); off += ephPubLen
        if (ephPublicKey.isEmpty()) throw HandshakeError.MalformedHandshake

        // nonce (fixed 32 bytes, no prefix)
        if (bytes.size - off < FIXED_NONCE_SIZE) throw HandshakeError.MalformedHandshake
        val nonce = bytes.copyOfRange(off, off + FIXED_NONCE_SIZE); off += FIXED_NONCE_SIZE

        // keyAgreementAlg
        if (bytes.size - off < 4) throw HandshakeError.MalformedHandshake
        val keyAgAlgLen = readU32be(off, bytes); off += 4
        if (keyAgAlgLen < 0 || keyAgAlgLen > bytes.size - off) throw HandshakeError.MalformedHandshake
        val keyAgreementAlg = parseUtf8(off, bytes, keyAgAlgLen); off += keyAgAlgLen
        if (keyAgreementAlg != "P-256") {
            throw HandshakeError.UnsupportedAlgorithm
        }

        // No trailing bytes allowed
        if (off != bytes.size) throw HandshakeError.MalformedHandshake

        return HandshakeMessage(
            protocolVersion = protocolVersion,
            role = role,
            idAlgorithm = idAlgorithm,
            idPublicKey = idPublicKey,
            ephPublicKey = ephPublicKey,
            nonce = nonce,
            keyAgreementAlg = keyAgreementAlg
        )
    }

    private fun writeU32be(offset: Int, buf: ByteArray, value: Int): Int {
        buf[offset] = (value shr 24).toByte()
        buf[offset + 1] = (value shr 16).toByte()
        buf[offset + 2] = (value shr 8).toByte()
        buf[offset + 3] = value.toByte()
        return offset + 4
    }

    private fun writeBytes(offset: Int, buf: ByteArray, data: ByteArray): Int {
        data.copyInto(buf, offset)
        return offset + data.size
    }

    private fun readU32be(offset: Int, bytes: ByteArray): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun parseUtf8(offset: Int, bytes: ByteArray, len: Int): String {
        return String(bytes, offset, len, Charsets.UTF_8)
    }
}

package com.filo.transfer.core.network.security.handshake

/**
 * Wire-payload assembly and splitting for the Task 21G handshake frames.
 *
 * Task 21G framing:
 * - D-HELLO (0x01): payload = encoded [HandshakeMessage] (role 0)
 * - D-ACK   (0x02): payload = encoded [HandshakeMessage] (role 1) || raw signature
 * - D-FIN   (0x03): payload = raw signature only
 *
 * The D-ACK split is computed by walking the self-describing D-HM layout
 * (length-prefixed fields). No brute-force guessing of the message boundary
 * is performed: a walk that leaves trailing bytes is validated by
 * [HandshakeMessageCodec.decode], which rejects both the prefix mismatch and
 * any trailing garbage.
 */
object HandshakeFraming {

    private const val HELLO_ROLE: Int = 0
    private const val HELLO_ACK_ROLE: Int = 1
    private const val FIXED_NONCE_SIZE = 32

    /** Assembles the D-HELLO payload from the initiator's local message. */
    fun helloPayload(message: HandshakeMessage): ByteArray {
        require(message.role.toInt() == HELLO_ROLE) { "HELLO message role must be 0" }
        return HandshakeMessageCodec.encode(message)
    }

    /** Decodes and role-validates a D-HELLO payload. */
    fun decodeHello(payload: ByteArray): HandshakeMessage {
        val msg = HandshakeMessageCodec.decode(payload)
        if (msg.role.toInt() != HELLO_ROLE) throw HandshakeError.MalformedHandshake
        return msg
    }

    /** Assembles the D-ACK payload: encoded responder message followed by the raw signature. */
    fun helloAckPayload(message: HandshakeMessage, signature: ByteArray): ByteArray {
        require(message.role.toInt() == HELLO_ACK_ROLE) { "HELLO_ACK message role must be 1" }
        require(signature.isNotEmpty()) { "HELLO_ACK signature must not be empty" }
        return HandshakeMessageCodec.encode(message) + signature
    }

    /** Splits a D-ACK payload into the encoded message and the raw signature. */
    fun decodeHelloAck(payload: ByteArray): HandshakeSplit {
        val messageSize = selfDescribingMessageSize(payload)
        val signature = payload.copyOfRange(messageSize, payload.size)
        if (signature.isEmpty()) throw HandshakeError.MalformedHandshake
        val msg = HandshakeMessageCodec.decode(payload.copyOfRange(0, messageSize))
        if (msg.role.toInt() != HELLO_ACK_ROLE) throw HandshakeError.MalformedHandshake
        return HandshakeSplit(msg, signature)
    }

    /** Extracts the raw signature from a D-FIN payload. */
    fun helloFinishSignature(payload: ByteArray): ByteArray {
        if (payload.isEmpty()) throw HandshakeError.MalformedHandshake
        return payload
    }

    /** Result of splitting a D-ACK payload. */
    data class HandshakeSplit(val message: HandshakeMessage, val signature: ByteArray)

    /**
     * Walks the self-describing D-HM layout and returns the byte offset at the
     * end of the encoded message (i.e. the signature start position).
     *
     * Layout: version(1) role(1) then three u32be length-prefixed fields
     * (idAlgorithm, idPublicKey, ephPublicKey), the fixed 32-byte nonce, and
     * one more u32be length-prefixed field (keyAgreementAlg).
     */
    private fun selfDescribingMessageSize(bytes: ByteArray): Int {
        var off = 2
        if (bytes.size < off) throw HandshakeError.MalformedHandshake

        repeat(3) {
            val len = readU32be(bytes, off)
            off += 4
            if (len < 0 || bytes.size - off < len) throw HandshakeError.MalformedHandshake
            off += len
        }

        if (bytes.size - off < FIXED_NONCE_SIZE) throw HandshakeError.MalformedHandshake
        off += FIXED_NONCE_SIZE

        val len = readU32be(bytes, off)
        off += 4
        if (len < 0 || bytes.size - off < len) throw HandshakeError.MalformedHandshake
        off += len

        return off
    }

    private fun readU32be(bytes: ByteArray, off: Int): Int {
        if (bytes.size - off < 4) throw HandshakeError.MalformedHandshake
        return ((bytes[off].toInt() and 0xFF) shl 24) or
                ((bytes[off + 1].toInt() and 0xFF) shl 16) or
                ((bytes[off + 2].toInt() and 0xFF) shl 8) or
                (bytes[off + 3].toInt() and 0xFF)
    }
}

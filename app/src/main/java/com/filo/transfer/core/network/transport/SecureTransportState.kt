package com.filo.transfer.core.network.transport

import com.filo.transfer.core.network.protocol.FrameType
import com.filo.transfer.core.network.security.crypto.CryptoDirection
import com.filo.transfer.core.network.security.crypto.CryptoReceiveSequence
import com.filo.transfer.core.network.security.crypto.CryptoSequence
import com.filo.transfer.core.network.security.crypto.SecureFrameCodec
import com.filo.transfer.core.network.security.handshake.SecureSession

/**
 * Per-connection secure-transport state, created only after a successful
 * [com.filo.transfer.core.network.security.handshake.SecureHandshake].
 *
 * Owns the directional session keys and the monotonic crypto sequence state
 * for both directions. It is destroyed together with the connection; it is
 * never reused across connections.
 *
 * Key mapping (Task 21A / Task 21G):
 * - Initiator: TX = keyA with direction INITIATOR_TO_RESPONDER,
 *              RX = keyB with direction RESPONDER_TO_INITIATOR.
 * - Responder: TX = keyB with direction RESPONDER_TO_INITIATOR,
 *              RX = keyA with direction INITIATOR_TO_RESPONDER.
 *
 * Both directions start at CRYPTO_SEQ = 0, independent of the application
 * `ProtocolFrame.sequence` carried inside the encrypted payload.
 */
class SecureTransportState private constructor(
    val session: SecureSession,
    private val txKey: ByteArray,
    private val rxKey: ByteArray,
    private val txDirection: CryptoDirection,
    private val rxDirection: CryptoDirection,
    val txSequence: CryptoSequence,
    val rxSequence: CryptoReceiveSequence
) {

    /**
     * Encrypts and authenticates [plaintext] as a secure frame of [type].
     * Consumes exactly one crypto sequence number before encryption.
     */
    fun encodeFrame(plaintext: ByteArray, type: FrameType): ByteArray =
        SecureFrameCodec.encode(plaintext, type, txKey, txSequence, txDirection)

    /**
     * Validates, authenticates, and decrypts a secure frame.
     * Advances the receive sequence only after successful GCM authentication.
     */
    fun decodeFrame(frame: ByteArray): SecureFrameCodec.DecodedFrame =
        SecureFrameCodec.decode(frame, rxKey, rxSequence, rxDirection)

    companion object {

        /** State for the INITIATOR side of a completed handshake. */
        fun forInitiator(session: SecureSession): SecureTransportState =
            SecureTransportState(
                session = session,
                txKey = session.keyA,
                rxKey = session.keyB,
                txDirection = CryptoDirection.INITIATOR_TO_RESPONDER,
                rxDirection = CryptoDirection.RESPONDER_TO_INITIATOR,
                txSequence = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER),
                rxSequence = CryptoReceiveSequence(CryptoDirection.RESPONDER_TO_INITIATOR)
            )

        /** State for the RESPONDER side of a completed handshake. */
        fun forResponder(session: SecureSession): SecureTransportState =
            SecureTransportState(
                session = session,
                txKey = session.keyB,
                rxKey = session.keyA,
                txDirection = CryptoDirection.RESPONDER_TO_INITIATOR,
                rxDirection = CryptoDirection.INITIATOR_TO_RESPONDER,
                txSequence = CryptoSequence(CryptoDirection.RESPONDER_TO_INITIATOR),
                rxSequence = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
            )
    }
}

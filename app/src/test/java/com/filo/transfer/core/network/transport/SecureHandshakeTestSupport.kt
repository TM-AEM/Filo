package com.filo.transfer.core.network.transport

import com.filo.transfer.core.network.model.NetworkError
import com.filo.transfer.core.network.protocol.FrameType
import com.filo.transfer.core.network.security.handshake.HandshakeError
import com.filo.transfer.core.network.security.handshake.HandshakeFraming
import com.filo.transfer.core.network.security.handshake.SecureHandshake
import com.filo.transfer.core.network.security.handshake.SigningIdentity
import java.security.GeneralSecurityException

/**
 * Test helper that performs the Task 21G crypto handshake as the INITIATOR over a raw
 * [SocketConnection]. The peer (responder side) must run the responder flow, typically
 * [com.filo.transfer.core.network.transfer.TransferReceiver].receive().
 *
 * Used by tests that hand-drive the application frames while the receiver engine performs
 * its own crypto handshake.
 */
object SecureHandshakeTestSupport {

    /**
     * Runs the full initiator handshake: D-HELLO -> D-ACK -> D-FIN, then enters secure
     * mode on [conn]. Throws [NetworkError.HandshakeFailed] on any verification failure.
     */
    fun initiateAsClient(conn: SocketConnection, identity: SigningIdentity) {
        val initiatorState = try {
            SecureHandshake.initiate(identity)
        } catch (e: GeneralSecurityException) {
            throw NetworkError.HandshakeFailed("Identity key generation unavailable", e)
        }

        conn.sendHandshakeFrame(FrameType.HELLO, HandshakeFraming.helloPayload(initiatorState.localMessage))

        val ackFrame = conn.receiveHandshakeFrame()
        if (ackFrame.type != FrameType.HELLO_ACK) {
            throw NetworkError.HandshakeFailed("Expected HELLO_ACK frame, got: ${ackFrame.type}")
        }
        val split = try {
            HandshakeFraming.decodeHelloAck(ackFrame.payload)
        } catch (e: HandshakeError) {
            throw NetworkError.HandshakeFailed("Malformed HELLO_ACK payload", e)
        }

        val completion = try {
            SecureHandshake.completeAsInitiator(initiatorState, split.message, split.signature)
        } catch (e: HandshakeError) {
            throw NetworkError.HandshakeFailed("Initiator handshake verification failed: ${e.category}", e)
        } catch (e: GeneralSecurityException) {
            throw NetworkError.HandshakeFailed("Cryptographic operation failed during handshake", e)
        }

        conn.sendHandshakeFrame(FrameType.HANDSHAKE_FINISH, completion.signatureToSend)
        conn.enterSecure(SecureTransportState.forInitiator(completion.session))
    }
}

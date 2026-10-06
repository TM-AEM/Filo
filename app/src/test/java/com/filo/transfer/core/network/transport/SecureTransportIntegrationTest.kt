package com.filo.transfer.core.network.transport

import com.filo.transfer.core.network.model.NetworkError
import com.filo.transfer.core.network.model.TransferManifest
import com.filo.transfer.core.network.model.ManifestFileItem
import com.filo.transfer.core.network.protocol.FrameCodec
import com.filo.transfer.core.network.protocol.FrameType
import com.filo.transfer.core.network.protocol.ProtocolFrame
import com.filo.transfer.core.network.security.crypto.AesGcmException
import com.filo.transfer.core.network.security.crypto.SecureFrameCodec
import com.filo.transfer.core.network.security.handshake.HandshakeError
import com.filo.transfer.core.network.security.handshake.HandshakeFraming
import com.filo.transfer.core.network.security.handshake.InMemorySigningIdentity
import com.filo.transfer.core.network.security.handshake.SecureHandshake
import com.filo.transfer.core.network.security.handshake.SigningIdentity
import com.filo.transfer.core.network.transfer.ChecksumCalculator
import com.filo.transfer.core.network.transfer.LocalFileSource
import com.filo.transfer.core.network.transfer.TransferReceiver
import com.filo.transfer.core.network.transfer.TransferSender
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/**
 * Task 21F-4: verifies the production secure transport integration.
 *
 * Covers:
 * - crypto handshake over real TCP sockets, both sides entering SECURE mode
 * - first secure frame carries CRYPTO_SEQ = 0; sequence advances expected+1
 * - initiator/responder use opposite keys and directions (Task 21A mapping)
 * - application ProtocolFrame.sequence is independent of CRYPTO_SEQ
 * - all application frame types round-trip through the secure transport
 * - plaintext frames after SECURE are rejected (no bypass, no fallback, fail-close)
 * - tampered ciphertext / out-of-order / replayed secure frames are rejected and close
 * - a corrupted signature fails the handshake without ever entering SECURE mode
 * - handshake frame types are forbidden after session start
 * - end-to-end engine transfer over the secure transport with in-memory identities
 */
class SecureTransportIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var serverTransport: TcpServerTransport? = null

    @After
    fun tearDown() {
        try {
            serverTransport?.close()
        } catch (_: Throwable) {}
        serverTransport = null
    }

    private class SecurePair(
        val clientConn: SocketConnection,
        val serverConn: SocketConnection,
        val clientState: SecureTransportState,
        val serverState: SecureTransportState,
        val rawClient: Socket,
        val rawServer: Socket
    )

    private fun socketPair(): Pair<Socket, Socket> {
        val server = ServerSocket(0)
        val client = Socket("127.0.0.1", server.localPort)
        val accepted = server.accept()
        server.close()
        return client to accepted
    }

    /**
     * Runs the full Task 21G handshake over the wire (D-HELLO, D-ACK, D-FIN),
     * enters secure mode on both connections, and returns the resulting states.
     */
    private fun handshakeOverWire(clientIdentity: SigningIdentity, serverIdentity: SigningIdentity): SecurePair {
        val (rawClient, rawServer) = socketPair()
        val clientConn = SocketConnection(rawClient)
        val serverConn = SocketConnection(rawServer)

        // 1. Initiator: D-HELLO
        val initiatorState = SecureHandshake.initiate(clientIdentity)
        clientConn.sendHandshakeFrame(FrameType.HELLO, HandshakeFraming.helloPayload(initiatorState.localMessage))

        // 2. Responder: read D-HELLO, respond with D-ACK
        val helloFrame = serverConn.receiveHandshakeFrame()
        assertEquals(FrameType.HELLO, helloFrame.type)
        val initiatorMsg = HandshakeFraming.decodeHello(helloFrame.payload)
        val responderState = SecureHandshake.respond(serverIdentity, initiatorMsg)
        serverConn.sendHandshakeFrame(
            FrameType.HELLO_ACK,
            HandshakeFraming.helloAckPayload(responderState.localMessage, responderState.fullTranscriptSignature)
        )

        // 3. Initiator: read D-ACK, complete, send D-FIN
        val ackFrame = clientConn.receiveHandshakeFrame()
        assertEquals(FrameType.HELLO_ACK, ackFrame.type)
        val split = HandshakeFraming.decodeHelloAck(ackFrame.payload)
        val completion = SecureHandshake.completeAsInitiator(initiatorState, split.message, split.signature)
        clientConn.sendHandshakeFrame(FrameType.HANDSHAKE_FINISH, completion.signatureToSend)

        // 4. Responder: read D-FIN, complete
        val finFrame = serverConn.receiveHandshakeFrame()
        assertEquals(FrameType.HANDSHAKE_FINISH, finFrame.type)
        val serverSession = SecureHandshake.completeAsResponder(
            responderState,
            initiatorMsg,
            HandshakeFraming.helloFinishSignature(finFrame.payload)
        )

        // Both sides derive identical directional session keys
        assertEquals(serverSession.keyA.toList(), completion.session.keyA.toList())
        assertEquals(serverSession.keyB.toList(), completion.session.keyB.toList())

        val clientState = SecureTransportState.forInitiator(completion.session)
        val serverState = SecureTransportState.forResponder(serverSession)
        clientConn.enterSecure(clientState)
        serverConn.enterSecure(serverState)

        return SecurePair(clientConn, serverConn, clientState, serverState, rawClient, rawServer)
    }

    /** Serializes a ProtocolFrame exactly as it would appear inside the secure plaintext. */
    private fun innerFrameBytes(type: FrameType, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        FrameCodec.writeFrame(out, ProtocolFrame(type = type, payload = payload))
        return out.toByteArray()
    }

    private inline fun <reified T : Throwable> expectThrows(action: () -> Unit): T {
        try {
            action()
        } catch (e: Throwable) {
            if (e is T) return e
            fail("Expected ${T::class.simpleName} but got ${e::class.simpleName}: ${e.message}")
        }
        fail("Expected ${T::class.simpleName} but nothing was thrown")
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    @Test
    fun testHandshakeCompletesAndBothSidesEnterSecureMode() {
        val pair = handshakeOverWire(InMemorySigningIdentity.generate(), InMemorySigningIdentity.generate())

        assertTrue(pair.clientConn.isSecure)
        assertTrue(pair.serverConn.isSecure)
        assertEquals(0L, pair.clientState.rxSequence.currentExpected)
        assertEquals(0L, pair.serverState.rxSequence.currentExpected)

        pair.clientConn.close()
        pair.serverConn.close()
    }

    @Test
    fun testFirstSecureFrameUsesCryptoSequenceZeroAndAdvancesMonotonically() {
        val pair = handshakeOverWire(InMemorySigningIdentity.generate(), InMemorySigningIdentity.generate())

        val payload = byteArrayOf(1, 2, 3)
        pair.clientConn.sendFrame(ProtocolFrame(type = FrameType.MANIFEST, payload = payload))
        val rx1 = pair.serverConn.receiveFrame()
        assertEquals(FrameType.MANIFEST, rx1.type)
        assertTrue(rx1.payload.contentEquals(payload))
        // The receive side accepts strictly expected+1: after one frame it must expect 1.
        assertEquals(1L, pair.serverState.rxSequence.currentExpected)

        pair.clientConn.sendFrame(ProtocolFrame(type = FrameType.MANIFEST, payload = payload))
        val rx2 = pair.serverConn.receiveFrame()
        assertEquals(FrameType.MANIFEST, rx2.type)
        assertEquals(2L, pair.serverState.rxSequence.currentExpected)

        // Reverse direction: the responder's first secure frame must also carry CRYPTO_SEQ 0.
        pair.serverConn.sendFrame(ProtocolFrame(type = FrameType.MANIFEST_ACK, payload = payload))
        val rx3 = pair.clientConn.receiveFrame()
        assertEquals(FrameType.MANIFEST_ACK, rx3.type)
        assertEquals(1L, pair.clientState.rxSequence.currentExpected)

        pair.clientConn.close()
        pair.serverConn.close()
    }

    @Test
    fun testDirectionalKeysAreAsymmetric() {
        val pair = handshakeOverWire(InMemorySigningIdentity.generate(), InMemorySigningIdentity.generate())

        // Correct directional pairing (initiator TX keyA/INITIATOR_TO_RESPONDER seq0 ->
        // responder RX keyA/INITIATOR_TO_RESPONDER expecting 0) decodes successfully.
        val frameOk = pair.clientState.encodeFrame(byteArrayOf(7, 7), FrameType.MANIFEST)
        val decoded = pair.serverState.decodeFrame(frameOk)
        assertTrue(decoded.plaintext.contentEquals(byteArrayOf(7, 7)))
        assertEquals(1L, pair.serverState.rxSequence.currentExpected)

        // Wrong directional key: a frame encrypted with keyA/INITIATOR_TO_RESPONDER
        // must FAIL GCM authentication on a receive state using keyB/RESPONDER_TO_INITIATOR
        // (the initiator's RX configuration). Fresh states keep crypto sequences aligned at 0.
        val wrongTx = SecureTransportState.forInitiator(pair.clientState.session)
        val wrongFrame = wrongTx.encodeFrame(byteArrayOf(9, 9), FrameType.MANIFEST)
        val wrongRx = SecureTransportState.forInitiator(pair.clientState.session)
        expectThrows<AesGcmException> {
            wrongRx.decodeFrame(wrongFrame)
        }

        pair.clientConn.close()
        pair.serverConn.close()
    }

    @Test
    fun testProtocolFrameSequenceIsIndependentFromCryptoSequence() {
        val pair = handshakeOverWire(InMemorySigningIdentity.generate(), InMemorySigningIdentity.generate())

        val payload = ByteArray(64) { 5 }
        pair.clientConn.sendFrame(ProtocolFrame(type = FrameType.DATA_CHUNK, sequence = 4096L, payload = payload))
        val rx1 = pair.serverConn.receiveFrame()
        assertEquals(4096L, rx1.sequence)
        assertEquals(1L, pair.serverState.rxSequence.currentExpected)

        pair.clientConn.sendFrame(ProtocolFrame(type = FrameType.DATA_CHUNK, sequence = 4097L, payload = payload))
        val rx2 = pair.serverConn.receiveFrame()
        assertEquals(4097L, rx2.sequence)
        assertEquals(2L, pair.serverState.rxSequence.currentExpected)

        pair.clientConn.close()
        pair.serverConn.close()
    }

    @Test
    fun testAllApplicationFrameTypesRoundTripThroughSecureTransport() {
        val pair = handshakeOverWire(InMemorySigningIdentity.generate(), InMemorySigningIdentity.generate())

        val appTypes = listOf(
            FrameType.MANIFEST,
            FrameType.MANIFEST_ACK,
            FrameType.FILE_HEADER,
            FrameType.RESUME_REQUEST,
            FrameType.RESUME_RESPONSE,
            FrameType.DATA_CHUNK,
            FrameType.CHECKSUM,
            FrameType.CHECKSUM_RESULT,
            FrameType.PAUSE,
            FrameType.RESUME,
            FrameType.CANCEL,
            FrameType.ERROR,
            FrameType.COMPLETE
        )

        for (type in appTypes) {
            val payload = byteArrayOf(1, 2, 3)
            pair.clientConn.sendFrame(ProtocolFrame(type = type, payload = payload))
            val rx = pair.serverConn.receiveFrame()
            assertEquals("Client->server round-trip failed for $type", type, rx.type)
            assertTrue(rx.payload.contentEquals(payload))
        }

        for (type in appTypes) {
            val payload = byteArrayOf(4, 5, 6)
            pair.serverConn.sendFrame(ProtocolFrame(type = type, payload = payload))
            val rx = pair.clientConn.receiveFrame()
            assertEquals("Server->client round-trip failed for $type", type, rx.type)
            assertTrue(rx.payload.contentEquals(payload))
        }

        pair.clientConn.close()
        pair.serverConn.close()
    }

    @Test
    fun testPlaintextFrameAfterSecureModeIsRejectedAndConnectionClosed() {
        val pair = handshakeOverWire(InMemorySigningIdentity.generate(), InMemorySigningIdentity.generate())

        // A VER=1 plaintext application frame written after SECURE must be rejected:
        // no plaintext bypass, no fallback to plaintext parsing.
        val plaintext = innerFrameBytes(FrameType.MANIFEST, byteArrayOf(8, 8))
        pair.rawClient.getOutputStream().write(plaintext)
        pair.rawClient.getOutputStream().flush()

        val error = expectThrows<NetworkError> {
            pair.serverConn.receiveFrame()
        }
        assertTrue("Expected ProtocolVersionMismatch, got: ${error::class.simpleName}",
            error is NetworkError.ProtocolVersionMismatch)
        assertTrue("Connection must be closed after plaintext bypass attempt", pair.serverConn.isClosed)
    }

    @Test
    fun testTamperedCiphertextIsRejectedAndConnectionClosed() {
        val pair = handshakeOverWire(InMemorySigningIdentity.generate(), InMemorySigningIdentity.generate())

        val frame = pair.clientState.encodeFrame(innerFrameBytes(FrameType.MANIFEST, byteArrayOf(3, 3)), FrameType.MANIFEST)
        // Corrupt the first ciphertext byte; GCM authentication must fail.
        frame[SecureFrameCodec.HEADER_SIZE] = (frame[SecureFrameCodec.HEADER_SIZE].toInt() xor 0xFF).toByte()
        pair.rawClient.getOutputStream().write(frame)
        pair.rawClient.getOutputStream().flush()

        val error = expectThrows<NetworkError> {
            pair.serverConn.receiveFrame()
        }
        assertTrue("Expected InvalidFrame (auth failure), got: ${error::class.simpleName}",
            error is NetworkError.InvalidFrame)
        assertTrue("Connection must be closed after authentication failure", pair.serverConn.isClosed)
        // Authentication failure must not advance the receive sequence.
        assertEquals(0L, pair.serverState.rxSequence.currentExpected)
    }

    @Test
    fun testOutOfOrderCryptoSequenceIsRejectedAndConnectionClosed() {
        val pair = handshakeOverWire(InMemorySigningIdentity.generate(), InMemorySigningIdentity.generate())

        val frame0 = pair.clientState.encodeFrame(innerFrameBytes(FrameType.MANIFEST, byteArrayOf(1)), FrameType.MANIFEST)
        val frame1 = pair.clientState.encodeFrame(innerFrameBytes(FrameType.MANIFEST, byteArrayOf(2)), FrameType.MANIFEST)

        // Write frame1 (CRYPTO_SEQ=1) while the receiver expects CRYPTO_SEQ=0.
        pair.rawClient.getOutputStream().write(frame1)
        pair.rawClient.getOutputStream().flush()

        val error = expectThrows<NetworkError> {
            pair.serverConn.receiveFrame()
        }
        assertTrue("Expected InvalidFrame (sequence violation), got: ${error::class.simpleName}",
            error is NetworkError.InvalidFrame)
        assertTrue(pair.serverConn.isClosed)
        // frame0 is never consumed; sequence state must be untouched.
        assertEquals(0L, pair.serverState.rxSequence.currentExpected)
        // frame0 itself is now unrecoverable for the closed session; the discarded
        // bytes are only meaningful for documentation of the out-of-order rejection.
        assertTrue(frame0.isNotEmpty())
    }

    @Test
    fun testReplayedSecureFrameIsRejectedAndConnectionClosed() {
        val pair = handshakeOverWire(InMemorySigningIdentity.generate(), InMemorySigningIdentity.generate())

        val frame = pair.clientState.encodeFrame(innerFrameBytes(FrameType.MANIFEST, byteArrayOf(1)), FrameType.MANIFEST)

        // First delivery: accepted (CRYPTO_SEQ 0).
        pair.rawClient.getOutputStream().write(frame)
        pair.rawClient.getOutputStream().flush()
        val ok = pair.serverConn.receiveFrame()
        assertEquals(FrameType.MANIFEST, ok.type)

        // Replay of the same frame: rejected by the strict expected+1 rule.
        pair.rawClient.getOutputStream().write(frame)
        pair.rawClient.getOutputStream().flush()
        val error = expectThrows<NetworkError> {
            pair.serverConn.receiveFrame()
        }
        assertTrue(error is NetworkError.InvalidFrame)
        assertTrue(pair.serverConn.isClosed)
    }

    @Test
    fun testCorruptSignatureFailsHandshakeWithoutEnteringSecureMode() {
        val (rawClient, rawServer) = socketPair()
        val clientConn = SocketConnection(rawClient)
        val serverConn = SocketConnection(rawServer)

        val clientIdentity = InMemorySigningIdentity.generate()
        val serverIdentity = InMemorySigningIdentity.generate()

        val initiatorState = SecureHandshake.initiate(clientIdentity)
        clientConn.sendHandshakeFrame(FrameType.HELLO, HandshakeFraming.helloPayload(initiatorState.localMessage))

        val helloFrame = serverConn.receiveHandshakeFrame()
        val initiatorMsg = HandshakeFraming.decodeHello(helloFrame.payload)
        val responderState = SecureHandshake.respond(serverIdentity, initiatorMsg)
        serverConn.sendHandshakeFrame(
            FrameType.HELLO_ACK,
            HandshakeFraming.helloAckPayload(responderState.localMessage, responderState.fullTranscriptSignature)
        )

        val ackFrame = clientConn.receiveHandshakeFrame()
        val split = HandshakeFraming.decodeHelloAck(ackFrame.payload)
        val corruptedSig = split.signature.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0xFF).toByte() }

        // The initiator must reject the corrupted signature (InvalidSignature).
        expectThrows<HandshakeError> {
            SecureHandshake.completeAsInitiator(initiatorState, split.message, corruptedSig)
        }

        // No session was established on either side; no application frames are allowed.
        assertFalse(clientConn.isSecure)
        assertFalse(serverConn.isSecure)

        clientConn.close()
        serverConn.close()
    }

    @Test
    fun testHandshakeFrameTypesAreForbiddenAfterSessionStart() {
        val pair = handshakeOverWire(InMemorySigningIdentity.generate(), InMemorySigningIdentity.generate())

        // Sending a handshake frame after SECURE fails closed and closes the connection.
        expectThrows<NetworkError> {
            pair.clientConn.sendHandshakeFrame(FrameType.HELLO, ByteArray(0))
        }
        assertTrue(pair.clientConn.isClosed)

        // A fresh pair: receiving a handshake frame after SECURE also fails closed.
        val pair2 = handshakeOverWire(InMemorySigningIdentity.generate(), InMemorySigningIdentity.generate())
        expectThrows<NetworkError> {
            pair2.serverConn.receiveHandshakeFrame()
        }
        assertTrue(pair2.serverConn.isClosed)

        pair2.clientConn.close()
    }

    @Test
    fun testHandshakeFinishForbiddenInPostSessionSecureFrames() {
        val pair = handshakeOverWire(InMemorySigningIdentity.generate(), InMemorySigningIdentity.generate())

        // Encoding a 0x03 HANDSHAKE_FINISH through the post-session secure codec
        // fails closed and closes the connection (Task 21G post-session rule).
        expectThrows<NetworkError> {
            pair.clientConn.sendFrame(ProtocolFrame(type = FrameType.HANDSHAKE_FINISH, payload = ByteArray(1)))
        }
        assertTrue(pair.clientConn.isClosed)
        pair.serverConn.close()
    }

    @Test
    fun testEnteringSecureModeTwiceFails() {
        val pair = handshakeOverWire(InMemorySigningIdentity.generate(), InMemorySigningIdentity.generate())

        expectThrows<NetworkError> {
            pair.clientConn.enterSecure(SecureTransportState.forResponder(pair.clientState.session))
        }
        assertTrue(pair.clientConn.isClosed)

        pair.serverConn.close()
    }

    @Test
    fun testEngineTransferOverSecureTransport() {
        val sourceDir = tempFolder.newFolder("secureSource")
        val destDir = tempFolder.newFolder("secureDest")
        val file = java.io.File(sourceDir, "secure_test.bin")
        file.writeBytes(ByteArray(48 * 1024) { (it % 251).toByte() })
        val originalSha = file.inputStream().use { ChecksumCalculator.calculate(it) }

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val sender = TransferSender(
            deviceName = "SecureSender",
            signingIdentity = InMemorySigningIdentity.generate()
        )
        val receiver = TransferReceiver(
            deviceName = "SecureReceiver",
            signingIdentity = InMemorySigningIdentity.generate()
        )

        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "SecureSender",
            files = listOf(
                ManifestFileItem("sec1", file.name, file.length(), "application/octet-stream", file.lastModified())
            )
        )

        runBlocking {
            val receiverDeferred = async {
                val serverConn = server.accept()
                receiver.receive(serverConn, destDir)
            }
            val clientConn = TcpClientTransport.connect("127.0.0.1", port)
            val sendRes = sender.transfer(clientConn, manifest, mapOf("sec1" to LocalFileSource(file, "sec1")))
            val recvRes = receiverDeferred.await()

            assertTrue("Sender failed over secure transport: ${sendRes.exceptionOrNull()}", sendRes.isSuccess)
            assertTrue("Receiver failed over secure transport: ${recvRes.exceptionOrNull()}", recvRes.isSuccess)

            val received = java.io.File(destDir, file.name)
            assertTrue(received.exists())
            assertEquals(file.length(), received.length())
            assertEquals(originalSha, received.inputStream().use { ChecksumCalculator.calculate(it) })
        }

        server.close()
        serverTransport = null
    }

    /**
     * F-04: a truncated secure stream over TCP must fail closed quickly, not hang.
     * After one secure frame is delivered, the peer is closed abruptly; the next
     * secure-frame read must return a NetworkError (EOF) instead of blocking.
     */
    @Test
    fun testTruncatedSecureFrameOverTcpFailsClosedWithoutHanging() {
        val pair = handshakeOverWire(InMemorySigningIdentity.generate(), InMemorySigningIdentity.generate())

        pair.clientConn.sendFrame(ProtocolFrame(type = FrameType.MANIFEST, payload = byteArrayOf(9, 9, 7)))
        // Abruptly drop the connection mid-session (no final frame, no close handshake).
        pair.clientConn.close()

        val reader = Thread {
            try {
                pair.serverConn.receiveFrame()
            } catch (_: NetworkError) {
                // expected EOF / I/O error on the truncated secure stream
            }
        }
        reader.start()
        reader.join(5_000)
        assertFalse("server receiveFrame hung on the truncated secure stream", reader.isAlive)
        pair.serverConn.close()
    }

    /**
     * F-04: two consecutive, independent secure transfers over the SAME bound server
     * port both succeed, proving per-connection crypto state is reset (no cross-
     * connection key / sequence reuse).
     */
    @Test
    fun testConsecutiveSecureTransfersOverSameServerPort() {
        val sourceDir = tempFolder.newFolder("ccSource")
        val destDir = tempFolder.newFolder("ccDest")
        val f1 = java.io.File(sourceDir, "cc_a.bin")
        f1.writeBytes(ByteArray(32 * 1024) { (it % 251).toByte() })
        val f2 = java.io.File(sourceDir, "cc_b.bin")
        f2.writeBytes(ByteArray(48 * 1024) { (it % 199).toByte() })

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val sender = TransferSender(
            deviceName = "CCSender",
            signingIdentity = InMemorySigningIdentity.generate()
        )
        val receiver = TransferReceiver(
            deviceName = "CCReceiver",
            signingIdentity = InMemorySigningIdentity.generate()
        )

        fun transferOnce(itemKey: String, file: java.io.File) {
            val manifest = TransferManifest(
                transferId = UUID.randomUUID().toString(),
                senderDeviceName = "CCSender",
                files = listOf(
                    ManifestFileItem(itemKey, file.name, file.length(), "application/octet-stream", file.lastModified())
                )
            )
            runBlocking {
                val receiverDeferred = async {
                    val serverConn = server.accept()
                    receiver.receive(serverConn, destDir)
                }
                val clientConn = TcpClientTransport.connect("127.0.0.1", port)
                val sendRes = sender.transfer(clientConn, manifest, mapOf(itemKey to LocalFileSource(file, itemKey)))
                val recvRes = receiverDeferred.await()

                assertTrue("Sender failed for $itemKey: ${sendRes.exceptionOrNull()}", sendRes.isSuccess)
                assertTrue("Receiver failed for $itemKey: ${recvRes.exceptionOrNull()}", recvRes.isSuccess)

                val received = java.io.File(destDir, file.name)
                assertEquals(file.length(), received.length())
            }
        }

        transferOnce("cc_a", f1)
        transferOnce("cc_b", f2) // second, fully independent connection on the same port

        server.close()
        serverTransport = null
    }
}

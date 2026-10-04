package com.filo.transfer.core.network.transfer

import com.filo.transfer.core.network.model.ManifestFileItem
import com.filo.transfer.core.network.model.NetworkError
import com.filo.transfer.core.network.model.TransferManifest
import com.filo.transfer.core.network.model.TransferState
import com.filo.transfer.core.network.protocol.FramePayloads
import com.filo.transfer.core.network.protocol.FrameType
import com.filo.transfer.core.network.protocol.ProtocolConstants
import com.filo.transfer.core.network.protocol.ProtocolFrame
import com.filo.transfer.core.network.transport.SocketConnection
import com.filo.transfer.core.network.transport.TcpClientTransport
import com.filo.transfer.core.network.transport.TcpServerTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * Task 16: Verifies that TransferReceiver rejects manifests with excessive
 * file counts or oversized individual files BEFORE any transfer work begins.
 */
class TransferReceiverManifestLimitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var destDir: File
    private var serverTransport: TcpServerTransport? = null

    @Before
    fun setUp() {
        destDir = tempFolder.newFolder("destination")
    }

    @After
    fun tearDown() {
        try {
            serverTransport?.close()
        } catch (_: Throwable) {}
        serverTransport = null
    }

    private fun partialFile(name: String): File {
        return File(destDir, "$name${ProtocolConstants.PARTIAL_FILE_SUFFIX}")
    }

    private suspend fun startReceiverAndSendManifest(
        manifest: TransferManifest,
        clientConn: SocketConnection
    ): Pair<TransferReceiver, kotlinx.coroutines.Deferred<Result<TransferManifest>>> = runBlocking {
        withContext(Dispatchers.IO) {
            val server = TcpServerTransport()
            serverTransport = server
            val port = server.bind(0)
            val receiver = TransferReceiver("LimitReceiver")

            val receiverDeferred = async(Dispatchers.IO) {
                val serverConn = server.accept()
                receiver.receive(serverConn, destDir, allowResume = false)
            }

            // Complete handshake
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.HELLO,
                    payload = FramePayloads.encodeHello(
                        ProtocolConstants.CURRENT_PROTOCOL_VERSION,
                        "LimitSender",
                        "limit-session"
                    )
                )
            )
            clientConn.receiveFrame() // HELLO_ACK

            // Send manifest
            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest))
            )

            receiver to receiverDeferred
        }
    }

    @Test
    fun testExcessiveFileCountRejected() = runBlocking<Unit> {
        // Create manifest with MAX_FILE_COUNT + 1 files
        val fileCount = ProtocolConstants.MAX_FILE_COUNT + 1
        val files = (1..fileCount).map { i ->
            ManifestFileItem("file-$i", "f$i.bin", 1024L, "application/octet-stream", System.currentTimeMillis())
        }
        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "LimitSender",
            files = files
        )

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)
        val receiver = TransferReceiver("LimitReceiver")

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)
        withContext(Dispatchers.IO) {
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.HELLO,
                    payload = FramePayloads.encodeHello(
                        ProtocolConstants.CURRENT_PROTOCOL_VERSION,
                        "LimitSender",
                        "limit-session"
                    )
                )
            )
            clientConn.receiveFrame() // HELLO_ACK
            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest))
            )
            // Should receive rejection MANIFEST_ACK
            val ackFrame = clientConn.receiveFrame()
            assertEquals(FrameType.MANIFEST_ACK, ackFrame.type)
        }

        withTimeout(5_000) { receiverDeferred.await() }

        // Receiver must fail
        assertTrue(
            "Receiver must reach Failed state, got: ${receiver.state.value}",
            receiver.state.value is TransferState.Failed
        )
        val error = (receiver.state.value as? TransferState.Failed)?.error
        assertTrue(
            "Error must be OversizedPayload; got: ${error?.javaClass?.simpleName} - ${error?.message}",
            error is NetworkError.OversizedPayload
        )
        // No partial files created
        assertFalse("No .filo.part file should exist", destDir.listFiles()?.any { it.name.endsWith(ProtocolConstants.PARTIAL_FILE_SUFFIX) } == true)
        clientConn.close()
    }

    @Test
    fun testOversizedIndividualFileRejected() = runBlocking<Unit> {
        val oversizedSize = ProtocolConstants.MAX_FILE_SIZE_BYTES + 1L
        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "LimitSender",
            files = listOf(
                ManifestFileItem("big1", "big.bin", oversizedSize, "application/octet-stream", System.currentTimeMillis())
            )
        )

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)
        val receiver = TransferReceiver("LimitReceiver")

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)
        withContext(Dispatchers.IO) {
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.HELLO,
                    payload = FramePayloads.encodeHello(
                        ProtocolConstants.CURRENT_PROTOCOL_VERSION,
                        "LimitSender",
                        "limit-session"
                    )
                )
            )
            clientConn.receiveFrame() // HELLO_ACK
            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest))
            )
            val ackFrame = clientConn.receiveFrame()
            assertEquals(FrameType.MANIFEST_ACK, ackFrame.type)
        }

        withTimeout(5_000) { receiverDeferred.await() }

        assertTrue(
            "Receiver must reach Failed state, got: ${receiver.state.value}",
            receiver.state.value is TransferState.Failed
        )
        val error = (receiver.state.value as? TransferState.Failed)?.error
        assertTrue(
            "Error must be OversizedPayload; got: ${error?.javaClass?.simpleName} - ${error?.message}",
            error is NetworkError.OversizedPayload
        )
        assertFalse("No .filo.part file should exist", partialFile("big.bin").exists())
        clientConn.close()
    }

    @Test
    fun testExactMaximumFileSizeAccepted() = runBlocking<Unit> {
        val exactSize = ProtocolConstants.MAX_FILE_SIZE_BYTES
        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "LimitSender",
            files = listOf(
                ManifestFileItem("exact1", "exact.bin", exactSize, "application/octet-stream", System.currentTimeMillis())
            )
        )

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)
        val receiver = TransferReceiver("LimitReceiver")

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)
        withContext(Dispatchers.IO) {
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.HELLO,
                    payload = FramePayloads.encodeHello(
                        ProtocolConstants.CURRENT_PROTOCOL_VERSION,
                        "LimitSender",
                        "limit-session"
                    )
                )
            )
            clientConn.receiveFrame() // HELLO_ACK
            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest))
            )
            // Should receive ACCEPTED MANIFEST_ACK
            val ackFrame = clientConn.receiveFrame()
            assertEquals(FrameType.MANIFEST_ACK, ackFrame.type)
            val ackPayload = FramePayloads.decodeManifestAck(ackFrame.payload)
            assertTrue(
                "Manifest with exact MAX_FILE_SIZE_BYTES must be accepted, got accepted=${ackPayload.accepted} reason=${ackPayload.reason}",
                ackPayload.accepted
            )
        }

        // The receiver is now waiting for FILE_HEADER. Close connection to end.
        clientConn.close()
        withTimeout(5_000) {
            try {
                receiverDeferred.await()
            } catch (_: Throwable) {
                // Expected: connection closed while waiting for FILE_HEADER
            }
        }
    }

    @Test
    fun testExactMaximumFileCountAccepted() = runBlocking<Unit> {
        val fileCount = ProtocolConstants.MAX_FILE_COUNT
        val files = (1..fileCount).map { i ->
            ManifestFileItem("f$i", "f$i.bin", 1024L, "application/octet-stream", System.currentTimeMillis())
        }
        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "LimitSender",
            files = files
        )

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)
        val receiver = TransferReceiver("LimitReceiver")

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)
        withContext(Dispatchers.IO) {
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.HELLO,
                    payload = FramePayloads.encodeHello(
                        ProtocolConstants.CURRENT_PROTOCOL_VERSION,
                        "LimitSender",
                        "limit-session"
                    )
                )
            )
            clientConn.receiveFrame() // HELLO_ACK
            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest))
            )
            val ackFrame = clientConn.receiveFrame()
            assertEquals(FrameType.MANIFEST_ACK, ackFrame.type)
            val ackPayload = FramePayloads.decodeManifestAck(ackFrame.payload)
            assertTrue(
                "Manifest with exactly MAX_FILE_COUNT files must be accepted, got accepted=${ackPayload.accepted} reason=${ackPayload.reason}",
                ackPayload.accepted
            )
        }

        clientConn.close()
        withTimeout(5_000) {
            try {
                receiverDeferred.await()
            } catch (_: Throwable) {
            }
        }
    }

    @Test
    fun testNegativeFileSizeRejected() = runBlocking<Unit> {
        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "LimitSender",
            files = listOf(
                ManifestFileItem("neg1", "neg.bin", -1L, "application/octet-stream", System.currentTimeMillis())
            )
        )

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)
        val receiver = TransferReceiver("LimitReceiver")

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)
        withContext(Dispatchers.IO) {
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.HELLO,
                    payload = FramePayloads.encodeHello(
                        ProtocolConstants.CURRENT_PROTOCOL_VERSION,
                        "LimitSender",
                        "limit-session"
                    )
                )
            )
            clientConn.receiveFrame() // HELLO_ACK
            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest))
            )
            // Negative size is rejected at the decoder level (decodeManifest throws InvalidFrame),
            // so the receiver sends an ERROR frame, not MANIFEST_ACK.
            val errorFrame = clientConn.receiveFrame()
            assertEquals(FrameType.ERROR, errorFrame.type)
        }

        withTimeout(5_000) { receiverDeferred.await() }

        assertTrue(
            "Receiver must reach Failed state for negative size, got: ${receiver.state.value}",
            receiver.state.value is TransferState.Failed
        )
        val error = (receiver.state.value as? TransferState.Failed)?.error
        assertTrue(
            "Error must be InvalidFrame for negative size; got: ${error?.javaClass?.simpleName} - ${error?.message}",
            error is NetworkError.InvalidFrame
        )
        assertFalse("No .filo.part file should exist", partialFile("neg.bin").exists())
        clientConn.close()
    }
}

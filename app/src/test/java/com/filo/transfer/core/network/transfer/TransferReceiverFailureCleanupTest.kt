package com.filo.transfer.core.network.transfer

import com.filo.transfer.core.network.model.ManifestFileItem
import com.filo.transfer.core.network.model.NetworkError
import com.filo.transfer.core.network.model.TransferManifest
import com.filo.transfer.core.network.model.TransferState
import com.filo.transfer.core.network.protocol.FramePayloads
import com.filo.transfer.core.network.protocol.FrameType
import com.filo.transfer.core.network.protocol.ProtocolConstants
import com.filo.transfer.core.network.protocol.ProtocolFrame
import com.filo.transfer.core.network.security.handshake.InMemorySigningIdentity
import com.filo.transfer.core.network.security.handshake.SigningIdentity
import com.filo.transfer.core.network.transport.SecureHandshakeTestSupport
import com.filo.transfer.core.network.transport.SocketConnection
import com.filo.transfer.core.network.transport.TcpClientTransport
import com.filo.transfer.core.network.transport.TcpServerTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Task 13: Verifies that TransferReceiver cleans up orphaned .filo.part files
 * on all confirmed failure paths (peer disconnect, invalid frame, I/O failure,
 * pause->disconnect, checksum mismatch) while preserving D5/D6/D7 invariants.
 */
class TransferReceiverFailureCleanupTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var sourceDir: File
    private lateinit var destDir: File
    private var serverTransport: TcpServerTransport? = null

    @Before
    fun setUp() {
        sourceDir = tempFolder.newFolder("source")
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

    private fun activePartialField(receiver: TransferReceiver): File? {
        val field = TransferReceiver::class.java.getDeclaredField("activePartialFile")
        field.isAccessible = true
        return field.get(receiver) as File?
    }

    private suspend fun handshakeAndStartFile(
        clientConn: SocketConnection,
        identity: SigningIdentity,
        fileName: String,
        fileSize: Long,
        fileId: String = "fcl"
    ) = withContext(Dispatchers.IO) {
        SecureHandshakeTestSupport.initiateAsClient(clientConn, identity)

        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "FclSender",
            files = listOf(
                ManifestFileItem(fileId, fileName, fileSize, "application/octet-stream", System.currentTimeMillis())
            )
        )
        clientConn.sendFrame(
            ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest))
        )
        val manifestAck = clientConn.receiveFrame()
        assertEquals(FrameType.MANIFEST_ACK, manifestAck.type)

        clientConn.sendFrame(
            ProtocolFrame(
                type = FrameType.FILE_HEADER,
                payload = FramePayloads.encodeFileHeader(0, 1, fileId, fileName, fileSize, "application/octet-stream")
            )
        )
        val resumeReq = clientConn.receiveFrame()
        assertEquals(FrameType.RESUME_REQUEST, resumeReq.type)

        clientConn.sendFrame(
            ProtocolFrame(
                type = FrameType.RESUME_RESPONSE,
                payload = FramePayloads.encodeResumeResponse(fileId, 0L, true)
            )
        )
    }

    private suspend fun sendChunks(
        clientConn: SocketConnection,
        count: Int,
        chunkSize: Int = 4096
    ) = withContext(Dispatchers.IO) {
        repeat(count) { i ->
            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.DATA_CHUNK, sequence = (i.toLong() * chunkSize), payload = ByteArray(chunkSize) { 1 })
            )
        }
    }

    private suspend fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) {
                delay(10)
            }
        }
    }

    @Test
    fun testPeerDisconnectCleansPartialFile() = runBlocking<Unit> {
        val fileName = "peer_disc.bin"
        val fileSize = 200_000L
        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)
        val receiver = TransferReceiver("PeerDiscReceiver", InMemorySigningIdentity.generate())

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)
        handshakeAndStartFile(clientConn, InMemorySigningIdentity.generate(), fileName, fileSize)
        sendChunks(clientConn, 4)

        waitUntil {
            partialFile(fileName).exists() && receiver.state.value is TransferState.Transferring
        }
        assertTrue("Partial file must exist during transfer", partialFile(fileName).exists())

        // Simulate peer disconnect
        clientConn.close()

        withTimeout(5_000) { receiverDeferred.await() }

        assertTrue(
            "Receiver must reach Failed state, got: ${receiver.state.value}",
            receiver.state.value is TransferState.Failed
        )
        val error = (receiver.state.value as? TransferState.Failed)?.error
        assertTrue(
            "Error must be connection-related; got: ${error?.javaClass?.simpleName} - ${error?.message}",
            error is NetworkError.ConnectionFailed || error is NetworkError.Timeout || error is NetworkError.IoError
        )

        // Task 13 assertion: partial file must NOT remain
        assertFalse(
            "Partial .filo.part must be deleted on peer disconnect",
            partialFile(fileName).exists()
        )
        assertFalse("Final file must NOT exist", File(destDir, fileName).exists())
        assertNull("activePartialFile must be cleared", activePartialField(receiver))
    }

    @Test
    fun testInvalidFrameCleansPartialFile() = runBlocking<Unit> {
        val fileName = "invalid_frame.bin"
        val fileSize = 200_000L
        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)
        val receiver = TransferReceiver("InvalidFrameReceiver", InMemorySigningIdentity.generate())

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)
        handshakeAndStartFile(clientConn, InMemorySigningIdentity.generate(), fileName, fileSize)
        sendChunks(clientConn, 4)

        waitUntil {
            partialFile(fileName).exists() && receiver.state.value is TransferState.Transferring
        }
        assertTrue("Partial file must exist during transfer", partialFile(fileName).exists())

        // Send an ERROR frame while DATA_CHUNK is expected
        withContext(Dispatchers.IO) {
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.ERROR,
                    payload = FramePayloads.encodeControlMessage("Simulated error")
                )
            )
        }

        withTimeout(5_000) { receiverDeferred.await() }

        assertTrue(
            "Receiver must reach Failed state, got: ${receiver.state.value}",
            receiver.state.value is TransferState.Failed
        )
        val error = (receiver.state.value as? TransferState.Failed)?.error
        assertTrue(
            "Error must be InvalidFrame; got: ${error?.javaClass?.simpleName} - ${error?.message}",
            error is NetworkError.InvalidFrame
        )

        // Task 13 assertion: partial file must NOT remain
        assertFalse(
            "Partial .filo.part must be deleted on invalid frame",
            partialFile(fileName).exists()
        )
        assertFalse("Final file must NOT exist", File(destDir, fileName).exists())
        assertNull("activePartialFile must be cleared", activePartialField(receiver))
    }

    @Test
    fun testIoFailureCleansPartialFile() = runBlocking<Unit> {
        val fileName = "io_fail.bin"
        val fileSize = 200_000L

        // Create a DIRECTORY with the partial file name to force FileOutputStream failure
        val partialPath = File(destDir, "$fileName${ProtocolConstants.PARTIAL_FILE_SUFFIX}")
        assertTrue("Setup: create directory as partial file", partialPath.mkdirs())

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)
        val receiver = TransferReceiver("IoFailReceiver", InMemorySigningIdentity.generate())

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)
        handshakeAndStartFile(clientConn, InMemorySigningIdentity.generate(), fileName, fileSize)

        withTimeout(5_000) { receiverDeferred.await() }

        assertTrue(
            "Receiver must reach Failed state, got: ${receiver.state.value}",
            receiver.state.value is TransferState.Failed
        )
        val error = (receiver.state.value as? TransferState.Failed)?.error
        assertTrue(
            "Error must be I/O related; got: ${error?.javaClass?.simpleName} - ${error?.message}",
            error is NetworkError.IoError
        )

        // The directory still exists (we can't delete it via File.delete() on a non-empty dir,
        // but activePartialFile should be null since FileOutputStream failed before assignment)
        assertNull("activePartialFile must be null after I/O failure", activePartialField(receiver))
        assertFalse("Final file must NOT exist", File(destDir, fileName).exists())

        // Clean up the directory for test teardown
        partialPath.delete()
    }

    @Test
    fun testPauseThenDisconnectCleansPartialFile() = runBlocking<Unit> {
        val fileName = "pause_disc.bin"
        val fileSize = 200_000L
        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)
        val receiver = TransferReceiver("PauseDiscReceiver", InMemorySigningIdentity.generate())

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)
        handshakeAndStartFile(clientConn, InMemorySigningIdentity.generate(), fileName, fileSize)
        sendChunks(clientConn, 4)

        waitUntil {
            partialFile(fileName).exists() && receiver.state.value is TransferState.Transferring
        }
        assertTrue("Partial file must exist during transfer", partialFile(fileName).exists())

        // Pause the receiver
        receiver.pause()
        waitUntil { receiver.state.value is TransferState.Paused }
        assertTrue("Receiver must be paused", receiver.state.value is TransferState.Paused)

        // Disconnect peer while paused
        clientConn.close()

        // Resume so the streaming loop can detect the disconnect
        receiver.resume()

        withTimeout(5_000) { receiverDeferred.await() }

        assertTrue(
            "Receiver must reach Failed state after pause->disconnect, got: ${receiver.state.value}",
            receiver.state.value is TransferState.Failed
        )

        // Task 13 assertion: partial file must NOT remain
        assertFalse(
            "Partial .filo.part must be deleted on pause->disconnect",
            partialFile(fileName).exists()
        )
        assertFalse("Final file must NOT exist", File(destDir, fileName).exists())
        assertNull("activePartialFile must be cleared", activePartialField(receiver))
    }

    @Test
    fun testChecksumMismatchCleansPartialFile() = runBlocking<Unit> {
        val fileName = "checksum_fail.bin"
        val fileSize = 8 * 1024L
        val payload = ByteArray(fileSize.toInt()) { (it % 251).toByte() }

        // Pre-create a valid final file to verify it's untouched
        val existingFile = File(destDir, "existing_final.bin")
        existingFile.writeBytes(ByteArray(1024) { 7 })

        // Custom sender that sends correct data but WRONG checksum
        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)
        val receiver = TransferReceiver("ChecksumFailReceiver", InMemorySigningIdentity.generate())

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)
        // Handshake + manifest + file header + resume response
        withContext(Dispatchers.IO) {
            SecureHandshakeTestSupport.initiateAsClient(clientConn, InMemorySigningIdentity.generate())

            val manifest = TransferManifest(
                transferId = UUID.randomUUID().toString(),
                senderDeviceName = "CsSender",
                files = listOf(
                    ManifestFileItem("cs1", fileName, fileSize, "application/octet-stream", System.currentTimeMillis())
                )
            )
            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest))
            )
            clientConn.receiveFrame() // MANIFEST_ACK

            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.FILE_HEADER,
                    payload = FramePayloads.encodeFileHeader(0, 1, "cs1", fileName, fileSize, "application/octet-stream")
                )
            )
            clientConn.receiveFrame() // RESUME_REQUEST

            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.RESUME_RESPONSE,
                    payload = FramePayloads.encodeResumeResponse("cs1", 0L, true)
                )
            )

            // Send all data
            val chunkSize = 4096
            var offset = 0L
            while (offset < fileSize) {
                val toSend = minOf(chunkSize.toLong(), fileSize - offset).toInt()
                clientConn.sendFrame(
                    ProtocolFrame(
                        type = FrameType.DATA_CHUNK,
                        sequence = offset,
                        payload = payload.copyOfRange(offset.toInt(), (offset + toSend).toInt())
                    )
                )
                offset += toSend
            }

            // Send WRONG checksum
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.CHECKSUM,
                    payload = FramePayloads.encodeChecksum("cs1", "0000000000000000000000000000000000000000000000000000000000000000")
                )
            )

            // Receive CHECKSUM_RESULT (should indicate mismatch)
            val resultFrame = clientConn.receiveFrame()
            assertEquals(FrameType.CHECKSUM_RESULT, resultFrame.type)
        }

        withTimeout(5_000) { receiverDeferred.await() }

        assertTrue(
            "Receiver must reach Failed state on checksum mismatch, got: ${receiver.state.value}",
            receiver.state.value is TransferState.Failed
        )
        val error = (receiver.state.value as? TransferState.Failed)?.error
        assertTrue(
            "Error must be ChecksumMismatch; got: ${error?.javaClass?.simpleName}",
            error is NetworkError.ChecksumMismatch
        )

        // Partial must be deleted
        assertFalse(
            "Partial .filo.part must be deleted on checksum mismatch",
            partialFile(fileName).exists()
        )
        // Final file must NOT exist
        assertFalse("Final file must NOT exist after checksum mismatch", File(destDir, fileName).exists())
        // Existing final file must be untouched
        assertTrue("Existing final file must be preserved", existingFile.exists())
        assertEquals(1024, existingFile.length())
        // activePartialFile must be cleared
        assertNull("activePartialFile must be null after checksum mismatch", activePartialField(receiver))

        clientConn.close()
    }
}

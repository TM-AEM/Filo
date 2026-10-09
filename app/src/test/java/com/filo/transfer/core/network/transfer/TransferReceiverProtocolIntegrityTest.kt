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
import com.filo.transfer.core.network.transport.SecureHandshakeTestSupport
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
 * Task 22 — Protocol-Integrity Tests for TransferReceiver.
 *
 * Verifies every mandatory validation introduced in this task:
 *   - FILE_HEADER cross-check against the corresponding manifest entry
 *   - Zero-length DATA_CHUNK rejection
 *   - Strict oversized-DATA_CHUNK pre-write guard
 *   - CHECKSUM.fileId validation
 *   - COMPLETE payload cross-check against manifest/session state
 *   - Duplicate manifest fileId rejection
 *   - Final byte-count verification before completion
 */
class TransferReceiverProtocolIntegrityTest {

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

    private fun partialFile(name: String): File =
        File(destDir, "$name${ProtocolConstants.PARTIAL_FILE_SUFFIX}")

    @Test
    fun testMismatchedFileHeaderFileId() = runBlocking<Unit> {
        val goodFileId = "good-fid"
        val badFileId = "bad-fid"
        val fileName = "a.bin"
        val fileSize = 4096L
        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "Sender",
            files = listOf(ManifestFileItem(goodFileId, fileName, fileSize, "application/octet-stream", 0L))
        )
        val receiver = TransferReceiver("ProtoIntegReceiver", InMemorySigningIdentity.generate())

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)

        withContext(Dispatchers.IO) {
            SecureHandshakeTestSupport.initiateAsClient(clientConn, InMemorySigningIdentity.generate())

            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest))
            )
            clientConn.receiveFrame() // MANIFEST_ACK

            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.FILE_HEADER,
                    payload = FramePayloads.encodeFileHeader(0, 1, badFileId, fileName, fileSize, "application/octet-stream")
                )
            )
            clientConn.receiveFrame() // RESUME_REQUEST
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.RESUME_RESPONSE,
                    payload = FramePayloads.encodeResumeResponse(badFileId, 0L, true)
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
            "Error must be InvalidFrame for file header mismatch; got: ${error?.javaClass?.simpleName}",
            error is NetworkError.InvalidFrame
        )
        clientConn.close()
    }

    @Test
    fun testMismatchedFileHeaderFileName() = runBlocking<Unit> {
        val fileId = "f1"
        val fileName = "a.bin"
        val badName = "b.bin"
        val fileSize = 4096L
        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "Sender",
            files = listOf(ManifestFileItem(fileId, fileName, fileSize, "application/octet-stream", 0L))
        )
        val receiver = TransferReceiver("ProtoIntegReceiver", InMemorySigningIdentity.generate())

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)

        withContext(Dispatchers.IO) {
            SecureHandshakeTestSupport.initiateAsClient(clientConn, InMemorySigningIdentity.generate())

            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest))
            )
            clientConn.receiveFrame() // MANIFEST_ACK

            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.FILE_HEADER,
                    payload = FramePayloads.encodeFileHeader(0, 1, fileId, badName, fileSize, "application/octet-stream")
                )
            )
            clientConn.receiveFrame() // RESUME_REQUEST
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.RESUME_RESPONSE,
                    payload = FramePayloads.encodeResumeResponse(fileId, 0L, true)
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
            "Error must be InvalidFrame for file header mismatch; got: ${error?.javaClass?.simpleName}",
            error is NetworkError.InvalidFrame
        )
        clientConn.close()
    }

    @Test
    fun testMismatchedFileHeaderFileSize() = runBlocking<Unit> {
        val fileId = "f1"
        val fileName = "a.bin"
        val fileSize = 4096L
        val badSize = 8192L
        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "Sender",
            files = listOf(ManifestFileItem(fileId, fileName, fileSize, "application/octet-stream", 0L))
        )
        val receiver = TransferReceiver("ProtoIntegReceiver", InMemorySigningIdentity.generate())

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)

        withContext(Dispatchers.IO) {
            SecureHandshakeTestSupport.initiateAsClient(clientConn, InMemorySigningIdentity.generate())

            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest))
            )
            clientConn.receiveFrame() // MANIFEST_ACK

            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.FILE_HEADER,
                    payload = FramePayloads.encodeFileHeader(0, 1, fileId, fileName, badSize, "application/octet-stream")
                )
            )
            clientConn.receiveFrame() // RESUME_REQUEST
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.RESUME_RESPONSE,
                    payload = FramePayloads.encodeResumeResponse(fileId, 0L, true)
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
            "Error must be InvalidFrame for file header mismatch; got: ${error?.javaClass?.simpleName}",
            error is NetworkError.InvalidFrame
        )
        clientConn.close()
    }

    @Test
    fun testMismatchedFileHeaderMimeType() = runBlocking<Unit> {
        val fileId = "f1"
        val fileName = "a.bin"
        val fileSize = 4096L
        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "Sender",
            files = listOf(ManifestFileItem(fileId, fileName, fileSize, "application/octet-stream", 0L))
        )
        val receiver = TransferReceiver("ProtoIntegReceiver", InMemorySigningIdentity.generate())

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)

        withContext(Dispatchers.IO) {
            SecureHandshakeTestSupport.initiateAsClient(clientConn, InMemorySigningIdentity.generate())

            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest))
            )
            clientConn.receiveFrame() // MANIFEST_ACK

            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.FILE_HEADER,
                    payload = FramePayloads.encodeFileHeader(0, 1, fileId, fileName, fileSize, "text/plain")
                )
            )
            clientConn.receiveFrame() // RESUME_REQUEST
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.RESUME_RESPONSE,
                    payload = FramePayloads.encodeResumeResponse(fileId, 0L, true)
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
            "Error must be InvalidFrame for file header mismatch; got: ${error?.javaClass?.simpleName}",
            error is NetworkError.InvalidFrame
        )
        clientConn.close()
    }

    @Test
    fun testMismatchedFileHeaderFileIndex() = runBlocking<Unit> {
        val fileId = "f1"
        val fileName = "a.bin"
        val fileSize = 4096L
        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "Sender",
            files = listOf(ManifestFileItem(fileId, fileName, fileSize, "application/octet-stream", 0L))
        )
        val receiver = TransferReceiver("ProtoIntegReceiver", InMemorySigningIdentity.generate())

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)

        withContext(Dispatchers.IO) {
            SecureHandshakeTestSupport.initiateAsClient(clientConn, InMemorySigningIdentity.generate())

            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest))
            )
            clientConn.receiveFrame() // MANIFEST_ACK

            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.FILE_HEADER,
                    payload = FramePayloads.encodeFileHeader(5, 1, fileId, fileName, fileSize, "application/octet-stream")
                )
            )
            clientConn.receiveFrame() // RESUME_REQUEST
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.RESUME_RESPONSE,
                    payload = FramePayloads.encodeResumeResponse(fileId, 0L, true)
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
            "Error must be InvalidFrame for file header mismatch; got: ${error?.javaClass?.simpleName}",
            error is NetworkError.InvalidFrame
        )
        clientConn.close()
    }

    @Test
    fun testMismatchedFileHeaderTotalFiles() = runBlocking<Unit> {
        val fileId = "f1"
        val fileName = "a.bin"
        val fileSize = 4096L
        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "Sender",
            files = listOf(ManifestFileItem(fileId, fileName, fileSize, "application/octet-stream", 0L))
        )
        val receiver = TransferReceiver("ProtoIntegReceiver", InMemorySigningIdentity.generate())

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)

        withContext(Dispatchers.IO) {
            SecureHandshakeTestSupport.initiateAsClient(clientConn, InMemorySigningIdentity.generate())

            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest))
            )
            clientConn.receiveFrame() // MANIFEST_ACK

            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.FILE_HEADER,
                    payload = FramePayloads.encodeFileHeader(0, 99, fileId, fileName, fileSize, "application/octet-stream")
                )
            )
            clientConn.receiveFrame() // RESUME_REQUEST
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.RESUME_RESPONSE,
                    payload = FramePayloads.encodeResumeResponse(fileId, 0L, true)
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
            "Error must be InvalidFrame for file header mismatch; got: ${error?.javaClass?.simpleName}",
            error is NetworkError.InvalidFrame
        )
        clientConn.close()
    }

    @Test
    fun testZeroLengthDataChunkRejected() = runBlocking<Unit> {
        val fileId = "f1"
        val fileName = "a.bin"
        val fileSize = 4096L
        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "Sender",
            files = listOf(ManifestFileItem(fileId, fileName, fileSize, "application/octet-stream", 0L))
        )
        val receiver = TransferReceiver("ProtoIntegReceiver", InMemorySigningIdentity.generate())

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)

        withContext(Dispatchers.IO) {
            SecureHandshakeTestSupport.initiateAsClient(clientConn, InMemorySigningIdentity.generate())

            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest))
            )
            clientConn.receiveFrame() // MANIFEST_ACK

            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.FILE_HEADER,
                    payload = FramePayloads.encodeFileHeader(0, 1, fileId, fileName, fileSize, "application/octet-stream")
                )
            )
            clientConn.receiveFrame() // RESUME_REQUEST
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.RESUME_RESPONSE,
                    payload = FramePayloads.encodeResumeResponse(fileId, 0L, true)
                )
            )
            // Send zero-length chunk
            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.DATA_CHUNK, sequence = 0L, payload = ByteArray(0))
            )
        }

        withTimeout(5_000) { receiverDeferred.await() }

        assertTrue(
            "Receiver must reach Failed state, got: ${receiver.state.value}",
            receiver.state.value is TransferState.Failed
        )
        val error = (receiver.state.value as? TransferState.Failed)?.error
        assertTrue(
            "Error must be InvalidFrame for zero-length chunk; got: ${error?.javaClass?.simpleName}",
            error is NetworkError.InvalidFrame
        )
        clientConn.close()
    }

    @Test
    fun testOversizedDataChunkRejected() = runBlocking<Unit> {
        val fileId = "f1"
        val fileName = "a.bin"
        val fileSize = 4096L
        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "Sender",
            files = listOf(ManifestFileItem(fileId, fileName, fileSize, "application/octet-stream", 0L))
        )
        val receiver = TransferReceiver("ProtoIntegReceiver", InMemorySigningIdentity.generate())

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)

        withContext(Dispatchers.IO) {
            SecureHandshakeTestSupport.initiateAsClient(clientConn, InMemorySigningIdentity.generate())

            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest))
            )
            clientConn.receiveFrame() // MANIFEST_ACK

            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.FILE_HEADER,
                    payload = FramePayloads.encodeFileHeader(0, 1, fileId, fileName, fileSize, "application/octet-stream")
                )
            )
            clientConn.receiveFrame() // RESUME_REQUEST
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.RESUME_RESPONSE,
                    payload = FramePayloads.encodeResumeResponse(fileId, 0L, true)
                )
            )
            // Send chunk larger than the entire declared file
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.DATA_CHUNK,
                    sequence = 0L,
                    payload = ByteArray((fileSize + 1024).toInt()) { 1 }
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
            "Error must be InvalidFrame for oversized chunk; got: ${error?.javaClass?.simpleName}",
            error is NetworkError.InvalidFrame
        )
        clientConn.close()
    }

    @Test
    fun testMismatchedChecksumFileId() = runBlocking<Unit> {
        val goodFileId = "good-fid"
        val badFileId = "bad-fid"
        val fileName = "a.bin"
        val fileSize = 4096L
        val data = ByteArray(fileSize.toInt()) { 1 }
        val calc = ChecksumCalculator()
        calc.update(data)
        val checksum = calc.finalizeChecksum()

        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "Sender",
            files = listOf(ManifestFileItem(goodFileId, fileName, fileSize, "application/octet-stream", 0L))
        )
        val receiver = TransferReceiver("ProtoIntegReceiver", InMemorySigningIdentity.generate())

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)

        withContext(Dispatchers.IO) {
            SecureHandshakeTestSupport.initiateAsClient(clientConn, InMemorySigningIdentity.generate())

            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest))
            )
            clientConn.receiveFrame() // MANIFEST_ACK

            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.FILE_HEADER,
                    payload = FramePayloads.encodeFileHeader(0, 1, goodFileId, fileName, fileSize, "application/octet-stream")
                )
            )
            clientConn.receiveFrame() // RESUME_REQUEST
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.RESUME_RESPONSE,
                    payload = FramePayloads.encodeResumeResponse(goodFileId, 0L, true)
                )
            )
            // Send data
            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.DATA_CHUNK, sequence = 0L, payload = data)
            )
            // Send checksum with wrong fileId
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.CHECKSUM,
                    payload = FramePayloads.encodeChecksum(badFileId, checksum)
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
            "Error must be InvalidFrame for checksum fileId mismatch; got: ${error?.javaClass?.simpleName}",
            error is NetworkError.InvalidFrame
        )
        clientConn.close()
    }

    @Test
    fun testInvalidCompleteTransferId() = runBlocking<Unit> {
        val fileId = "f1"
        val fileName = "a.bin"
        val fileSize = 4096L
        val data = ByteArray(fileSize.toInt()) { 1 }
        val calc = ChecksumCalculator()
        calc.update(data)
        val checksum = calc.finalizeChecksum()
        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "Sender",
            files = listOf(ManifestFileItem(fileId, fileName, fileSize, "application/octet-stream", 0L))
        )
        val receiver = TransferReceiver("ProtoIntegReceiver", InMemorySigningIdentity.generate())

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)

        withContext(Dispatchers.IO) {
            SecureHandshakeTestSupport.initiateAsClient(clientConn, InMemorySigningIdentity.generate())

            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest))
            )
            clientConn.receiveFrame() // MANIFEST_ACK

            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.FILE_HEADER,
                    payload = FramePayloads.encodeFileHeader(0, 1, fileId, fileName, fileSize, "application/octet-stream")
                )
            )
            clientConn.receiveFrame() // RESUME_REQUEST
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.RESUME_RESPONSE,
                    payload = FramePayloads.encodeResumeResponse(fileId, 0L, true)
                )
            )
            // Send data
            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.DATA_CHUNK, sequence = 0L, payload = data)
            )
            // Send checksum
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.CHECKSUM,
                    payload = FramePayloads.encodeChecksum(fileId, checksum)
                )
            )
            clientConn.receiveFrame() // CHECKSUM_RESULT
            // Send COMPLETE with wrong transferId
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.COMPLETE,
                    payload = FramePayloads.encodeComplete("wrong-transfer-id", 1, fileSize)
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
            "Error must be InvalidFrame for COMPLETE mismatch; got: ${error?.javaClass?.simpleName}",
            error is NetworkError.InvalidFrame
        )
        clientConn.close()
    }

    @Test
    fun testInvalidCompleteTotalFiles() = runBlocking<Unit> {
        val fileId = "f1"
        val fileName = "a.bin"
        val fileSize = 4096L
        val data = ByteArray(fileSize.toInt()) { 1 }
        val calc = ChecksumCalculator()
        calc.update(data)
        val checksum = calc.finalizeChecksum()
        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "Sender",
            files = listOf(ManifestFileItem(fileId, fileName, fileSize, "application/octet-stream", 0L))
        )
        val receiver = TransferReceiver("ProtoIntegReceiver", InMemorySigningIdentity.generate())

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)

        withContext(Dispatchers.IO) {
            SecureHandshakeTestSupport.initiateAsClient(clientConn, InMemorySigningIdentity.generate())

            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest))
            )
            clientConn.receiveFrame() // MANIFEST_ACK

            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.FILE_HEADER,
                    payload = FramePayloads.encodeFileHeader(0, 1, fileId, fileName, fileSize, "application/octet-stream")
                )
            )
            clientConn.receiveFrame() // RESUME_REQUEST
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.RESUME_RESPONSE,
                    payload = FramePayloads.encodeResumeResponse(fileId, 0L, true)
                )
            )
            // Send data
            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.DATA_CHUNK, sequence = 0L, payload = data)
            )
            // Send checksum
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.CHECKSUM,
                    payload = FramePayloads.encodeChecksum(fileId, checksum)
                )
            )
            clientConn.receiveFrame() // CHECKSUM_RESULT
            // Send COMPLETE with wrong totalFiles
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.COMPLETE,
                    payload = FramePayloads.encodeComplete(manifest.transferId, 99, fileSize)
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
            "Error must be InvalidFrame for COMPLETE mismatch; got: ${error?.javaClass?.simpleName}",
            error is NetworkError.InvalidFrame
        )
        clientConn.close()
    }

    @Test
    fun testInvalidCompleteTotalBytes() = runBlocking<Unit> {
        val fileId = "f1"
        val fileName = "a.bin"
        val fileSize = 4096L
        val data = ByteArray(fileSize.toInt()) { 1 }
        val calc = ChecksumCalculator()
        calc.update(data)
        val checksum = calc.finalizeChecksum()
        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "Sender",
            files = listOf(ManifestFileItem(fileId, fileName, fileSize, "application/octet-stream", 0L))
        )
        val receiver = TransferReceiver("ProtoIntegReceiver", InMemorySigningIdentity.generate())

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)

        withContext(Dispatchers.IO) {
            SecureHandshakeTestSupport.initiateAsClient(clientConn, InMemorySigningIdentity.generate())

            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest))
            )
            clientConn.receiveFrame() // MANIFEST_ACK

            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.FILE_HEADER,
                    payload = FramePayloads.encodeFileHeader(0, 1, fileId, fileName, fileSize, "application/octet-stream")
                )
            )
            clientConn.receiveFrame() // RESUME_REQUEST
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.RESUME_RESPONSE,
                    payload = FramePayloads.encodeResumeResponse(fileId, 0L, true)
                )
            )
            // Send data
            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.DATA_CHUNK, sequence = 0L, payload = data)
            )
            // Send checksum
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.CHECKSUM,
                    payload = FramePayloads.encodeChecksum(fileId, checksum)
                )
            )
            clientConn.receiveFrame() // CHECKSUM_RESULT
            // Send COMPLETE with wrong totalBytes
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.COMPLETE,
                    payload = FramePayloads.encodeComplete(manifest.transferId, 1, fileSize + 1000)
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
            "Error must be InvalidFrame for COMPLETE mismatch; got: ${error?.javaClass?.simpleName}",
            error is NetworkError.InvalidFrame
        )
        clientConn.close()
    }

    @Test
    fun testDuplicateManifestFileIdRejected() = runBlocking<Unit> {
        val dupFileId = "dup"
        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "Sender",
            files = listOf(
                ManifestFileItem(dupFileId, "a.bin", 1024L, "application/octet-stream", 0L),
                ManifestFileItem(dupFileId, "b.bin", 2048L, "application/octet-stream", 0L)
            )
        )
        val receiver = TransferReceiver("DupReceiver", InMemorySigningIdentity.generate())

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)

        withContext(Dispatchers.IO) {
            SecureHandshakeTestSupport.initiateAsClient(clientConn, InMemorySigningIdentity.generate())

            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest))
            )
            val ackFrame = clientConn.receiveFrame()
            assertEquals(FrameType.MANIFEST_ACK, ackFrame.type)
            val ackPayload = FramePayloads.decodeManifestAck(ackFrame.payload)
            assertFalse(
                "Duplicate fileId should be rejected; got accepted=${ackPayload.accepted}",
                ackPayload.accepted
            )
        }

        withTimeout(5_000) { receiverDeferred.await() }

        assertTrue(
            "Receiver must reach Failed state, got: ${receiver.state.value}",
            receiver.state.value is TransferState.Failed
        )
        val error = (receiver.state.value as? TransferState.Failed)?.error
        assertTrue(
            "Error must be InvalidFrame for duplicate fileId; got: ${error?.javaClass?.simpleName}",
            error is NetworkError.InvalidFrame
        )
        clientConn.close()
    }

    @Test
    fun testValidNormalTransferCompletes() = runBlocking<Unit> {
        val fileId = "f1"
        val fileName = "normal.bin"
        val fileSize = 8192L
        val data = ByteArray(fileSize.toInt()) { 1 }
        val calc = ChecksumCalculator()
        calc.update(data)
        val checksum = calc.finalizeChecksum()
        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "Sender",
            files = listOf(ManifestFileItem(fileId, fileName, fileSize, "application/octet-stream", 0L))
        )
        val receiver = TransferReceiver("ProtoIntegReceiver", InMemorySigningIdentity.generate())

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)

        withContext(Dispatchers.IO) {
            SecureHandshakeTestSupport.initiateAsClient(clientConn, InMemorySigningIdentity.generate())

            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest))
            )
            clientConn.receiveFrame() // MANIFEST_ACK

            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.FILE_HEADER,
                    payload = FramePayloads.encodeFileHeader(0, 1, fileId, fileName, fileSize, "application/octet-stream")
                )
            )
            clientConn.receiveFrame() // RESUME_REQUEST
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.RESUME_RESPONSE,
                    payload = FramePayloads.encodeResumeResponse(fileId, 0L, true)
                )
            )
            // Send data in two chunks
            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.DATA_CHUNK, sequence = 0L, payload = data.copyOfRange(0, 4096))
            )
            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.DATA_CHUNK, sequence = 4096L, payload = data.copyOfRange(4096, 8192))
            )
            // Send checksum
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.CHECKSUM,
                    payload = FramePayloads.encodeChecksum(fileId, checksum)
                )
            )
            clientConn.receiveFrame() // CHECKSUM_RESULT
            // Send COMPLETE
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.COMPLETE,
                    payload = FramePayloads.encodeComplete(manifest.transferId, 1, fileSize)
                )
            )
        }

        val result = withTimeout(10_000) { receiverDeferred.await() }

        assertTrue("Transfer should succeed, got: ${receiver.state.value}", result.isSuccess)
        assertFalse("Partial file must be gone", partialFile(fileName).exists())
        assertTrue("Final file must exist", File(destDir, fileName).exists())
        clientConn.close()
    }

    @Test
    fun testValidExactSizeFinalChunkCompletes() = runBlocking<Unit> {
        val fileId = "f1"
        val fileName = "exact.bin"
        val fileSize = 12288L // 3 x 4096 exactly
        val data = ByteArray(fileSize.toInt()) { 1 }
        val calc = ChecksumCalculator()
        calc.update(data)
        val checksum = calc.finalizeChecksum()
        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "Sender",
            files = listOf(ManifestFileItem(fileId, fileName, fileSize, "application/octet-stream", 0L))
        )
        val receiver = TransferReceiver("ProtoIntegReceiver", InMemorySigningIdentity.generate())

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)

        withContext(Dispatchers.IO) {
            SecureHandshakeTestSupport.initiateAsClient(clientConn, InMemorySigningIdentity.generate())

            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest))
            )
            clientConn.receiveFrame() // MANIFEST_ACK

            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.FILE_HEADER,
                    payload = FramePayloads.encodeFileHeader(0, 1, fileId, fileName, fileSize, "application/octet-stream")
                )
            )
            clientConn.receiveFrame() // RESUME_REQUEST
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.RESUME_RESPONSE,
                    payload = FramePayloads.encodeResumeResponse(fileId, 0L, true)
                )
            )
            // Send data in 3 exactly-sized chunks
            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.DATA_CHUNK, sequence = 0L, payload = data.copyOfRange(0, 4096))
            )
            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.DATA_CHUNK, sequence = 4096L, payload = data.copyOfRange(4096, 8192))
            )
            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.DATA_CHUNK, sequence = 8192L, payload = data.copyOfRange(8192, 12288))
            )
            // Send checksum
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.CHECKSUM,
                    payload = FramePayloads.encodeChecksum(fileId, checksum)
                )
            )
            clientConn.receiveFrame() // CHECKSUM_RESULT
            // Send COMPLETE
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.COMPLETE,
                    payload = FramePayloads.encodeComplete(manifest.transferId, 1, fileSize)
                )
            )
        }

        val result = withTimeout(10_000) { receiverDeferred.await() }

        assertTrue("Transfer should succeed, got: ${receiver.state.value}", result.isSuccess)
        assertTrue("Final file must exist", File(destDir, fileName).exists())
        assertEquals(fileSize, File(destDir, fileName).length())
        clientConn.close()
    }

    @Test
    fun testMultipleFilesValidTransferCompletes() = runBlocking<Unit> {
        val dataA = ByteArray(4096) { 1 }
        val calcA = ChecksumCalculator()
        calcA.update(dataA)
        val checksumA = calcA.finalizeChecksum()

        val dataB = ByteArray(8192) { 1 }
        val calcB = ChecksumCalculator()
        calcB.update(dataB)
        val checksumB = calcB.finalizeChecksum()

        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "Sender",
            files = listOf(
                ManifestFileItem("fa", "a.bin", 4096L, "application/octet-stream", 0L),
                ManifestFileItem("fb", "b.bin", 8192L, "application/octet-stream", 0L)
            )
        )
        val receiver = TransferReceiver("ProtoIntegReceiver", InMemorySigningIdentity.generate())

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)

        withContext(Dispatchers.IO) {
            SecureHandshakeTestSupport.initiateAsClient(clientConn, InMemorySigningIdentity.generate())

            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest))
            )
            clientConn.receiveFrame() // MANIFEST_ACK

            // File A
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.FILE_HEADER,
                    payload = FramePayloads.encodeFileHeader(0, 2, "fa", "a.bin", 4096L, "application/octet-stream")
                )
            )
            clientConn.receiveFrame() // RESUME_REQUEST
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.RESUME_RESPONSE,
                    payload = FramePayloads.encodeResumeResponse("fa", 0L, true)
                )
            )
            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.DATA_CHUNK, sequence = 0L, payload = dataA)
            )
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.CHECKSUM,
                    payload = FramePayloads.encodeChecksum("fa", checksumA)
                )
            )
            clientConn.receiveFrame() // CHECKSUM_RESULT

            // File B
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.FILE_HEADER,
                    payload = FramePayloads.encodeFileHeader(1, 2, "fb", "b.bin", 8192L, "application/octet-stream")
                )
            )
            clientConn.receiveFrame() // RESUME_REQUEST
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.RESUME_RESPONSE,
                    payload = FramePayloads.encodeResumeResponse("fb", 0L, true)
                )
            )
            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.DATA_CHUNK, sequence = 0L, payload = dataB.copyOfRange(0, 4096))
            )
            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.DATA_CHUNK, sequence = 4096L, payload = dataB.copyOfRange(4096, 8192))
            )
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.CHECKSUM,
                    payload = FramePayloads.encodeChecksum("fb", checksumB)
                )
            )
            clientConn.receiveFrame() // CHECKSUM_RESULT
            // Send COMPLETE with correct totals
            clientConn.sendFrame(
                ProtocolFrame(
                    type = FrameType.COMPLETE,
                    payload = FramePayloads.encodeComplete(manifest.transferId, 2, 12288)
                )
            )
        }

        val result = withTimeout(10_000) { receiverDeferred.await() }

        assertTrue("Transfer should succeed, got: ${receiver.state.value}", result.isSuccess)
        assertTrue(File(destDir, "a.bin").exists())
        assertTrue(File(destDir, "b.bin").exists())
        clientConn.close()
    }
}

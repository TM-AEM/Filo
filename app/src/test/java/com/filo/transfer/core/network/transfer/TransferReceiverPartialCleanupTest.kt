package com.filo.transfer.core.network.transfer

import com.filo.transfer.core.network.model.ManifestFileItem
import com.filo.transfer.core.network.model.TransferManifest
import com.filo.transfer.core.network.model.TransferState
import com.filo.transfer.core.network.protocol.FramePayloads
import com.filo.transfer.core.network.protocol.FrameType
import com.filo.transfer.core.network.protocol.ProtocolConstants
import com.filo.transfer.core.network.protocol.ProtocolFrame
import com.filo.transfer.core.network.security.handshake.InMemorySigningIdentity
import com.filo.transfer.core.network.security.handshake.SigningIdentity
import com.filo.transfer.core.network.transport.SecureHandshakeTestSupport
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
import java.util.UUID

class TransferReceiverPartialCleanupTest {

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

    private fun createTestFile(parent: File, name: String, sizeBytes: Int): File {
        val file = File(parent, name)
        file.writeBytes(ByteArray(sizeBytes) { (it % 251).toByte() })
        return file
    }

    private fun partialFile(name: String): File {
        return File(destDir, "$name${ProtocolConstants.PARTIAL_FILE_SUFFIX}")
    }

    private fun activePartialField(receiver: TransferReceiver): File? {
        val field = TransferReceiver::class.java.getDeclaredField("activePartialFile")
        field.isAccessible = true
        return field.get(receiver) as File?
    }

    private suspend fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) {
                delay(10)
            }
        }
    }

    private suspend fun handshakeAndStartFile(
        clientConn: com.filo.transfer.core.network.transport.SocketConnection,
        identity: SigningIdentity,
        fileName: String,
        fileSize: Long,
        fileId: String = "d71"
    ) = withContext(Dispatchers.IO) {
        SecureHandshakeTestSupport.initiateAsClient(clientConn, identity)

        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "D71Sender",
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

    @Test
    fun cancellationDeletesActivePartialFile() = runBlocking {
        val fileName = "cancel_partial.bin"
        val fileSize = 200_000L
        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)
        val receiver = TransferReceiver("D71Receiver", InMemorySigningIdentity.generate())

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)
        handshakeAndStartFile(clientConn, InMemorySigningIdentity.generate(), fileName, fileSize)
        withContext(Dispatchers.IO) {
            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.DATA_CHUNK, sequence = 0L, payload = ByteArray(4096) { 1 })
            )
        }

        waitUntil {
            partialFile(fileName).exists() && receiver.state.value is TransferState.Transferring
        }
        assertTrue(partialFile(fileName).exists())

        receiver.cancel()

        withTimeout(5_000) { receiverDeferred.await() }

        assertEquals(TransferState.Cancelled, receiver.state.value)
        assertFalse(partialFile(fileName).exists())
        assertNull(activePartialField(receiver))
        clientConn.close()
    }

    @Test
    fun successfulTransferDoesNotDeleteFinalFile() = runBlocking {
        val testFile = createTestFile(sourceDir, "success.bin", 16 * 1024)
        val originalSha = testFile.inputStream().use { ChecksumCalculator.calculate(it) }

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val sender = TransferSender(deviceName = "D71SuccessSender", signingIdentity = InMemorySigningIdentity.generate())
        val receiver = TransferReceiver(deviceName = "D71SuccessReceiver", signingIdentity = InMemorySigningIdentity.generate())

        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "D71SuccessSender",
            files = listOf(
                ManifestFileItem("s1", testFile.name, testFile.length(), "application/octet-stream", testFile.lastModified())
            )
        )

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)
        val sendRes = sender.transfer(clientConn, manifest, mapOf("s1" to LocalFileSource(testFile, "s1")))
        val recvRes = receiverDeferred.await()

        assertTrue(sendRes.isSuccess)
        assertTrue(recvRes.isSuccess)

        val finalFile = File(destDir, testFile.name)
        assertTrue(finalFile.exists())
        assertEquals(testFile.length(), finalFile.length())
        assertEquals(originalSha, finalFile.inputStream().use { ChecksumCalculator.calculate(it) })
        assertFalse(partialFile(testFile.name).exists())
        assertTrue(receiver.state.value is TransferState.Completed)
        assertNull(activePartialField(receiver))
    }

    @Test
    fun cancelWhilePausedDeletesPartialAndDoesNotDeadlock() = runBlocking {
        val fileName = "paused_cancel.bin"
        val fileSize = 200_000L
        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)
        val receiver = TransferReceiver("D71PauseReceiver", InMemorySigningIdentity.generate())

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)
        handshakeAndStartFile(clientConn, InMemorySigningIdentity.generate(), fileName, fileSize, fileId = "p1")
        withContext(Dispatchers.IO) {
            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.DATA_CHUNK, sequence = 0L, payload = ByteArray(4096) { 2 })
            )
        }

        waitUntil { receiver.state.value is TransferState.Transferring && partialFile(fileName).exists() }

        receiver.pause()
        waitUntil { receiver.state.value is TransferState.Paused }
        assertTrue(receiver.state.value is TransferState.Paused)
        assertTrue(partialFile(fileName).exists())

        withContext(Dispatchers.IO) {
            clientConn.sendFrame(
                ProtocolFrame(type = FrameType.DATA_CHUNK, sequence = 1L, payload = ByteArray(4096) { 3 })
            )
        }
        delay(50)

        receiver.cancel()

        withTimeout(5_000) { receiverDeferred.await() }

        assertEquals(TransferState.Cancelled, receiver.state.value)
        assertFalse(partialFile(fileName).exists())
        assertNull(activePartialField(receiver))
        clientConn.close()
    }

    @Test
    fun cancelAfterSuccessDoesNotDeleteFinalFile() = runBlocking {
        val testFile = createTestFile(sourceDir, "keep_final.bin", 8 * 1024)
        val originalBytes = testFile.readBytes()

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val sender = TransferSender(signingIdentity = InMemorySigningIdentity.generate())
        val receiver = TransferReceiver(signingIdentity = InMemorySigningIdentity.generate())

        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "KeepFinalSender",
            files = listOf(
                ManifestFileItem("k1", testFile.name, testFile.length(), "application/octet-stream", testFile.lastModified())
            )
        )

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)
        val sendRes = sender.transfer(clientConn, manifest, mapOf("k1" to LocalFileSource(testFile, "k1")))
        val recvRes = receiverDeferred.await()

        assertTrue(sendRes.isSuccess)
        assertTrue(recvRes.isSuccess)

        val finalFile = File(destDir, testFile.name)
        assertTrue(finalFile.exists())
        assertNull(activePartialField(receiver))

        receiver.cancel()

        assertTrue(finalFile.exists())
        assertEquals(originalBytes.toList(), finalFile.readBytes().toList())
        assertFalse(partialFile(testFile.name).exists())
        assertTrue(receiver.state.value is TransferState.Completed)
    }
}

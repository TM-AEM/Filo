package com.filo.transfer.core.network.transport

import com.filo.transfer.core.network.model.ManifestFileItem
import com.filo.transfer.core.network.model.NetworkError
import com.filo.transfer.core.network.model.TransferManifest
import com.filo.transfer.core.network.model.TransferState
import com.filo.transfer.core.network.protocol.FramePayloads
import com.filo.transfer.core.network.protocol.FrameType
import com.filo.transfer.core.network.protocol.ProtocolConstants
import com.filo.transfer.core.network.protocol.ProtocolFrame
import com.filo.transfer.core.network.security.handshake.InMemorySigningIdentity
import com.filo.transfer.core.network.transfer.ChecksumCalculator
import com.filo.transfer.core.network.transfer.LocalFileSource
import com.filo.transfer.core.network.transfer.TransferFileSource
import com.filo.transfer.core.network.transfer.TransferReceiver
import com.filo.transfer.core.network.transfer.TransferSender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class TcpTransferIntegrationTest {

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
        FileOutputStream(file).use { out ->
            val buf = ByteArray(8192)
            var written = 0
            while (written < sizeBytes) {
                val toWrite = (sizeBytes - written).coerceAtMost(buf.size)
                for (i in 0 until toWrite) {
                    buf[i] = ((written + i) % 251).toByte()
                }
                out.write(buf, 0, toWrite)
                written += toWrite
            }
            out.flush()
        }
        return file
    }

    @Test
    fun testSingleSmallFileTransfer() = runBlocking {
        val testFile = createTestFile(sourceDir, "document.txt", 16 * 1024)
        val fileSha = testFile.inputStream().use { ChecksumCalculator.calculate(it) }

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val sender = TransferSender(deviceName = "SenderDevice", signingIdentity = InMemorySigningIdentity.generate())
        val receiver = TransferReceiver(deviceName = "ReceiverDevice", signingIdentity = InMemorySigningIdentity.generate())

        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "SenderDevice",
            files = listOf(
                ManifestFileItem(
                    fileId = "doc-1",
                    fileName = testFile.name,
                    size = testFile.length(),
                    mimeType = "text/plain",
                    lastModified = testFile.lastModified()
                )
            )
        )
        val fileSources = mapOf<String, TransferFileSource>(
            "doc-1" to LocalFileSource(testFile, fileId = "doc-1")
        )

        val receiverDeferred = async {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)
        val senderResult = sender.transfer(clientConn, manifest, fileSources)

        val receiverResult = receiverDeferred.await()

        assertTrue("Sender failed: ${senderResult.exceptionOrNull()?.message}", senderResult.isSuccess)
        assertTrue("Receiver failed: ${receiverResult.exceptionOrNull()?.message}", receiverResult.isSuccess)

        val receivedFile = File(destDir, testFile.name)
        assertTrue(receivedFile.exists())
        assertEquals(testFile.length(), receivedFile.length())

        val receivedSha = receivedFile.inputStream().use { ChecksumCalculator.calculate(it) }
        assertEquals(fileSha, receivedSha)

        assertTrue(sender.state.value is TransferState.Completed)
        assertTrue(receiver.state.value is TransferState.Completed)
    }

    @Test
    fun testMultipleFilesTransfer() = runBlocking {
        val file1 = createTestFile(sourceDir, "image.png", 32 * 1024)
        val file2 = createTestFile(sourceDir, "audio.mp3", 140 * 1024)
        val file3 = createTestFile(sourceDir, "notes.pdf", 4 * 1024)

        val sha1 = file1.inputStream().use { ChecksumCalculator.calculate(it) }
        val sha2 = file2.inputStream().use { ChecksumCalculator.calculate(it) }
        val sha3 = file3.inputStream().use { ChecksumCalculator.calculate(it) }

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val sender = TransferSender(signingIdentity = InMemorySigningIdentity.generate())
        val receiver = TransferReceiver(signingIdentity = InMemorySigningIdentity.generate())

        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "MultiSender",
            files = listOf(
                ManifestFileItem("f1", file1.name, file1.length(), "image/png", file1.lastModified()),
                ManifestFileItem("f2", file2.name, file2.length(), "audio/mpeg", file2.lastModified()),
                ManifestFileItem("f3", file3.name, file3.length(), "application/pdf", file3.lastModified())
            )
        )
        val fileSources = mapOf(
            "f1" to LocalFileSource(file1, "f1"),
            "f2" to LocalFileSource(file2, "f2"),
            "f3" to LocalFileSource(file3, "f3")
        )

        val receiverDeferred = async {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)
        val sendRes = sender.transfer(clientConn, manifest, fileSources)
        val recvRes = receiverDeferred.await()

        assertTrue(sendRes.isSuccess)
        assertTrue(recvRes.isSuccess)

        val recFile1 = File(destDir, file1.name)
        val recFile2 = File(destDir, file2.name)
        val recFile3 = File(destDir, file3.name)

        assertTrue(recFile1.exists())
        assertTrue(recFile2.exists())
        assertTrue(recFile3.exists())

        assertEquals(sha1, recFile1.inputStream().use { ChecksumCalculator.calculate(it) })
        assertEquals(sha2, recFile2.inputStream().use { ChecksumCalculator.calculate(it) })
        assertEquals(sha3, recFile3.inputStream().use { ChecksumCalculator.calculate(it) })
    }

    @Test
    fun testLargeFileMultiChunkTransfer() = runBlocking {
        val largeFile = createTestFile(sourceDir, "video.mp4", 1_500_000)
        val originalSha = largeFile.inputStream().use { ChecksumCalculator.calculate(it) }

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val sender = TransferSender(signingIdentity = InMemorySigningIdentity.generate())
        val receiver = TransferReceiver(signingIdentity = InMemorySigningIdentity.generate())

        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "BigSender",
            files = listOf(
                ManifestFileItem("big1", largeFile.name, largeFile.length(), "video/mp4", largeFile.lastModified())
            )
        )

        val receiverDeferred = async {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)
        val sendRes = sender.transfer(clientConn, manifest, mapOf("big1" to LocalFileSource(largeFile, "big1")))
        val recvRes = receiverDeferred.await()

        assertTrue(sendRes.isSuccess)
        assertTrue(recvRes.isSuccess)

        val receivedFile = File(destDir, largeFile.name)
        assertTrue(receivedFile.exists())
        assertEquals(largeFile.length(), receivedFile.length())
        assertEquals(originalSha, receivedFile.inputStream().use { ChecksumCalculator.calculate(it) })
    }

    @Test
    fun testResumePartialFile() = runBlocking {
        val fullFile = createTestFile(sourceDir, "backup.zip", 300 * 1024)
        val fullSha = fullFile.inputStream().use { ChecksumCalculator.calculate(it) }

        // Simulate pre-existing partial file on receiver side: first 100 KiB
        val partialFile = File(destDir, "${fullFile.name}${ProtocolConstants.PARTIAL_FILE_SUFFIX}")
        fullFile.inputStream().use { input ->
            FileOutputStream(partialFile).use { output ->
                val buf = ByteArray(100 * 1024)
                input.read(buf)
                output.write(buf)
            }
        }
        assertEquals(100 * 1024L, partialFile.length())

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val sender = TransferSender(signingIdentity = InMemorySigningIdentity.generate())
        val receiver = TransferReceiver(signingIdentity = InMemorySigningIdentity.generate())

        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "ResumeSender",
            files = listOf(
                ManifestFileItem("zip1", fullFile.name, fullFile.length(), "application/zip", fullFile.lastModified())
            )
        )

        val receiverDeferred = async {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = true)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)
        val sendRes = sender.transfer(clientConn, manifest, mapOf("zip1" to LocalFileSource(fullFile, "zip1")))
        val recvRes = receiverDeferred.await()

        assertTrue(sendRes.isSuccess)
        assertTrue(recvRes.isSuccess)

        assertFalse(partialFile.exists())
        val finalFile = File(destDir, fullFile.name)
        assertTrue(finalFile.exists())
        assertEquals(fullFile.length(), finalFile.length())
        assertEquals(fullSha, finalFile.inputStream().use { ChecksumCalculator.calculate(it) })
    }

    @Test
    fun testCancellationDuringTransfer() = runBlocking {
        val file = createTestFile(sourceDir, "cancel_test.bin", 500 * 1024)

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val sender = TransferSender(signingIdentity = InMemorySigningIdentity.generate())
        val receiver = TransferReceiver(signingIdentity = InMemorySigningIdentity.generate())

        val readStarted = CompletableDeferred<Unit>()

        // Custom file source that signals when reading starts
        val latchFileSource = object : TransferFileSource {
            override val fileId: String = "c1"
            override val fileName: String = file.name
            override val size: Long = file.length()
            override val mimeType: String = "application/octet-stream"
            override val lastModified: Long = file.lastModified()
            override fun openStream(offset: Long): InputStream {
                val fis = file.inputStream()
                return object : InputStream() {
                    override fun read(): Int {
                        readStarted.complete(Unit)
                        return fis.read()
                    }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        readStarted.complete(Unit)
                        return fis.read(b, off, len)
                    }
                    override fun close() = fis.close()
                }
            }
        }

        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "CancelSender",
            files = listOf(
                ManifestFileItem("c1", file.name, file.length(), "application/octet-stream", file.lastModified())
            )
        )

        val receiverDeferred = async {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)

        launch {
            readStarted.await()
            sender.cancel()
        }

        val sendRes = sender.transfer(clientConn, manifest, mapOf("c1" to latchFileSource))
        receiverDeferred.await()

        assertFalse(sendRes.isSuccess)
        assertEquals(TransferState.Cancelled, sender.state.value)
    }

    @Test
    fun testPauseAndResume() = runBlocking {
        val file = createTestFile(sourceDir, "pause_test.bin", 300 * 1024)
        val originalSha = file.inputStream().use { ChecksumCalculator.calculate(it) }

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val sender = TransferSender(signingIdentity = InMemorySigningIdentity.generate())
        val receiver = TransferReceiver(signingIdentity = InMemorySigningIdentity.generate())

        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "PauseSender",
            files = listOf(
                ManifestFileItem("p1", file.name, file.length(), "application/octet-stream", file.lastModified())
            )
        )

        val receiverDeferred = async {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)

        launch {
            delay(5)
            sender.pause()
            delay(50)
            sender.resume()
        }

        val sendRes = sender.transfer(clientConn, manifest, mapOf("p1" to LocalFileSource(file, "p1")))
        val recvRes = receiverDeferred.await()

        assertTrue(sendRes.isSuccess)
        assertTrue(recvRes.isSuccess)

        val finalFile = File(destDir, file.name)
        assertTrue(finalFile.exists())
        assertEquals(originalSha, finalFile.inputStream().use { ChecksumCalculator.calculate(it) })
    }

    @Test
    fun testChecksumMismatchRejectsFile() = runBlocking {
        val originalFile = createTestFile(sourceDir, "secret.dat", 64 * 1024)

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val receiver = TransferReceiver(signingIdentity = InMemorySigningIdentity.generate())

        // Launch receiver waiting for transfer
        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)

        // Simulate a sender that transmits data but sends an invalid/corrupted SHA-256
        SecureHandshakeTestSupport.initiateAsClient(clientConn, InMemorySigningIdentity.generate())

        val manifest = TransferManifest(
            transferId = "corrupt-tx",
            senderDeviceName = "BadShaSender",
            files = listOf(
                ManifestFileItem("b1", originalFile.name, originalFile.length(), "application/octet-stream", originalFile.lastModified())
            )
        )
        clientConn.sendFrame(ProtocolFrame(type = FrameType.MANIFEST, payload = FramePayloads.encodeManifest(manifest)))
        val mack = clientConn.receiveFrame()
        assertEquals(FrameType.MANIFEST_ACK, mack.type)

        clientConn.sendFrame(
            ProtocolFrame(
                type = FrameType.FILE_HEADER,
                payload = FramePayloads.encodeFileHeader(0, 1, "b1", originalFile.name, originalFile.length(), "application/octet-stream")
            )
        )
        val rreq = clientConn.receiveFrame()
        assertEquals(FrameType.RESUME_REQUEST, rreq.type)

        clientConn.sendFrame(
            ProtocolFrame(
                type = FrameType.RESUME_RESPONSE,
                payload = FramePayloads.encodeResumeResponse("b1", 0L, true)
            )
        )

        // Send raw file data
        val bytes = originalFile.readBytes()
        clientConn.sendFrame(
            ProtocolFrame(
                type = FrameType.DATA_CHUNK,
                sequence = 0L,
                payload = bytes
            )
        )

        // Send intentionally corrupted SHA-256
        clientConn.sendFrame(
            ProtocolFrame(
                type = FrameType.CHECKSUM,
                payload = FramePayloads.encodeChecksum("b1", "0000000000000000000000000000000000000000000000000000000000000000")
            )
        )

        // Receiver sends CHECKSUM_RESULT(matched = false)
        val cresult = clientConn.receiveFrame()
        assertEquals(FrameType.CHECKSUM_RESULT, cresult.type)
        val decodedResult = FramePayloads.decodeChecksumResult(cresult.payload)
        assertFalse(decodedResult.matched)

        val recvRes = receiverDeferred.await()
        assertFalse(recvRes.isSuccess)
        assertTrue(recvRes.exceptionOrNull() is NetworkError.ChecksumMismatch)

        // Destination final file must NOT exist
        val finalFile = File(destDir, originalFile.name)
        assertFalse(finalFile.exists())

        // Partial file must be deleted on checksum failure
        val partialFile = File(destDir, "${originalFile.name}${ProtocolConstants.PARTIAL_FILE_SUFFIX}")
        assertFalse(partialFile.exists())

        clientConn.close()
    }

    @Test
    fun testPeerDisconnectThrowsCleanError() = runBlocking {
        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val receiverDeferred = async {
            val serverConn = server.accept()
            // Immediately close server side to simulate abrupt disconnect
            serverConn.close()
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)
        receiverDeferred.await()

        val sender = TransferSender(signingIdentity = InMemorySigningIdentity.generate())
        val manifest = TransferManifest(
            transferId = "disc-test",
            senderDeviceName = "DisconnectSender",
            files = emptyList()
        )

        val result = sender.transfer(clientConn, manifest, emptyMap())
        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull() is NetworkError)
    }
}

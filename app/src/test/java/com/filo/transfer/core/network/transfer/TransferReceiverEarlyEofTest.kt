package com.filo.transfer.core.network.transfer

import com.filo.transfer.core.network.model.ManifestFileItem
import com.filo.transfer.core.network.model.NetworkError
import com.filo.transfer.core.network.model.TransferManifest
import com.filo.transfer.core.network.model.TransferState
import com.filo.transfer.core.network.protocol.ProtocolConstants
import com.filo.transfer.core.network.transport.TcpClientTransport
import com.filo.transfer.core.network.transport.TcpServerTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Tests the early-EOF / premature-CHECKSUM failure path documented in Task 09.
 *
 * Reproduces a scenario where a sender declares a fileSize larger than the bytes
 * actually delivered on the stream. With the production fix (Task 11), the sender
 * now detects EOF before the declared size is reached and throws IoError BEFORE
 * sending CHECKSUM, preventing the receiver from ever seeing an invalid protocol
 * sequence.
 *
 * NOTE: The partial .filo.part file is still orphaned by the receiver's error
 * handler — this cleanup gap was documented in Task 09 and remains unfixed in
 * this task.
 */
class TransferReceiverEarlyEofTest {

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

    @Test
    fun testEarlyEofRejectsTransferAndCleansPartialFile() = runBlocking<Unit> {
        val declaredSize = 16 * 1024L
        val actualReadSize = 8 * 1024
        val payload = ByteArray(declaredSize.toInt()) { (it % 251).toByte() }

        val fullFile = File(sourceDir, "data.bin")
        fullFile.writeBytes(payload)

        // Custom source that declares 16 KB but only delivers 8 KB before EOF.
        val truncatedSource = object : TransferFileSource {
            override val fileId = "eof-test"
            override val fileName = fullFile.name
            override val size: Long = declaredSize
            override val mimeType = "application/octet-stream"
            override val lastModified = fullFile.lastModified()

            override fun openStream(offset: Long): InputStream {
                if (offset != 0L) {
                    throw RuntimeException("Offset-based streams are not tested in this scenario")
                }
                return ByteArrayInputStream(payload.copyOfRange(0, actualReadSize))
            }
        }

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val sender = TransferSender(deviceName = "EofSender")
        val receiver = TransferReceiver(deviceName = "EofReceiver")

        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "EofSender",
            files = listOf(
                ManifestFileItem(
                    "eof-test",
                    fullFile.name,
                    declaredSize,
                    "application/octet-stream",
                    fullFile.lastModified()
                )
            )
        )

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)
        val sendRes = sender.transfer(clientConn, manifest, mapOf("eof-test" to truncatedSource))
        val recvRes = receiverDeferred.await()

        // 1. Sender must fail with IoError before sending CHECKSUM.
        assertFalse(
            "Sender should fail when source stream ends before declared fileSize",
            sendRes.isSuccess
        )
        assertTrue(
            "Sender must reach Failed state, got: ${sender.state.value}",
            sender.state.value is TransferState.Failed
        )
        val sendError = sendRes.exceptionOrNull()
        assertTrue(
            "Sender error must be IoError indicating premature EOF; got: ${sendError?.javaClass?.simpleName} - ${sendError?.message}",
            sendError is NetworkError.IoError && sendError.message.contains("prematurely")
        )

        // 2. Receiver must reject the transfer.
        assertFalse(
            "Receiver should fail when sent fewer bytes than declared fileSize",
            recvRes.isSuccess
        )
        assertTrue(
            "Receiver must reach Failed state, got: ${receiver.state.value}",
            receiver.state.value is TransferState.Failed
        )
        val recvError = recvRes.exceptionOrNull()
        assertTrue(
            "Receiver error must relate to unexpected frame type; got: ${recvError?.javaClass?.simpleName} - ${recvError?.message}",
            recvError is NetworkError && recvError.message.contains("DATA_CHUNK")
        )

        // 3. No final destination file must be created.
        val finalFile = File(destDir, fullFile.name)
        assertFalse("Final file must NOT be created when transfer is rejected", finalFile.exists())

        // 4. Document current production behavior: partial file is orphaned after
        //    InvalidFrame/CHECKSUM-rejection error path (no cleanup in this branch).
        val partialFile = File(destDir, "${fullFile.name}${ProtocolConstants.PARTIAL_FILE_SUFFIX}")
        assertTrue(
            "CURRENT BEHAVIOR (Task 09 documented gap): partial .filo.part file is orphaned " +
            "and NOT auto-cleaned on frame-rejection error. Asserting existence to lock down behavior.",
            partialFile.exists()
        )
        assertEquals(
            "Partial file must contain exactly the bytes that were received (not the declared size)",
            actualReadSize.toLong(),
            partialFile.length()
        )
    }

    @Test
    fun testEarlyEofFailsBeforeSendingChecksum() = runBlocking<Unit> {
        val declaredSize = 16 * 1024L
        val actualReadSize = 8 * 1024
        val payload = ByteArray(declaredSize.toInt()) { (it % 251).toByte() }

        val truncatedSource = object : TransferFileSource {
            override val fileId = "eof-assert"
            override val fileName = "test.bin"
            override val size: Long = declaredSize
            override val mimeType = "application/octet-stream"
            override val lastModified = System.currentTimeMillis()

            override fun openStream(offset: Long): InputStream {
                if (offset != 0L) {
                    throw RuntimeException("Offset-based streams are not tested in this scenario")
                }
                return ByteArrayInputStream(payload.copyOfRange(0, actualReadSize))
            }
        }

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val sender = TransferSender(deviceName = "EofAssertSender")
        val receiver = TransferReceiver(deviceName = "EofAssertReceiver")

        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "EofAssertSender",
            files = listOf(
                ManifestFileItem("eof-assert", "test.bin", declaredSize, "application/octet-stream", System.currentTimeMillis())
            )
        )

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, File(System.getProperty("java.io.tmpdir") ?: "/tmp"), allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)
        val sendRes = sender.transfer(clientConn, manifest, mapOf("eof-assert" to truncatedSource))
        receiverDeferred.await()

        // The sender must NOT have completed successfully.
        assertFalse("Sender must not succeed when source stream is shorter than declared size", sendRes.isSuccess)

        // The sender's final state must be Failed (not Completed or any terminal success state).
        assertTrue(
            "Sender must reach Failed state, got: ${sender.state.value}",
            sender.state.value is TransferState.Failed
        )

        // The error must identify the premature-E-of-source condition (IoError, not ChecksumMismatch etc.).
        val error = sendRes.exceptionOrNull()
        assertTrue(
            "Sender error must identify premature EOF; got: ${error?.javaClass?.simpleName} - ${error?.message}",
            error is NetworkError.IoError && error.message.contains("prematurely")
        )
    }
}

package com.filo.transfer.core.network.transfer

import com.filo.transfer.core.network.model.ManifestFileItem
import com.filo.transfer.core.network.model.TransferManifest
import com.filo.transfer.core.network.model.TransferState
import com.filo.transfer.core.network.protocol.ProtocolConstants
import com.filo.transfer.core.network.transport.TcpClientTransport
import com.filo.transfer.core.network.transport.TcpServerTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class TransferReceiverCollisionCascadeTest {

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
    fun newTransferCascadeWritesData3WithoutOverwritingExistingFiles() = runBlocking<Unit> {
        val existing0 = File(destDir, "data.bin")
        val existing1 = File(destDir, "data_1.bin")
        val existing2 = File(destDir, "data_2.bin")
        val contents0 = byteArrayOf(0x0A, 0x0A, 0x0A)
        val contents1 = byteArrayOf(0x1B, 0x1B, 0x1B, 0x1B)
        val contents2 = byteArrayOf(0x2C, 0x2C)
        existing0.writeBytes(contents0)
        existing1.writeBytes(contents1)
        existing2.writeBytes(contents2)

        val incoming = File(sourceDir, "data.bin")
        val incomingBytes = ByteArray(8 * 1024) { (it % 251).toByte() }
        incoming.writeBytes(incomingBytes)
        val incomingSha = incoming.inputStream().use { ChecksumCalculator.calculate(it) }

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val sender = TransferSender(deviceName = "D72Sender")
        val receiver = TransferReceiver(deviceName = "D72Receiver")

        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "D72Sender",
            files = listOf(
                ManifestFileItem(
                    "d72",
                    "data.bin",
                    incoming.length(),
                    "application/octet-stream",
                    incoming.lastModified()
                )
            )
        )

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = false)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)
        val sendRes = sender.transfer(clientConn, manifest, mapOf("d72" to LocalFileSource(incoming, "d72")))
        val recvRes = receiverDeferred.await()

        assertTrue("Sender failed: ${sendRes.exceptionOrNull()?.message}", sendRes.isSuccess)
        assertTrue("Receiver failed: ${recvRes.exceptionOrNull()?.message}", recvRes.isSuccess)
        assertTrue(receiver.state.value is TransferState.Completed)

        assertTrue(existing0.exists())
        assertTrue(existing1.exists())
        assertTrue(existing2.exists())
        assertArrayEquals(contents0, existing0.readBytes())
        assertArrayEquals(contents1, existing1.readBytes())
        assertArrayEquals(contents2, existing2.readBytes())

        val created = File(destDir, "data_3.bin")
        assertTrue(created.exists())
        assertEquals(incoming.length(), created.length())
        assertArrayEquals(incomingBytes, created.readBytes())
        assertEquals(incomingSha, created.inputStream().use { ChecksumCalculator.calculate(it) })

        assertFalse(File(destDir, "data_4.bin").exists())
        assertFalse(File(destDir, "data.bin${ProtocolConstants.PARTIAL_FILE_SUFFIX}").exists())
        assertFalse(File(destDir, "data_3.bin${ProtocolConstants.PARTIAL_FILE_SUFFIX}").exists())
        destDir.listFiles()?.forEach { file ->
            assertFalse(file.name.endsWith(ProtocolConstants.PARTIAL_FILE_SUFFIX))
        }
    }
}

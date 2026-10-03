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

class TransferReceiverCollisionResumeTest {

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
    fun testD7CollisionPlusResume() = runBlocking<Unit> {
        val payloadSize = 16 * 1024
        val partialSize = 4 * 1024
        val incomingBytes = ByteArray(payloadSize) { (it % 251).toByte() }

        val existing0 = File(destDir, "data.bin")
        val existing1 = File(destDir, "data_1.bin")
        val existing2 = File(destDir, "data_2.bin")
        val contents1 = byteArrayOf(0x11, 0x11, 0x11, 0x11)
        val contents2 = byteArrayOf(0x22, 0x22, 0x22)
        existing0.writeBytes(byteArrayOf(0x00, 0x00))
        existing1.writeBytes(contents1)
        existing2.writeBytes(contents2)

        val partialFile = File(destDir, "data.bin${ProtocolConstants.PARTIAL_FILE_SUFFIX}")
        partialFile.writeBytes(incomingBytes.copyOfRange(0, partialSize))
        assertEquals(partialSize.toLong(), partialFile.length())

        val incoming = File(sourceDir, "data.bin")
        incoming.writeBytes(incomingBytes)
        val incomingSha = incoming.inputStream().use { ChecksumCalculator.calculate(it) }

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val sender = TransferSender(deviceName = "D73Sender")
        val receiver = TransferReceiver(deviceName = "D73Receiver")

        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "D73Sender",
            files = listOf(
                ManifestFileItem(
                    "d73",
                    "data.bin",
                    incoming.length(),
                    "application/octet-stream",
                    incoming.lastModified()
                )
            )
        )

        val receiverDeferred = async(Dispatchers.IO) {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = true)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)
        val sendRes = sender.transfer(clientConn, manifest, mapOf("d73" to LocalFileSource(incoming, "d73")))
        val recvRes = receiverDeferred.await()

        assertTrue("Sender failed: ${sendRes.exceptionOrNull()?.message}", sendRes.isSuccess)
        assertTrue("Receiver failed: ${recvRes.exceptionOrNull()?.message}", recvRes.isSuccess)
        assertTrue(receiver.state.value is TransferState.Completed)

        val finalFile = File(destDir, "data.bin")
        assertTrue(finalFile.exists())
        assertEquals(payloadSize.toLong(), finalFile.length())
        assertArrayEquals(incomingBytes, finalFile.readBytes())
        assertEquals(incomingSha, finalFile.inputStream().use { ChecksumCalculator.calculate(it) })
        assertArrayEquals(incomingBytes.copyOfRange(0, partialSize), finalFile.readBytes().copyOfRange(0, partialSize))
        assertArrayEquals(
            incomingBytes.copyOfRange(partialSize, payloadSize),
            finalFile.readBytes().copyOfRange(partialSize, payloadSize)
        )

        assertTrue(existing1.exists())
        assertTrue(existing2.exists())
        assertArrayEquals(contents1, existing1.readBytes())
        assertArrayEquals(contents2, existing2.readBytes())

        assertFalse(File(destDir, "data_3.bin").exists())
        assertFalse(File(destDir, "data_4.bin").exists())
        assertFalse(partialFile.exists())
        destDir.listFiles()?.forEach { file ->
            assertFalse(file.name.endsWith(ProtocolConstants.PARTIAL_FILE_SUFFIX))
        }
    }
}

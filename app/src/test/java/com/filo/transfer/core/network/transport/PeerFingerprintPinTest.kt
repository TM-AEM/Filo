package com.filo.transfer.core.network.transport

import com.filo.transfer.core.network.model.ManifestFileItem
import com.filo.transfer.core.network.model.NetworkError
import com.filo.transfer.core.network.model.TransferManifest
import com.filo.transfer.core.network.protocol.FrameType
import com.filo.transfer.core.network.security.handshake.HandshakeFraming
import com.filo.transfer.core.network.security.handshake.InMemorySigningIdentity
import com.filo.transfer.core.network.security.handshake.SecureHandshake
import com.filo.transfer.core.network.security.handshake.SecureSession
import com.filo.transfer.core.network.security.handshake.SigningIdentity
import com.filo.transfer.core.network.transfer.LocalFileSource
import com.filo.transfer.core.network.transfer.TransferReceiver
import com.filo.transfer.core.network.transfer.TransferSender
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.util.UUID

class PeerFingerprintPinTest {

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

    private fun fingerprintOf(identity: SigningIdentity): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.getIdentityPublicKey())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun validFingerprint(): String = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun matchingExpectedFingerprintAllowsPinnedTransfer() {
        val sourceDir = tempFolder.newFolder("pinOkSource")
        val destDir = tempFolder.newFolder("pinOkDest")
        val file = java.io.File(sourceDir, "ok.bin")
        file.writeBytes(ByteArray(4096) { it.toByte() })

        val receiverIdentity = InMemorySigningIdentity.generate()
        val expected = fingerprintOf(receiverIdentity)
        assertEquals(expected, SecureSession.computeFingerprint(receiverIdentity.getIdentityPublicKey()))

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val sender = TransferSender(
            signingIdentity = InMemorySigningIdentity.generate(),
            expectedPeerFingerprint = expected
        )
        val receiver = TransferReceiver(signingIdentity = receiverIdentity)
        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "PinSender",
            files = listOf(
                ManifestFileItem("f1", file.name, file.length(), "application/octet-stream", file.lastModified())
            )
        )

        runBlocking {
            val receiverDeferred = async {
                val serverConn = server.accept()
                receiver.receive(serverConn, destDir)
            }
            val clientConn = TcpClientTransport.connect("127.0.0.1", port)
            val sendRes = sender.transfer(clientConn, manifest, mapOf("f1" to LocalFileSource(file, "f1")))
            val recvRes = receiverDeferred.await()

            assertTrue("Pinned sender failed: ${sendRes.exceptionOrNull()}", sendRes.isSuccess)
            assertTrue("Pinned receiver failed: ${recvRes.exceptionOrNull()}", recvRes.isSuccess)
            assertTrue(java.io.File(destDir, file.name).exists())
        }
    }

    @Test
    fun mismatchedExpectedFingerprintFailsClosedWithoutApplicationFrames() {
        val sourceDir = tempFolder.newFolder("pinBadSource")
        val destDir = tempFolder.newFolder("pinBadDest")
        val file = java.io.File(sourceDir, "secret.bin")
        file.writeBytes(ByteArray(2048) { 7 })

        val receiverIdentity = InMemorySigningIdentity.generate()
        val wrongIdentity = InMemorySigningIdentity.generate()
        val wrongFp = fingerprintOf(wrongIdentity)
        assertFalse(wrongFp == fingerprintOf(receiverIdentity))

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val sender = TransferSender(
            signingIdentity = InMemorySigningIdentity.generate(),
            expectedPeerFingerprint = wrongFp
        )
        val receiver = TransferReceiver(signingIdentity = receiverIdentity)
        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "PinSender",
            files = listOf(
                ManifestFileItem("f1", file.name, file.length(), "application/octet-stream", file.lastModified())
            )
        )

        runBlocking {
            val receiverDeferred = async {
                val serverConn = server.accept()
                receiver.receive(serverConn, destDir)
            }
            val clientConn = TcpClientTransport.connect("127.0.0.1", port)
            val sendRes = sender.transfer(clientConn, manifest, mapOf("f1" to LocalFileSource(file, "f1")))
            val recvRes = receiverDeferred.await()

            assertTrue(sendRes.isFailure)
            val error = sendRes.exceptionOrNull()
            assertTrue("expected PeerFingerprintMismatch, got $error", error is NetworkError.PeerFingerprintMismatch)
            assertTrue(clientConn.isClosed)
            assertTrue(recvRes.isFailure)
            assertFalse(java.io.File(destDir, file.name).exists())
            assertEquals(0, destDir.listFiles()?.count { it.isFile } ?: 0)
        }
    }

    @Test
    fun mismatchClosesBeforeHandshakeFinishAndSendsNoManifest() {
        val receiverIdentity = InMemorySigningIdentity.generate()
        val wrongFp = fingerprintOf(InMemorySigningIdentity.generate())

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val observed = mutableListOf<FrameType>()
        runBlocking {
            val responderDeferred = async {
                val serverConn = server.accept()
                try {
                    val hello = serverConn.receiveHandshakeFrame()
                    observed.add(hello.type)
                    val initiatorMsg = HandshakeFraming.decodeHello(hello.payload)
                    val responderState = SecureHandshake.respond(receiverIdentity, initiatorMsg)
                    serverConn.sendHandshakeFrame(
                        FrameType.HELLO_ACK,
                        HandshakeFraming.helloAckPayload(
                            responderState.localMessage,
                            responderState.fullTranscriptSignature
                        )
                    )
                    val next = serverConn.receiveHandshakeFrame()
                    observed.add(next.type)
                } catch (_: Throwable) {
                    // sender must close rather than continue into application frames
                } finally {
                    serverConn.close()
                }
            }

            val file = tempFolder.newFile("probe.bin")
            file.writeBytes(byteArrayOf(1, 2, 3))
            val sender = TransferSender(
                signingIdentity = InMemorySigningIdentity.generate(),
                expectedPeerFingerprint = wrongFp
            )
            val clientConn = TcpClientTransport.connect("127.0.0.1", port)
            val sendRes = sender.transfer(
                clientConn,
                TransferManifest(
                    transferId = "tx-pin",
                    senderDeviceName = "PinSender",
                    files = listOf(
                        ManifestFileItem("f1", file.name, file.length(), "application/octet-stream", file.lastModified())
                    )
                ),
                mapOf("f1" to LocalFileSource(file, "f1"))
            )
            responderDeferred.await()

            assertTrue(sendRes.exceptionOrNull() is NetworkError.PeerFingerprintMismatch)
            assertTrue(clientConn.isClosed)
            assertFalse(observed.contains(FrameType.MANIFEST))
            assertFalse(observed.contains(FrameType.FILE_HEADER))
            assertFalse(observed.contains(FrameType.DATA_CHUNK))
            assertFalse(observed.contains(FrameType.HANDSHAKE_FINISH))
            assertFalse(observed.contains(FrameType.ERROR))
        }
    }

    @Test
    fun malformedExpectedFingerprintIsRejected() {
        assertRejectedPin("abc")
        assertRejectedPin(validFingerprint() + "0")
        assertRejectedPin(validFingerprint().dropLast(1))
        assertRejectedPin("")
    }

    @Test
    fun uppercaseExpectedFingerprintIsRejected() {
        assertRejectedPin(validFingerprint().uppercase())
    }

    @Test
    fun missingExpectationPreservesUnpinnedTransfer() {
        val sourceDir = tempFolder.newFolder("unpinSource")
        val destDir = tempFolder.newFolder("unpinDest")
        val file = java.io.File(sourceDir, "plain.bin")
        file.writeBytes(ByteArray(1024) { 3 })

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val sender = TransferSender(signingIdentity = InMemorySigningIdentity.generate())
        val receiver = TransferReceiver(signingIdentity = InMemorySigningIdentity.generate())
        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "NsdSender",
            files = listOf(
                ManifestFileItem("f1", file.name, file.length(), "application/octet-stream", file.lastModified())
            )
        )

        runBlocking {
            val receiverDeferred = async {
                val serverConn = server.accept()
                receiver.receive(serverConn, destDir)
            }
            val clientConn = TcpClientTransport.connect("127.0.0.1", port)
            val sendRes = sender.transfer(clientConn, manifest, mapOf("f1" to LocalFileSource(file, "f1")))
            val recvRes = receiverDeferred.await()
            assertTrue(sendRes.isSuccess)
            assertTrue(recvRes.isSuccess)
            assertTrue(java.io.File(destDir, file.name).exists())
        }
    }

    private fun assertRejectedPin(fingerprint: String) {
        val destDir = tempFolder.newFolder("reject-${fingerprint.hashCode()}")
        val file = tempFolder.newFile("reject-${fingerprint.hashCode()}.bin")
        file.writeBytes(byteArrayOf(9))

        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val sender = TransferSender(
            signingIdentity = InMemorySigningIdentity.generate(),
            expectedPeerFingerprint = fingerprint
        )
        val receiver = TransferReceiver(signingIdentity = InMemorySigningIdentity.generate())
        runBlocking {
            val receiverDeferred = async {
                val serverConn = server.accept()
                receiver.receive(serverConn, destDir)
            }
            val clientConn = TcpClientTransport.connect("127.0.0.1", port)
            val sendRes = sender.transfer(
                clientConn,
                TransferManifest(
                    transferId = "tx-badfp",
                    senderDeviceName = "PinSender",
                    files = listOf(
                        ManifestFileItem("f1", file.name, file.length(), "application/octet-stream", file.lastModified())
                    )
                ),
                mapOf("f1" to LocalFileSource(file, "f1"))
            )
            receiverDeferred.await()
            assertTrue(sendRes.exceptionOrNull() is NetworkError.PeerFingerprintMismatch)
            assertTrue(clientConn.isClosed)
            assertEquals(0, destDir.listFiles()?.count { it.isFile } ?: 0)
        }
        try {
            server.close()
        } catch (_: Throwable) {}
        serverTransport = null
    }
}

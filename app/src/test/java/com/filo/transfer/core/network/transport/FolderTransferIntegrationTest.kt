package com.filo.transfer.core.network.transport

import com.filo.transfer.core.network.model.ManifestFileItem
import com.filo.transfer.core.network.model.NetworkError
import com.filo.transfer.core.network.model.TransferManifest
import com.filo.transfer.core.network.protocol.ProtocolConstants
import com.filo.transfer.core.network.security.handshake.InMemorySigningIdentity
import com.filo.transfer.core.network.transfer.ChecksumCalculator
import com.filo.transfer.core.network.transfer.LocalFileSource
import com.filo.transfer.core.network.transfer.TransferFileSource
import com.filo.transfer.core.network.transfer.TransferReceiver
import com.filo.transfer.core.network.transfer.TransferSender
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class FolderTransferIntegrationTest {

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
        parent.mkdirs()
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

    private fun shaOf(file: File): String = file.inputStream().use { ChecksumCalculator.calculate(it) }

    private fun runTransfer(
        manifest: TransferManifest,
        sources: Map<String, TransferFileSource>,
        allowResume: Boolean = true
    ): Pair<Result<Unit>, Result<TransferManifest>> = runBlocking {
        val server = TcpServerTransport()
        serverTransport = server
        val port = server.bind(0)

        val sender = TransferSender(signingIdentity = InMemorySigningIdentity.generate())
        val receiver = TransferReceiver(signingIdentity = InMemorySigningIdentity.generate())

        val receiverDeferred = async {
            val serverConn = server.accept()
            receiver.receive(serverConn, destDir, allowResume = allowResume)
        }

        val clientConn = TcpClientTransport.connect("127.0.0.1", port)
        val sendRes = sender.transfer(clientConn, manifest, sources)
        val recvRes = receiverDeferred.await()

        sendRes to recvRes
    }

    @Test
    fun folderTreeIsRebuiltOnReceiver() = runBlocking {
        val rootFile = createTestFile(File(sourceDir, "project"), "readme.md", 4 * 1024)
        val srcFile = createTestFile(File(sourceDir, "project/src"), "main.kt", 28 * 1024)
        val assetFile = createTestFile(File(sourceDir, "project/assets/images"), "logo.png", 64 * 1024)

        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "FolderSender",
            files = listOf(
                ManifestFileItem("f0", "readme.md", rootFile.length(), "text/markdown", rootFile.lastModified(), "readme.md"),
                ManifestFileItem("f1", "main.kt", srcFile.length(), "text/x-kotlin", srcFile.lastModified(), "src/main.kt"),
                ManifestFileItem("f2", "logo.png", assetFile.length(), "image/png", assetFile.lastModified(), "assets/images/logo.png")
            )
        )
        val sources = mapOf(
            "f0" to LocalFileSource(rootFile, "f0"),
            "f1" to LocalFileSource(srcFile, "f1"),
            "f2" to LocalFileSource(assetFile, "f2")
        )

        val (sendRes, recvRes) = runTransfer(manifest, sources)

        assertTrue("Sender failed: ${sendRes.exceptionOrNull()?.message}", sendRes.isSuccess)
        assertTrue("Receiver failed: ${recvRes.exceptionOrNull()?.message}", recvRes.isSuccess)

        val receivedRoot = File(destDir, "readme.md")
        val receivedSrc = File(destDir, "src/main.kt")
        val receivedAsset = File(destDir, "assets/images/logo.png")

        assertTrue(receivedRoot.exists())
        assertTrue(receivedSrc.exists())
        assertTrue(receivedAsset.exists())

        assertEquals(shaOf(rootFile), shaOf(receivedRoot))
        assertEquals(shaOf(srcFile), shaOf(receivedSrc))
        assertEquals(shaOf(assetFile), shaOf(receivedAsset))
    }

    @Test
    fun sameNameFilesInDifferentSubdirsDoNotCollide() = runBlocking {
        val a = createTestFile(File(sourceDir, "a"), "config.json", 2 * 1024)
        val b = createTestFile(File(sourceDir, "b"), "config.json", 3 * 1024)

        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "FolderSender",
            files = listOf(
                ManifestFileItem("f0", "config.json", a.length(), "application/json", a.lastModified(), "a/config.json"),
                ManifestFileItem("f1", "config.json", b.length(), "application/json", b.lastModified(), "b/config.json")
            )
        )
        val sources = mapOf(
            "f0" to LocalFileSource(a, "f0"),
            "f1" to LocalFileSource(b, "f1")
        )

        val (sendRes, recvRes) = runTransfer(manifest, sources)

        assertTrue(sendRes.isSuccess)
        assertTrue(recvRes.isSuccess)

        val receivedA = File(destDir, "a/config.json")
        val receivedB = File(destDir, "b/config.json")

        assertTrue(receivedA.exists())
        assertTrue(receivedB.exists())
        assertEquals(a.length(), receivedA.length())
        assertEquals(b.length(), receivedB.length())
        assertEquals(shaOf(a), shaOf(receivedA))
        assertEquals(shaOf(b), shaOf(receivedB))
    }

    @Test
    fun emptyRelativePathStillLandsFlatInDestination() = runBlocking {
        val flatFile = createTestFile(sourceDir, "flat.txt", 8 * 1024)

        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "FlatSender",
            files = listOf(
                ManifestFileItem("f0", "flat.txt", flatFile.length(), "text/plain", flatFile.lastModified(), "")
            )
        )
        val sources = mapOf("f0" to LocalFileSource(flatFile, "f0"))

        val (sendRes, recvRes) = runTransfer(manifest, sources)

        assertTrue(sendRes.isSuccess)
        assertTrue(recvRes.isSuccess)

        val received = File(destDir, "flat.txt")
        assertTrue(received.exists())
        assertEquals(shaOf(flatFile), shaOf(received))
        assertFalse(File(destDir, ".filo.part").exists())
    }

    @Test
    fun mixedFlatAndNestedFilesTransferTogether() = runBlocking {
        val flat = createTestFile(sourceDir, "top.txt", 4 * 1024)
        val nested = createTestFile(File(sourceDir, "sub"), "nested.txt", 4 * 1024)

        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "MixedSender",
            files = listOf(
                ManifestFileItem("f0", "top.txt", flat.length(), "text/plain", flat.lastModified(), ""),
                ManifestFileItem("f1", "nested.txt", nested.length(), "text/plain", nested.lastModified(), "sub/nested.txt")
            )
        )
        val sources = mapOf(
            "f0" to LocalFileSource(flat, "f0"),
            "f1" to LocalFileSource(nested, "f1")
        )

        val (sendRes, recvRes) = runTransfer(manifest, sources)

        assertTrue(sendRes.isSuccess)
        assertTrue(recvRes.isSuccess)

        assertTrue(File(destDir, "top.txt").exists())
        assertTrue(File(destDir, "sub/nested.txt").exists())
    }

    @Test
    fun traversalRelativePathIsRejectedAndNothingEscapesDestination() = runBlocking {
        val evil = createTestFile(sourceDir, "evil.txt", 4 * 1024)

        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "EvilSender",
            files = listOf(
                ManifestFileItem("f0", "evil.txt", evil.length(), "text/plain", evil.lastModified(), "../../evil.txt")
            )
        )
        val sources = mapOf("f0" to LocalFileSource(evil, "f0"))

        val (sendRes, recvRes) = runTransfer(manifest, sources, allowResume = false)

        // The peer must be rejected outright; the receiver must fail rather than write outside.
        assertTrue(recvRes.isFailure)
        val cause = recvRes.exceptionOrNull()
        assertNotNull(cause)
        assertTrue("Expected UnsafeRelativePath, got $cause", cause is NetworkError.UnsafeRelativePath)

        val escaped = File(destDir.parentFile, "evil.txt")
        assertFalse("File escaped destination root", escaped.exists())
        assertFalse("Nothing should remain in destination", destDir.listFiles()?.isNotEmpty() == true)
    }

    @Test
    fun absoluteRelativePathIsRejected() = runBlocking {
        val evil = createTestFile(sourceDir, "evil.txt", 4 * 1024)

        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "EvilSender",
            files = listOf(
                ManifestFileItem("f0", "evil.txt", evil.length(), "text/plain", evil.lastModified(), "/evil.txt")
            )
        )
        val sources = mapOf("f0" to LocalFileSource(evil, "f0"))

        val (sendRes, recvRes) = runTransfer(manifest, sources, allowResume = false)

        assertTrue(recvRes.isFailure)
        assertTrue(recvRes.exceptionOrNull() is NetworkError.UnsafeRelativePath)
        assertFalse(File(destDir, "evil.txt").exists())
    }

    @Test
    fun nestedFileResumeCompletesIntoItsSubdirectory() = runBlocking {
        val full = createTestFile(File(sourceDir, "data"), "backup.zip", 300 * 1024)
        val fullSha = shaOf(full)

        // Pre-seed a partial file inside the nested destination directory.
        val nestedDir = File(destDir, "data")
        nestedDir.mkdirs()
        val partial = File(nestedDir, "backup.zip${ProtocolConstants.PARTIAL_FILE_SUFFIX}")
        full.inputStream().use { input ->
            FileOutputStream(partial).use { output ->
                val buf = ByteArray(100 * 1024)
                input.read(buf)
                output.write(buf)
            }
        }
        assertEquals(100 * 1024L, partial.length())

        val manifest = TransferManifest(
            transferId = UUID.randomUUID().toString(),
            senderDeviceName = "ResumeSender",
            files = listOf(
                ManifestFileItem("f0", "backup.zip", full.length(), "application/zip", full.lastModified(), "data/backup.zip")
            )
        )
        val sources = mapOf("f0" to LocalFileSource(full, "f0"))

        val (sendRes, recvRes) = runTransfer(manifest, sources, allowResume = true)

        assertTrue("Sender failed: ${sendRes.exceptionOrNull()?.message}", sendRes.isSuccess)
        assertTrue("Receiver failed: ${recvRes.exceptionOrNull()?.message}", recvRes.isSuccess)

        val received = File(destDir, "data/backup.zip")
        assertTrue(received.exists())
        assertEquals(full.length(), received.length())
        assertEquals(fullSha, shaOf(received))
        assertFalse(partial.exists())
    }
}

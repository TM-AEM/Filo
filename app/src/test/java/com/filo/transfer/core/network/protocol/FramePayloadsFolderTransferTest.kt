package com.filo.transfer.core.network.protocol

import com.filo.transfer.core.network.model.ManifestFileItem
import com.filo.transfer.core.network.model.NetworkError
import com.filo.transfer.core.network.model.TransferManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePayloadsFolderTransferTest {

    private fun sampleManifest(relativePath: String): TransferManifest = TransferManifest(
        transferId = "tx-folder-1",
        senderDeviceName = "SenderDevice",
        files = listOf(
            ManifestFileItem(
                fileId = "file-0",
                fileName = "report.pdf",
                size = 4096L,
                mimeType = "application/pdf",
                lastModified = 1700000000000L,
                relativePath = relativePath
            ),
            ManifestFileItem(
                fileId = "file-1",
                fileName = "notes.txt",
                size = 128L,
                mimeType = "text/plain",
                lastModified = 1700000000001L,
                relativePath = if (relativePath.isEmpty()) "" else "sub/notes.txt"
            )
        )
    )

    @Test
    fun manifestWithRelativePathRoundTrips() {
        val manifest = sampleManifest("docs/work/report.pdf")

        val decoded = FramePayloads.decodeManifest(FramePayloads.encodeManifest(manifest))

        assertEquals(manifest.transferId, decoded.transferId)
        assertEquals(2, decoded.files.size)
        assertEquals("docs/work/report.pdf", decoded.files[0].relativePath)
        assertEquals("sub/notes.txt", decoded.files[1].relativePath)
    }

    @Test
    fun manifestWithoutRelativePathDecodesToEmptyString() {
        val manifest = sampleManifest("")

        val decoded = FramePayloads.decodeManifest(FramePayloads.encodeManifest(manifest))

        assertEquals("", decoded.files[0].relativePath)
        assertEquals("", decoded.files[1].relativePath)
    }

    @Test
    fun manifestWithDeepRelativePathRoundTrips() {
        val deep = List(10) { "dir" }.joinToString("/") + "/file.txt"
        val manifest = sampleManifest(deep)

        val decoded = FramePayloads.decodeManifest(FramePayloads.encodeManifest(manifest))

        assertEquals(deep, decoded.files[0].relativePath)
    }

    @Test
    fun manifestRejectsTraversalInRelativePath() {
        val manifestPayload = encodeManifestRaw("docs/../../etc/passwd")

        val error = assertThrows(NetworkError.UnsafeRelativePath::class.java) {
            FramePayloads.decodeManifest(manifestPayload)
        }

        assertTrue(error.message!!.contains("relative path"))
    }

    @Test
    fun manifestRejectsAbsoluteRelativePath() {
        val manifestPayload = encodeManifestRaw("/etc/passwd")

        assertThrows(NetworkError.UnsafeRelativePath::class.java) {
            FramePayloads.decodeManifest(manifestPayload)
        }
    }

    @Test
    fun manifestRejectsBackslashRelativePath() {
        val manifestPayload = encodeManifestRaw("docs\\file.txt")

        assertThrows(NetworkError.UnsafeRelativePath::class.java) {
            FramePayloads.decodeManifest(manifestPayload)
        }
    }

    @Test
    fun fileHeaderWithRelativePathRoundTrips() {
        val payload = FramePayloads.encodeFileHeader(
            fileIndex = 2,
            totalFiles = 5,
            fileId = "file-2",
            fileName = "photo.jpg",
            fileSize = 999L,
            mimeType = "image/jpeg",
            relativePath = "vacation/2024/photo.jpg"
        )

        val decoded = FramePayloads.decodeFileHeader(payload)

        assertEquals(2, decoded.fileIndex)
        assertEquals(5, decoded.totalFiles)
        assertEquals("file-2", decoded.fileId)
        assertEquals("photo.jpg", decoded.fileName)
        assertEquals(999L, decoded.fileSize)
        assertEquals("image/jpeg", decoded.mimeType)
        assertEquals("vacation/2024/photo.jpg", decoded.relativePath)
    }

    @Test
    fun fileHeaderWithoutRelativePathDecodesToEmptyString() {
        val payload = FramePayloads.encodeFileHeader(
            fileIndex = 0,
            totalFiles = 1,
            fileId = "file-0",
            fileName = "photo.jpg",
            fileSize = 999L,
            mimeType = "image/jpeg"
        )

        val decoded = FramePayloads.decodeFileHeader(payload)

        assertEquals("", decoded.relativePath)
    }

    @Test
    fun fileHeaderRejectsTraversalInRelativePath() {
        val payload = FramePayloads.encodeFileHeader(
            fileIndex = 0,
            totalFiles = 1,
            fileId = "file-0",
            fileName = "name.txt",
            fileSize = 1L,
            mimeType = "text/plain",
            relativePath = "../escape.txt"
        )

        assertThrows(NetworkError.UnsafeRelativePath::class.java) {
            FramePayloads.decodeFileHeader(payload)
        }
    }

    @Test
    fun fileHeaderSanitizesFileNameButPreservesRelativePath() {
        val payload = FramePayloads.encodeFileHeader(
            fileIndex = 0,
            totalFiles = 1,
            fileId = "file-0",
            fileName = "weird/name?.txt",
            fileSize = 1L,
            mimeType = "text/plain",
            relativePath = "ok/file.txt"
        )

        val decoded = FramePayloads.decodeFileHeader(payload)

        assertEquals("name_.txt", decoded.fileName)
        assertEquals("ok/file.txt", decoded.relativePath)
    }

    @Test
    fun legacyManifestWithoutOptionalFieldStillDecodes() {
        // A manifest payload written by a peer that never knew about relativePath:
        // identical to encodeManifest but without the trailing optional field.
        val legacyPayload = encodeLegacyManifest()

        val decoded = FramePayloads.decodeManifest(legacyPayload)

        assertEquals(1, decoded.files.size)
        assertEquals("legacy.txt", decoded.files[0].fileName)
        assertEquals("", decoded.files[0].relativePath)
    }

    @Test
    fun legacyFileHeaderWithoutOptionalFieldStillDecodes() {
        val legacyPayload = encodeLegacyFileHeader()

        val decoded = FramePayloads.decodeFileHeader(legacyPayload)

        assertEquals("legacy.txt", decoded.fileName)
        assertEquals("", decoded.relativePath)
    }

    /**
     * Encodes a manifest where the relativePath field is malicious, bypassing the sender-side
     * validation that [FramePayloads.encodeManifest] does not perform (the field is attacker input
     * from the network, so decoding must be the trust boundary).
     */
    private fun encodeManifestRaw(relativePath: String): ByteArray {
        val bout = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bout).use { dos ->
            dos.writeUTF("tx-evil")
            dos.writeUTF("EvilDevice")
            dos.writeInt(1)
            dos.writeUTF("file-0")
            dos.writeUTF("passwd")
            dos.writeLong(100L)
            dos.writeUTF("text/plain")
            dos.writeLong(0L)
            dos.writeUTF(relativePath)
        }
        return bout.toByteArray()
    }

    private fun encodeLegacyManifest(): ByteArray {
        val bout = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bout).use { dos ->
            dos.writeUTF("tx-legacy")
            dos.writeUTF("LegacyDevice")
            dos.writeInt(1)
            dos.writeUTF("file-0")
            dos.writeUTF("legacy.txt")
            dos.writeLong(100L)
            dos.writeUTF("text/plain")
            dos.writeLong(0L)
        }
        return bout.toByteArray()
    }

    private fun encodeLegacyFileHeader(): ByteArray {
        val bout = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bout).use { dos ->
            dos.writeInt(0)
            dos.writeInt(1)
            dos.writeUTF("file-0")
            dos.writeUTF("legacy.txt")
            dos.writeLong(100L)
            dos.writeUTF("text/plain")
        }
        return bout.toByteArray()
    }
}

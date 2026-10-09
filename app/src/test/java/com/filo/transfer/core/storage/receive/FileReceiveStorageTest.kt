package com.filo.transfer.core.storage.receive

import com.filo.transfer.core.network.protocol.ProtocolConstants
import com.filo.transfer.core.network.security.FilenameValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileReceiveStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun createPartialDestinationUsesSafeFilenameAndSuffix() {
        val dir = tempFolder.newFolder("incoming")
        val storage = FileReceiveStorage(dir)
        val safeName = FilenameValidator.sanitize("photo.jpg")
        val partial = storage.createPartialDestination(safeName)

        assertEquals("photo.jpg${ProtocolConstants.PARTIAL_FILE_SUFFIX}", partial.path.name)
        assertEquals("photo.jpg${ProtocolConstants.PARTIAL_FILE_SUFFIX}.meta", partial.metadataPath.name)
        assertEquals(dir, partial.path.parentFile)
    }

    @Test
    fun metadataBindsTransferIdAndFileId() {
        val dir = tempFolder.newFolder("incoming")
        val storage = FileReceiveStorage(dir)
        val partial = storage.createPartialDestination("data.bin")

        assertTrue(partial.markMetadata("transfer-1", "file-a"))
        assertEquals(Pair("transfer-1", "file-a"), partial.readMetadata())
    }

    @Test
    fun missingMetadataIsNotValidResumeState() {
        val dir = tempFolder.newFolder("incoming")
        val storage = FileReceiveStorage(dir)
        val partial = storage.createPartialDestination("data.bin")
        partial.path.writeBytes(ByteArray(16) { 1 })

        assertNull(partial.readMetadata())
        assertEquals(16L, partial.resumeOffset)
    }

    @Test
    fun finalizeRenamesPartialAndDeletesMetadata() {
        val dir = tempFolder.newFolder("incoming")
        val storage = FileReceiveStorage(dir)
        val partial = storage.createPartialDestination("report.pdf")
        partial.path.writeBytes(byteArrayOf(1, 2, 3))
        partial.markMetadata("t1", "f1")

        val completed = storage.finalizeDestination(partial, "report.pdf")
        assertNotNull(completed)
        assertTrue(File(dir, "report.pdf").exists())
        assertEquals(3L, File(dir, "report.pdf").length())
        assertFalse(partial.path.exists())
        assertFalse(partial.metadataPath.exists())
    }

    @Test
    fun cleanupRemovesPartialAndMetadata() {
        val dir = tempFolder.newFolder("incoming")
        val storage = FileReceiveStorage(dir)
        val partial = storage.createPartialDestination("fail.bin")
        partial.path.writeBytes(byteArrayOf(9))
        partial.markMetadata("t1", "f1")

        assertTrue(storage.cleanupDestination(partial))
        assertFalse(partial.path.exists())
        assertFalse(partial.metadataPath.exists())
        assertFalse(File(dir, "fail.bin").exists())
    }

    @Test
    fun pathTraversalFilenameCannotEscapeDestination() {
        val dir = tempFolder.newFolder("incoming")
        val storage = FileReceiveStorage(dir)
        val safeName = FilenameValidator.sanitize("../etc/passwd")
        val partial = storage.createPartialDestination(safeName)

        assertEquals(dir.canonicalPath, partial.path.parentFile?.canonicalPath)
        assertFalse(partial.path.canonicalPath.contains(".."))
        assertTrue(partial.path.name.endsWith(ProtocolConstants.PARTIAL_FILE_SUFFIX))
    }

    @Test
    fun noOpPublisherDoesNotDeleteCompletedFile() {
        val dir = tempFolder.newFolder("incoming")
        val completed = File(dir, "keep.bin")
        completed.writeBytes(byteArrayOf(1, 2))

        assertTrue(NoOpCompletedFilePublisher.publish(completed, "keep.bin", "application/octet-stream"))
        assertTrue(completed.exists())
    }
}

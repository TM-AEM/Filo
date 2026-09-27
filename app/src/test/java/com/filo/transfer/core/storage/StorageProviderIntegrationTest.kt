package com.filo.transfer.core.storage

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.filo.transfer.core.storage.model.FileMediaType
import com.filo.transfer.core.storage.provider.DefaultStorageProvider
import com.filo.transfer.core.storage.provider.SafPermissionManager
import com.filo.transfer.core.storage.provider.SeekCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StorageProviderIntegrationTest {

    private lateinit var context: Context
    private lateinit var storageProvider: DefaultStorageProvider
    private lateinit var safManager: SafPermissionManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storageProvider = DefaultStorageProvider(context)
        safManager = SafPermissionManager(context.contentResolver)
    }

    @Test
    fun `default storage provider end to end metadata and stream reading`() {
        val tempFile = File.createTempFile("filo_doc", ".pdf", context.cacheDir)
        tempFile.writeText("%PDF-1.4 Mock Document Content")

        val uri = Uri.fromFile(tempFile)

        // 1. Check Metadata
        val metaResult = storageProvider.getFileMetadata(uri)
        assertTrue(metaResult.isSuccess)
        val file = metaResult.getOrNull()
        assertNotNull(file)
        assertEquals(FileMediaType.DOCUMENT, file?.mediaType)
        assertEquals(tempFile.name, file?.displayName)
        assertTrue((file?.size ?: 0) > 0)

        // 2. Check Stream
        val streamResult = storageProvider.openInputStream(uri)
        assertTrue(streamResult.isSuccess)
        val content = streamResult.getOrNull()?.use { it.bufferedReader().readText() }
        assertEquals("%PDF-1.4 Mock Document Content", content)

        // 3. Check Seek Capability
        val seekCap = storageProvider.getSeekCapability(uri)
        assertTrue(seekCap == SeekCapability.SEEKABLE_DESCRIPTOR || seekCap == SeekCapability.STREAM_SKIP_ONLY)

        tempFile.delete()
    }

    @Test
    fun `saf permission manager handles null uri safely`() {
        val result = safManager.takePersistablePermission(null)
        assertTrue(result.isFailure)

        val releaseResult = safManager.releasePersistablePermission(null)
        assertTrue(releaseResult.isFailure)

        val hasPerm = safManager.hasPersistedPermission(null)
        assertFalse(hasPerm)
    }
}

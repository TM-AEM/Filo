package com.filo.transfer.core.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.filo.transfer.core.storage.model.FileMediaType
import com.filo.transfer.core.storage.model.StorageError
import com.filo.transfer.core.storage.provider.FileMetadataResolver
import org.junit.Assert.assertEquals
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
class FileMetadataResolverTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var metadataResolver: FileMetadataResolver

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        contentResolver = context.contentResolver
        metadataResolver = FileMetadataResolver(contentResolver)
    }

    @Test
    fun `resolve null uri returns InvalidUri failure`() {
        val result = metadataResolver.resolve(null)
        assertTrue(result.isFailure)
        assertTrue(result.errorOrNull() is StorageError.InvalidUri)
    }

    @Test
    fun `resolve unsupported uri scheme returns InvalidUri failure`() {
        val uri = Uri.parse("ftp://example.com/test.pdf")
        val result = metadataResolver.resolve(uri)
        assertTrue(result.isFailure)
        assertTrue(result.errorOrNull() is StorageError.InvalidUri)
    }

    @Test
    fun `resolve valid file uri returns correct metadata`() {
        val tempFile = File.createTempFile("filo_test_video", ".mp4", context.cacheDir)
        tempFile.writeBytes(ByteArray(1024)) // 1 KB test file

        val uri = Uri.fromFile(tempFile)
        val result = metadataResolver.resolve(uri)

        assertTrue(result.isSuccess)
        val storageFile = result.getOrNull()
        assertNotNull(storageFile)
        assertEquals(1024L, storageFile?.size)
        assertEquals(tempFile.name, storageFile?.displayName)
        assertEquals(FileMediaType.VIDEO, storageFile?.mediaType)

        tempFile.delete()
    }

    @Test
    fun `resolve non existent file uri returns FileNotFound`() {
        val nonExistentFile = File(context.cacheDir, "non_existent_file.apk")
        val uri = Uri.fromFile(nonExistentFile)
        val result = metadataResolver.resolve(uri)

        assertTrue(result.isFailure)
        assertTrue(result.errorOrNull() is StorageError.FileNotFound)
    }

    @Test
    fun `file media type resolves accurately from various mime types and names`() {
        assertEquals(
            FileMediaType.IMAGE,
            FileMediaType.fromMimeAndName("image/jpeg", "vacation.jpg")
        )
        assertEquals(
            FileMediaType.VIDEO,
            FileMediaType.fromMimeAndName("video/mp4", "movie.mkv")
        )
        assertEquals(
            FileMediaType.AUDIO,
            FileMediaType.fromMimeAndName("audio/mpeg", "song.mp3")
        )
        assertEquals(
            FileMediaType.APK,
            FileMediaType.fromMimeAndName("application/vnd.android.package-archive", "app.apk")
        )
        assertEquals(
            FileMediaType.ARCHIVE,
            FileMediaType.fromMimeAndName("application/zip", "backup.zip")
        )
        assertEquals(
            FileMediaType.DOCUMENT,
            FileMediaType.fromMimeAndName("application/pdf", "document.pdf")
        )
        assertEquals(
            FileMediaType.OTHER,
            FileMediaType.fromMimeAndName("application/octet-stream", "data.xyz")
        )
    }
}

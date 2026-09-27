package com.filo.transfer.core.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.filo.transfer.core.storage.model.StorageError
import com.filo.transfer.core.storage.provider.FileStreamProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FileStreamProviderTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var streamProvider: FileStreamProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        contentResolver = context.contentResolver
        streamProvider = FileStreamProvider(contentResolver)
    }

    @Test
    fun `openInputStream null uri returns InvalidUri`() {
        val result = streamProvider.openInputStream(null)
        assertTrue(result.isFailure)
        assertTrue(result.errorOrNull() is StorageError.InvalidUri)
    }

    @Test
    fun `openInputStream reads file content correctly`() {
        val tempFile = File.createTempFile("stream_test", ".txt", context.cacheDir)
        val testData = "Filo Fast File Transfer Stream Engine".toByteArray(Charsets.UTF_8)
        tempFile.writeBytes(testData)

        val uri = Uri.fromFile(tempFile)
        val result = streamProvider.openInputStream(uri)
        assertTrue(result.isSuccess)

        val inputStream = result.getOrNull()
        assertNotNull(inputStream)

        val readBytes = inputStream?.use { it.readBytes() }
        assertEquals(String(testData), String(readBytes ?: ByteArray(0)))

        tempFile.delete()
    }

    @Test
    fun `openInputStreamAtOffset skips directly to requested position`() {
        val tempFile = File.createTempFile("offset_test", ".txt", context.cacheDir)
        val testData = "0123456789ABCDEF".toByteArray(Charsets.UTF_8)
        tempFile.writeBytes(testData)

        val uri = Uri.fromFile(tempFile)
        val offset = 10L // Position at 'A'
        val result = streamProvider.openInputStreamAtOffset(uri, offset)
        assertTrue(result.isSuccess)

        val inputStream = result.getOrNull()
        assertNotNull(inputStream)

        val buffer = ByteArray(6)
        val bytesRead = inputStream?.use { it.read(buffer) }
        assertEquals(6, bytesRead)
        assertEquals("ABCDEF", String(buffer))

        tempFile.delete()
    }

    @Test
    fun `openInputStreamAtOffset with negative offset returns InvalidUri`() {
        val uri = Uri.parse("content://dummy/file")
        val result = streamProvider.openInputStreamAtOffset(uri, -5L)
        assertTrue(result.isFailure)
        assertTrue(result.errorOrNull() is StorageError.InvalidUri)
    }

    @Test
    fun `openInputStreamAtOffset beyond file length returns SeekUnsupported`() {
        val tempFile = File.createTempFile("short_test", ".txt", context.cacheDir)
        tempFile.writeBytes("Short".toByteArray())

        val uri = Uri.fromFile(tempFile)
        val result = streamProvider.openInputStreamAtOffset(uri, 500L)
        assertTrue(result.isFailure)
        assertTrue(result.errorOrNull() is StorageError.SeekUnsupported)

        tempFile.delete()
    }

    @Test
    fun `streaming large data operates in bounded chunks without full memory allocation`() {
        // Create a 2 MB file using bounded writer
        val tempFile = File.createTempFile("large_test", ".bin", context.cacheDir)
        val totalBytes = 2 * 1024 * 1024L // 2 MB
        val chunkSize = 64 * 1024 // 64 KB buffer

        FileOutputStream(tempFile).use { fos ->
            val chunk = ByteArray(chunkSize) { 0x42.toByte() }
            var written = 0L
            while (written < totalBytes) {
                fos.write(chunk)
                written += chunkSize
            }
        }

        val uri = Uri.fromFile(tempFile)
        val result = streamProvider.openInputStream(uri)
        assertTrue(result.isSuccess)

        var streamedBytes = 0L
        val readBuffer = ByteArray(chunkSize)

        result.getOrNull()?.use { stream ->
            var bytesRead: Int
            while (stream.read(readBuffer).also { bytesRead = it } != -1) {
                streamedBytes += bytesRead
                // Ensure each chunk read is bounded
                assertTrue(bytesRead <= chunkSize)
            }
        }

        assertEquals(totalBytes, streamedBytes)
        tempFile.delete()
    }
}

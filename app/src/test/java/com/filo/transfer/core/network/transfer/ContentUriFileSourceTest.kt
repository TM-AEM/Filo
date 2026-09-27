package com.filo.transfer.core.network.transfer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.filo.transfer.core.storage.provider.FileStreamProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContentUriFileSourceTest {

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
    fun `test ContentUriFileSource reads bytes from offset 0`() {
        val testContent = "Hello Filo Content Uri Streaming Test"
        val testFile = File(context.cacheDir, "source_test.txt").apply {
            writeText(testContent)
        }
        val uri = Uri.fromFile(testFile)

        val source = ContentUriFileSource(
            uri = uri,
            fileId = "file-0",
            fileName = "source_test.txt",
            size = testFile.length(),
            mimeType = "text/plain",
            lastModified = testFile.lastModified(),
            streamProvider = streamProvider
        )

        assertEquals("file-0", source.fileId)
        assertEquals("source_test.txt", source.fileName)
        assertEquals(testContent.length.toLong(), source.size)

        source.openStream(0L).use { stream ->
            val readContent = stream.bufferedReader().readText()
            assertEquals(testContent, readContent)
        }
    }

    @Test
    fun `test ContentUriFileSource streams from requested offset for resume`() {
        val testBytes = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        val testFile = File(context.cacheDir, "offset_test.bin").apply {
            writeBytes(testBytes)
        }
        val uri = Uri.fromFile(testFile)

        val source = ContentUriFileSource(
            uri = uri,
            fileId = "file-offset",
            fileName = "offset_test.bin",
            size = 10L,
            streamProvider = streamProvider
        )

        source.openStream(5L).use { stream ->
            val remaining = stream.readBytes()
            assertEquals(5, remaining.size)
            assertArrayEquals(byteArrayOf(5, 6, 7, 8, 9), remaining)
        }
    }

    @Test
    fun `test ContentUriFileSource throws IOException for non existent URI`() {
        val nonExistentFile = File(context.cacheDir, "does_not_exist_${System.currentTimeMillis()}.txt")
        val invalidUri = Uri.fromFile(nonExistentFile)
        val source = ContentUriFileSource(
            uri = invalidUri,
            fileId = "file-invalid",
            fileName = "unknown.txt",
            size = 100L,
            streamProvider = streamProvider
        )

        assertThrows(IOException::class.java) {
            source.openStream(0L)
        }
    }
}

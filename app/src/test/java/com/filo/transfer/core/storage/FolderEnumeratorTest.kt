package com.filo.transfer.core.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.filo.transfer.core.storage.model.StorageResult
import com.filo.transfer.core.storage.provider.FolderEnumerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.fakes.BaseCursor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderEnumeratorTest {

    private lateinit var context: Context
    private lateinit var contentResolver: android.content.ContentResolver
    private lateinit var enumerator: FolderEnumerator

    private val treeUri = Uri.parse("content://com.example.documents/tree/primary%3AFilo")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        contentResolver = context.contentResolver
        enumerator = FolderEnumerator(contentResolver)
    }

    private class FakeCursor(rows: List<Map<String, String?>>) : BaseCursor() {

        private companion object {
            val COLUMNS = listOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
        }

        private val rows = rows
        private var position = -1

        override fun getCount(): Int = rows.size

        override fun moveToNext(): Boolean {
            position++
            return position < rows.size
        }

        override fun getColumnIndex(columnName: String): Int = COLUMNS.indexOf(columnName)

        override fun getString(column: Int): String? = rows[position][COLUMNS[column]]

        override fun close() {}
    }

    private fun row(documentId: String, displayName: String, mimeType: String?) =
        mapOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID to documentId,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME to displayName,
            DocumentsContract.Document.COLUMN_MIME_TYPE to mimeType
        )

    private fun registerChildren(parentDocumentId: String, vararg rows: Map<String, String?>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        Shadows.shadowOf(contentResolver).setCursor(childrenUri, FakeCursor(rows.toList()))
    }

    @Test
    fun `non tree uri returns failure`() {
        val plainUri = Uri.parse("content://com.example.documents/document/primary%3Afile.txt")

        val result = enumerator.enumerate(plainUri)

        assertTrue(result is StorageResult.Failure)
    }

    @Test
    fun `empty tree returns empty file list`() {
        registerChildren("primary:Filo")

        val result = enumerator.enumerate(treeUri)

        assertTrue(result is StorageResult.Success)
        assertTrue((result as StorageResult.Success).data.isEmpty())
    }

    @Test
    fun `flat tree lists single file at root`() {
        registerChildren("primary:Filo", row("primary:Filo/a.txt", "a.txt", "text/plain"))

        val result = enumerator.enumerate(treeUri)

        assertTrue(result is StorageResult.Success)
        val files = (result as StorageResult.Success).data
        assertEquals(1, files.size)
        assertEquals("a.txt", files[0].relativePath)
        assertEquals(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, "primary:Filo/a.txt"),
            files[0].uri
        )
    }

    @Test
    fun `nested tree produces relative paths`() {
        registerChildren(
            "primary:Filo",
            row("primary:Filo/docs", "docs", DocumentsContract.Document.MIME_TYPE_DIR),
            row("primary:Filo/readme.md", "readme.md", "text/markdown")
        )
        registerChildren(
            "primary:Filo/docs",
            row("primary:Filo/docs/report.pdf", "report.pdf", "application/pdf"),
            row("primary:Filo/docs/sub", "sub", DocumentsContract.Document.MIME_TYPE_DIR)
        )
        registerChildren(
            "primary:Filo/docs/sub",
            row("primary:Filo/docs/sub/notes.txt", "notes.txt", "text/plain")
        )

        val result = enumerator.enumerate(treeUri)

        assertTrue(result is StorageResult.Success)
        val files = (result as StorageResult.Success).data
        assertEquals(3, files.size)

        val byPath = files.associateBy { it.relativePath }
        assertEquals(setOf("readme.md", "docs/report.pdf", "docs/sub/notes.txt"), byPath.keys)

        assertEquals(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, "primary:Filo/docs/report.pdf"),
            byPath["docs/report.pdf"]?.uri
        )
        assertEquals(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, "primary:Filo/docs/sub/notes.txt"),
            byPath["docs/sub/notes.txt"]?.uri
        )
    }

    @Test
    fun `empty directories contribute no entries`() {
        registerChildren(
            "primary:Filo",
            row("primary:Filo/empty", "empty", DocumentsContract.Document.MIME_TYPE_DIR),
            row("primary:Filo/empty2", "empty2", DocumentsContract.Document.MIME_TYPE_DIR),
            row("primary:Filo/a.bin", "a.bin", "application/octet-stream")
        )
        registerChildren("primary:Filo/empty")
        registerChildren("primary:Filo/empty2")

        val result = enumerator.enumerate(treeUri)

        assertTrue(result is StorageResult.Success)
        val files = (result as StorageResult.Success).data
        assertEquals(1, files.size)
        assertEquals("a.bin", files[0].relativePath)
    }

    @Test
    fun `missing provider for a subtree returns success with remaining files`() {
        registerChildren(
            "primary:Filo",
            row("primary:Filo/unreadable", "unreadable", DocumentsContract.Document.MIME_TYPE_DIR),
            row("primary:Filo/ok.txt", "ok.txt", "text/plain")
        )
        // No cursor registered for children of "unreadable": query returns null and is skipped.

        val result = enumerator.enumerate(treeUri)

        assertTrue(result is StorageResult.Success)
        val files = (result as StorageResult.Success).data
        assertEquals(1, files.size)
        assertEquals("ok.txt", files[0].relativePath)
    }

    @Test
    fun `query failure returns failure result`() {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, "primary:Filo")
        Shadows.shadowOf(contentResolver).setCursor(childrenUri, ThrowingCursor())

        val result = enumerator.enumerate(treeUri)

        assertTrue(result is StorageResult.Failure)
    }

    private class ThrowingCursor : BaseCursor() {
        override fun moveToNext(): Boolean = throw RuntimeException("Provider crashed")
    }
}

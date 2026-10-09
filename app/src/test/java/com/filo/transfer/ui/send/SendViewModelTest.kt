package com.filo.transfer.ui.send

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.filo.transfer.core.network.discovery.DiscoveryDevice
import com.filo.transfer.core.network.discovery.DiscoveryService
import com.filo.transfer.core.network.discovery.DiscoveryState
import com.filo.transfer.core.network.discovery.AdvertisingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.fakes.BaseCursor
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SendViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var fakeDiscoveryService: FakeDiscoveryService
    private lateinit var viewModel: SendViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        fakeDiscoveryService = FakeDiscoveryService()
        viewModel = SendViewModel(
            customDiscoveryService = fakeDiscoveryService,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has empty files and zero bytes`() {
        assertTrue(viewModel.selectedFiles.value.isEmpty())
        assertEquals(0L, viewModel.totalBytes.value)
        assertEquals("0 B", viewModel.formattedTotalSize.value)
    }

    @Test
    fun `adding file URIs resolves metadata and calculates total size`() = runTest {
        val fileA = File(context.cacheDir, "docA.txt").apply { writeText("AAAA") }
        val fileB = File(context.cacheDir, "docB.txt").apply { writeText("BBBBBB") }

        val uriA = Uri.fromFile(fileA)
        val uriB = Uri.fromFile(fileB)

        viewModel.addUris(context, listOf(uriA, uriB))
        advanceUntilIdle()

        val files = viewModel.selectedFiles.value
        assertEquals(2, files.size)
        assertEquals("docA.txt", files[0].name)
        assertEquals(4L, files[0].size)
        assertEquals("docB.txt", files[1].name)
        assertEquals(6L, files[1].size)

        assertEquals(10L, viewModel.totalBytes.value)
    }

    @Test
    fun `duplicate URIs are suppressed`() = runTest {
        val file = File(context.cacheDir, "unique.txt").apply { writeText("123") }
        val uri = Uri.fromFile(file)

        viewModel.addUris(context, listOf(uri))
        advanceUntilIdle()
        assertEquals(1, viewModel.selectedFiles.value.size)

        // Add same URI again
        viewModel.addUris(context, listOf(uri))
        advanceUntilIdle()
        assertEquals(1, viewModel.selectedFiles.value.size)
    }

    @Test
    fun `removing a file updates list and total size`() = runTest {
        val fileA = File(context.cacheDir, "removeA.txt").apply { writeText("12345") }
        val fileB = File(context.cacheDir, "removeB.txt").apply { writeText("123") }
        val uriA = Uri.fromFile(fileA)
        val uriB = Uri.fromFile(fileB)

        viewModel.addUris(context, listOf(uriA, uriB))
        advanceUntilIdle()
        assertEquals(2, viewModel.selectedFiles.value.size)

        viewModel.removeFile(uriA)
        advanceUntilIdle()

        assertEquals(1, viewModel.selectedFiles.value.size)
        assertEquals(uriB, viewModel.selectedFiles.value[0].uri)
        assertEquals(3L, viewModel.totalBytes.value)
    }

    @Test
    fun `clearing files resets selection`() = runTest {
        val file = File(context.cacheDir, "clear.txt").apply { writeText("test") }
        viewModel.addUris(context, listOf(Uri.fromFile(file)))
        advanceUntilIdle()
        assertEquals(1, viewModel.selectedFiles.value.size)

        viewModel.clearFiles()
        advanceUntilIdle()
        assertTrue(viewModel.selectedFiles.value.isEmpty())
        assertEquals(0L, viewModel.totalBytes.value)
    }

    @Test
    fun `discovery lifecycle and device selection work correctly`() = runTest {
        viewModel.startDiscovery(context)
        assertTrue(fakeDiscoveryService.isDiscoveryRunning)

        val device = DiscoveryDevice(
            id = "dev-1",
            serviceName = "Filo-Receiver-Test",
            host = "192.168.1.150",
            port = 50222
        )
        fakeDiscoveryService.emitDevice(device)
        advanceUntilIdle()

        assertEquals(1, viewModel.discoveredDevices.value.size)
        assertEquals(device, viewModel.discoveredDevices.value.first())

        val tempFile = File(context.cacheDir, "send_ready.txt").apply { writeText("send payload") }
        viewModel.addUris(context, listOf(Uri.fromFile(tempFile)))
        advanceUntilIdle()

        val transferId = viewModel.startTransfer(context, device)
        assertNotNull(transferId)
        assertTrue(transferId!!.startsWith("tx-"))
        assertEquals(device, viewModel.selectedDevice.value)
        // Discovery is automatically stopped once transfer starts
        assertTrue(!fakeDiscoveryService.isDiscoveryRunning)
    }

    // --- Folder selection (folder transfer) ---

    private val treeUri = Uri.parse("content://com.example.documents/tree/primary%3AFilo")

    private class ColumnCursor(rows: List<Pair<String, Any?>>) : BaseCursor() {
        private val values = rows.toMap()
        private var position = -1

        override fun getCount(): Int = 1

        override fun moveToFirst(): Boolean {
            position = 0
            return true
        }

        override fun moveToNext(): Boolean {
            position++
            return position < 1
        }

        override fun getColumnIndex(columnName: String): Int = values.keys.indexOf(columnName)

        override fun getString(column: Int): String? {
            val value = values.values.elementAtOrNull(column) ?: return null
            return value as? String
        }

        override fun getLong(column: Int): Long {
            val value = values.values.elementAtOrNull(column) ?: return 0L
            return (value as? Long) ?: 0L
        }

        override fun isNull(column: Int): Boolean {
            return values.values.elementAtOrNull(column) == null
        }

        override fun close() {}
    }

    private fun treeRow(documentId: String, displayName: String, isDir: Boolean) = mapOf(
        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID to documentId,
        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME to displayName,
        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE to
            if (isDir) android.provider.DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
    )

    private fun registerTreeChildren(parentDocumentId: String, vararg rows: Map<String, String?>) {
        val columns = listOf(
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val childrenUri = android.provider.DocumentsContract
            .buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val cursor = object : BaseCursor() {
            private var position = -1
            override fun getCount(): Int = rows.size
            override fun moveToNext(): Boolean {
                position++
                return position < rows.size
            }
            override fun getColumnIndex(columnName: String): Int = columns.indexOf(columnName)
            override fun getString(column: Int): String? = rows[position][columns[column]]
            override fun close() {}
        }
        Shadows.shadowOf(context.contentResolver).setCursor(childrenUri, cursor)
    }

    private fun registerDocumentMetadata(documentId: String, displayName: String, size: Long) {
        val docUri = android.provider.DocumentsContract
            .buildDocumentUriUsingTree(treeUri, documentId)
        val cursor = ColumnCursor(
            listOf(
                android.provider.OpenableColumns.DISPLAY_NAME to displayName,
                android.provider.OpenableColumns.SIZE to size
            )
        )
        Shadows.shadowOf(context.contentResolver).setCursor(docUri, cursor)
    }

    @Test
    fun `adding a folder tree uri expands into nested files with relative paths`() = runTest {
        registerTreeChildren(
            "primary:Filo",
            treeRow("primary:Filo/docs", "docs", isDir = true),
            treeRow("primary:Filo/readme.md", "readme.md", isDir = false)
        )
        registerTreeChildren(
            "primary:Filo/docs",
            treeRow("primary:Filo/docs/report.pdf", "report.pdf", isDir = false)
        )
        registerDocumentMetadata("primary:Filo/readme.md", "readme.md", 12L)
        registerDocumentMetadata("primary:Filo/docs/report.pdf", "report.pdf", 34L)

        viewModel.addUris(context, listOf(treeUri))
        advanceUntilIdle()

        val files = viewModel.selectedFiles.value
        assertEquals(2, files.size)

        val byPath = files.associateBy { it.relativePath }
        assertEquals(setOf("readme.md", "docs/report.pdf"), byPath.keys)
        assertEquals("readme.md", byPath["readme.md"]?.name)
        assertEquals(12L, byPath["readme.md"]?.size)
        assertEquals("report.pdf", byPath["docs/report.pdf"]?.name)
        assertEquals(34L, byPath["docs/report.pdf"]?.size)
        assertEquals(46L, viewModel.totalBytes.value)
    }

    @Test
    fun `individual files keep empty relative paths`() = runTest {
        val file = File(context.cacheDir, "plain.txt").apply { writeText("hello") }

        viewModel.addUris(context, listOf(Uri.fromFile(file)))
        advanceUntilIdle()

        val files = viewModel.selectedFiles.value
        assertEquals(1, files.size)
        assertEquals("", files[0].relativePath)
    }

    @Test
    fun `folder and individual files can be mixed`() = runTest {
        registerTreeChildren(
            "primary:Filo",
            treeRow("primary:Filo/notes.txt", "notes.txt", isDir = false)
        )
        registerDocumentMetadata("primary:Filo/notes.txt", "notes.txt", 5L)

        val plain = File(context.cacheDir, "plain.bin").apply { writeText("xy") }

        viewModel.addUris(context, listOf(treeUri, Uri.fromFile(plain)))
        advanceUntilIdle()

        val byPath = viewModel.selectedFiles.value.associateBy { it.relativePath }
        assertEquals(setOf("notes.txt", ""), byPath.keys)
    }

    @Test
    fun `unreadable folder contributes no files`() = runTest {
        // No cursor registered for the tree children: enumeration yields nothing.

        viewModel.addUris(context, listOf(treeUri))
        advanceUntilIdle()

        assertTrue(viewModel.selectedFiles.value.isEmpty())
        assertEquals(0L, viewModel.totalBytes.value)
    }

    @Test
    fun `startTransfer carries relative paths into the service command`() = runTest {
        registerTreeChildren(
            "primary:Filo",
            treeRow("primary:Filo/a.txt", "a.txt", isDir = false)
        )
        registerDocumentMetadata("primary:Filo/a.txt", "a.txt", 3L)
        viewModel.addUris(context, listOf(treeUri))
        advanceUntilIdle()

        val device = DiscoveryDevice(
            id = "dev-1",
            serviceName = "Filo-Receiver-Test",
            host = "192.168.1.150",
            port = 50222
        )
        viewModel.startTransfer(context, device)
        advanceUntilIdle()

        val app = context.applicationContext as android.app.Application
        val startedIntent = Shadows.shadowOf(app).nextStartedService
        assertNotNull(startedIntent)
        val relativePaths = startedIntent!!.getStringArrayListExtra(
            com.filo.transfer.core.service.TransferCommand.EXTRA_RELATIVE_PATHS
        )
        assertEquals(listOf("a.txt"), relativePaths)
    }

    private class FakeDiscoveryService : DiscoveryService {
        var isDiscoveryRunning = false
        private val _discoveryState = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
        override val discoveryState: StateFlow<DiscoveryState> = _discoveryState.asStateFlow()

        private val _advertisingState = MutableStateFlow<AdvertisingState>(AdvertisingState.Idle)
        override val advertisingState: StateFlow<AdvertisingState> = _advertisingState.asStateFlow()

        private val _devices = MutableStateFlow<List<DiscoveryDevice>>(emptyList())
        override val discoveredDevices: StateFlow<List<DiscoveryDevice>> = _devices.asStateFlow()

        override fun startAdvertising(port: Int, customServiceName: String?) {
            _advertisingState.value = AdvertisingState.Advertising(customServiceName ?: "Fake", port)
        }

        override fun stopAdvertising() {
            _advertisingState.value = AdvertisingState.Stopped
        }

        override fun startDiscovery() {
            isDiscoveryRunning = true
            _discoveryState.value = DiscoveryState.Discovering
        }

        override fun stopDiscovery() {
            isDiscoveryRunning = false
            _discoveryState.value = DiscoveryState.Stopped
        }

        override fun reset() {
            stopDiscovery()
            stopAdvertising()
            _devices.value = emptyList()
        }

        fun emitDevice(device: DiscoveryDevice) {
            _devices.value = _devices.value + device
        }
    }
}

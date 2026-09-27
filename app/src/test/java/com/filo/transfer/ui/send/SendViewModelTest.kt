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
import org.robolectric.annotation.Config
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

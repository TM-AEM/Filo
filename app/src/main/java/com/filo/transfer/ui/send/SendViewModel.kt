package com.filo.transfer.ui.send

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filo.transfer.core.network.discovery.DiscoveryDevice
import com.filo.transfer.core.network.discovery.DiscoveryService
import com.filo.transfer.core.network.discovery.DiscoveryState
import com.filo.transfer.core.network.discovery.NsdDiscoveryService
import com.filo.transfer.core.service.TransferServiceController
import com.filo.transfer.core.storage.model.StorageResult
import com.filo.transfer.core.storage.provider.FileMetadataResolver
import com.filo.transfer.ui.model.SelectedFileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * ViewModel managing the sender workflow:
 * - SAF file picking, metadata resolution, and URI selection list.
 * - NSD peer discovery and device selection.
 * - Foreground [com.filo.transfer.core.service.TransferService] initiation.
 */
class SendViewModel(
    private var customDiscoveryService: DiscoveryService? = null,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _selectedFiles = MutableStateFlow<List<SelectedFileItem>>(emptyList())
    val selectedFiles: StateFlow<List<SelectedFileItem>> = _selectedFiles.asStateFlow()

    val totalBytes: StateFlow<Long> = _selectedFiles.map { files ->
        files.sumOf { if (it.size > 0) it.size else 0L }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val formattedTotalSize: StateFlow<String> = totalBytes.map { bytes ->
        SelectedFileItem.formatFileSize(bytes)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "0 B")

    private val _discoveredDevices = MutableStateFlow<List<DiscoveryDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveryDevice>> = _discoveredDevices.asStateFlow()

    private val _discoveryState = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val discoveryState: StateFlow<DiscoveryState> = _discoveryState.asStateFlow()

    private val _selectedDevice = MutableStateFlow<DiscoveryDevice?>(null)
    val selectedDevice: StateFlow<DiscoveryDevice?> = _selectedDevice.asStateFlow()

    private var activeDiscoveryService: DiscoveryService? = null

    /**
     * Resolves SAF / MediaStore URIs into [SelectedFileItem]s on an IO thread.
     */
    fun addUris(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val applicationContext = context.applicationContext

        viewModelScope.launch(ioDispatcher) {
            val resolver = FileMetadataResolver(applicationContext.contentResolver)
            val currentUris = _selectedFiles.value.map { it.uri }.toSet()
            val newItems = mutableListOf<SelectedFileItem>()

            for (uri in uris) {
                if (currentUris.contains(uri)) continue

                // Attempt to persist URI permission if applicable
                try {
                    applicationContext.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // Normal for single-grant or non-persistable content URIs
                }

                val metaResult = resolver.resolve(uri)
                if (metaResult is StorageResult.Success) {
                    val file = metaResult.data
                    val size = if (file.size >= 0) file.size else 0L
                    newItems.add(
                        SelectedFileItem(
                            uri = uri,
                            name = file.displayName,
                            size = size,
                            mimeType = file.mimeType,
                            formattedSize = SelectedFileItem.formatFileSize(size)
                        )
                    )
                }
            }

            if (newItems.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    _selectedFiles.value = _selectedFiles.value + newItems
                }
            }
        }
    }

    /**
     * Removes an item from the current selection.
     */
    fun removeFile(uri: Uri) {
        _selectedFiles.value = _selectedFiles.value.filterNot { it.uri == uri }
    }

    /**
     * Clears all selected files.
     */
    fun clearFiles() {
        _selectedFiles.value = emptyList()
    }

    /**
     * Starts local NSD peer discovery.
     */
    fun startDiscovery(context: Context) {
        val discovery = customDiscoveryService ?: activeDiscoveryService ?: run {
            val newService = NsdDiscoveryService.create(context.applicationContext)
            activeDiscoveryService = newService
            newService
        }

        viewModelScope.launch {
            discovery.discoveredDevices.collect { devices ->
                _discoveredDevices.value = devices
            }
        }
        viewModelScope.launch {
            discovery.discoveryState.collect { state ->
                _discoveryState.value = state
            }
        }

        discovery.startDiscovery()
    }

    /**
     * Stops peer discovery.
     */
    fun stopDiscovery() {
        activeDiscoveryService?.stopDiscovery()
        customDiscoveryService?.stopDiscovery()
    }

    /**
     * Selects a peer device and starts sending selected files via [TransferServiceController].
     *
     * @return The unique transfer ID assigned to the session, or null if no files selected.
     */
    fun startTransfer(context: Context, device: DiscoveryDevice): String? {
        val files = _selectedFiles.value
        if (files.isEmpty()) return null

        _selectedDevice.value = device
        stopDiscovery()

        val transferId = "tx-${UUID.randomUUID().toString().take(8)}"
        val uris = files.map { it.uri }

        TransferServiceController.startSendUris(
            context = context.applicationContext,
            transferId = transferId,
            targetHost = device.host,
            targetPort = device.port,
            uris = uris
        )

        return transferId
    }

    override fun onCleared() {
        super.onCleared()
        stopDiscovery()
        activeDiscoveryService = null
    }
}

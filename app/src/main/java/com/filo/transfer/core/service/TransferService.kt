package com.filo.transfer.core.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.filo.transfer.core.network.discovery.DiscoveryService
import com.filo.transfer.core.network.discovery.NsdDiscoveryService
import com.filo.transfer.core.network.model.ManifestFileItem
import com.filo.transfer.core.network.model.NetworkError
import com.filo.transfer.core.network.model.TransferManifest
import com.filo.transfer.core.network.model.TransferState
import com.filo.transfer.core.network.transfer.ContentUriFileSource
import com.filo.transfer.core.network.transfer.LocalFileSource
import com.filo.transfer.core.network.transfer.TransferFileSource
import com.filo.transfer.core.network.transfer.TransferReceiver
import com.filo.transfer.core.network.transfer.TransferSender
import com.filo.transfer.core.network.transport.SocketConnection
import com.filo.transfer.core.network.transport.TcpClientTransport
import com.filo.transfer.core.network.transport.TcpServerTransport
import com.filo.transfer.core.storage.model.StorageResult
import com.filo.transfer.core.storage.provider.FileMetadataResolver
import com.filo.transfer.core.storage.provider.FileStreamProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service hosting long-running Filo file transfers.
 *
 * OWNERSHIP AND LIFECYCLE:
 * - Owns the [serviceScope] (cancelled deterministically in [onDestroy]).
 * - Owns the active transfer [Job] and network engines ([TransferSender] / [TransferReceiver]).
 * - Enforces single active transfer constraint: duplicate START commands are rejected safely.
 * - Enforces immediate foreground transition with DATA_SYNC service type.
 * - Uses START_NOT_STICKY to prevent uncoordinated automatic restarts upon process death.
 */
class TransferService : Service() {

    private lateinit var serviceScope: CoroutineScope
    private lateinit var notificationManager: TransferNotificationManager

    private var activeJob: Job? = null
    private var activeTransferId: String? = null
    private var activeIsSender: Boolean = false

    private var currentSender: TransferSender? = null
    private var currentReceiver: TransferReceiver? = null
    private var currentConnection: SocketConnection? = null
    private var currentServerTransport: TcpServerTransport? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        notificationManager = TransferNotificationManager(applicationContext)
        _serviceState.value = TransferServiceState.Idle
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = TransferCommand.fromIntent(intent)
        if (command == null) {
            // Unrecognized or null intent (e.g. process restart) -> do not recreate transfer
            if (activeJob?.isActive != true) {
                stopSelf()
            }
            return START_NOT_STICKY
        }

        when (command) {
            is TransferCommand.StartSend -> handleStartSend(command)
            is TransferCommand.StartReceive -> handleStartReceive(command)
            is TransferCommand.Pause -> handlePause()
            is TransferCommand.Resume -> handleResume()
            is TransferCommand.Cancel -> handleCancel()
            is TransferCommand.Stop -> handleStop()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        cleanupActiveTransfer(cancelled = true)
        serviceScope.cancel("Service destroyed")
        _serviceState.value = TransferServiceState.Idle
    }

    private fun handleStartSend(command: TransferCommand.StartSend) {
        if (activeJob?.isActive == true) {
            // Reject duplicate start to protect active session
            return
        }

        enterForeground(isSender = true, transferId = command.transferId)
        activeTransferId = command.transferId
        activeIsSender = true
        _serviceState.value = TransferServiceState.Initializing(command.transferId, isSender = true)

        val sender = TransferSender(deviceName = command.deviceName)
        currentSender = sender

        activeJob = serviceScope.launch(Dispatchers.IO) {
            val stateJob = launch {
                sender.state.collect { state ->
                    publishAsyncState(
                        TransferServiceState.Active(
                            transferId = command.transferId,
                            isSender = true,
                            networkState = state
                        )
                    )
                    notificationManager.updateNotification(isSender = true, state = state)
                }
            }

            var connection: SocketConnection? = null
            try {
                connection = TcpClientTransport.connect(command.targetHost, command.targetPort)
                currentConnection = connection

                val resolver = FileMetadataResolver(contentResolver)
                val streamProvider = FileStreamProvider(contentResolver)

                val manifestItems = mutableListOf<ManifestFileItem>()
                val sources = mutableMapOf<String, TransferFileSource>()

                command.filePaths.forEachIndexed { idx, pathOrUri ->
                    val fileId = "file-$idx"
                    val relativePath = command.relativePaths.getOrNull(idx).orEmpty()
                    if (pathOrUri.startsWith("content://") || pathOrUri.startsWith("file://")) {
                        val uri = Uri.parse(pathOrUri)
                        val metaResult = resolver.resolve(uri)
                        if (metaResult is StorageResult.Success) {
                            val fileMeta = metaResult.data
                            val item = ManifestFileItem(
                                fileId = fileId,
                                fileName = fileMeta.displayName,
                                size = if (fileMeta.size >= 0L) fileMeta.size else 0L,
                                mimeType = fileMeta.mimeType,
                                lastModified = fileMeta.lastModified,
                                relativePath = relativePath
                            )
                            manifestItems.add(item)
                            sources[fileId] = ContentUriFileSource(
                                uri = uri,
                                fileId = fileId,
                                fileName = item.fileName,
                                size = item.size,
                                mimeType = item.mimeType,
                                lastModified = item.lastModified,
                                streamProvider = streamProvider
                            )
                        }
                    } else {
                        val file = File(pathOrUri)
                        if (file.exists() && file.isFile) {
                            val item = ManifestFileItem(
                                fileId = fileId,
                                fileName = file.name,
                                size = file.length(),
                                mimeType = "application/octet-stream",
                                lastModified = file.lastModified(),
                                relativePath = relativePath
                            )
                            manifestItems.add(item)
                            sources[fileId] = LocalFileSource(file, fileId)
                        }
                    }
                }

                val manifest = TransferManifest(
                    transferId = command.transferId,
                    senderDeviceName = command.deviceName,
                    files = manifestItems
                )

                val result = sender.transfer(connection, manifest, sources)
                handleFinalResult(command.transferId, isSender = true, result = result, state = sender.state.value)
            } catch (e: CancellationException) {
                handleCancelled(command.transferId, isSender = true)
            } catch (e: Throwable) {
                val netError = if (e is NetworkError) e else NetworkError.IoError(e.message ?: "Send failed", e)
                handleFailed(command.transferId, isSender = true, netError)
            } finally {
                stateJob.cancel()
                try { connection?.close() } catch (_: Throwable) {}
                currentConnection = null
                currentSender = null
                activeJob = null
            }
        }
    }

    private fun handleStartReceive(command: TransferCommand.StartReceive) {
        if (activeJob?.isActive == true) {
            // Reject duplicate start to protect active session
            return
        }

        enterForeground(isSender = false, transferId = command.transferId)
        activeTransferId = command.transferId
        activeIsSender = false
        _serviceState.value = TransferServiceState.Initializing(command.transferId, isSender = false)

        val receiver = TransferReceiver(deviceName = command.deviceName)
        currentReceiver = receiver

        activeJob = serviceScope.launch(Dispatchers.IO) {
            val stateJob = launch {
                receiver.state.collect { state ->
                    publishAsyncState(
                        TransferServiceState.Active(
                            transferId = command.transferId,
                            isSender = false,
                            networkState = state
                        )
                    )
                    notificationManager.updateNotification(isSender = false, state = state)
                }
            }

            var server: TcpServerTransport? = null
            var connection: SocketConnection? = null
            var discoveryService: DiscoveryService? = null
            try {
                server = TcpServerTransport()
                currentServerTransport = server
                val actualBoundPort = server.bind(command.listenPort)
                publishAsyncState(
                    TransferServiceState.Initializing(
                        transferId = command.transferId,
                        isSender = false,
                        boundPort = actualBoundPort
                    )
                )

                try {
                    discoveryService = NsdDiscoveryService.create(applicationContext)
                    discoveryService.startAdvertising(actualBoundPort, command.deviceName)
                } catch (_: Throwable) {}

                connection = server.accept()
                currentConnection = connection

                try { discoveryService?.stopAdvertising() } catch (_: Throwable) {}

                val destinationDir = File(command.destinationDir)
                if (!destinationDir.exists()) {
                    destinationDir.mkdirs()
                }
                val result = receiver.receive(connection, destinationDir, allowResume = true)
                handleFinalResult(command.transferId, isSender = false, result = result, state = receiver.state.value)
            } catch (e: CancellationException) {
                handleCancelled(command.transferId, isSender = false)
            } catch (e: Throwable) {
                val netError = if (e is NetworkError) e else NetworkError.IoError(e.message ?: "Receive failed", e)
                handleFailed(command.transferId, isSender = false, netError)
            } finally {
                try { discoveryService?.stopAdvertising() } catch (_: Throwable) {}
                stateJob.cancel()
                try { connection?.close() } catch (_: Throwable) {}
                try { server?.close() } catch (_: Throwable) {}
                currentConnection = null
                currentServerTransport = null
                currentReceiver = null
                activeJob = null
            }
        }
    }

    private fun handlePause() {
        val receiver = currentReceiver
        if (receiver != null) {
            serviceScope.launch {
                receiver.pause()
            }
            return
        }
        val sender = currentSender ?: return
        serviceScope.launch {
            sender.pause()
        }
    }

    private fun handleResume() {
        val receiver = currentReceiver
        if (receiver != null) {
            receiver.resume()
            return
        }
        currentSender?.resume()
    }

    private fun handleCancel() {
        cleanupActiveTransfer(cancelled = true)
        val transferId = activeTransferId ?: "unknown"
        val isSender = activeIsSender
        handleCancelled(transferId, isSender)
    }

    private fun handleStop() {
        cleanupActiveTransfer(cancelled = true)
        _serviceState.value = TransferServiceState.Idle
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notificationManager.cancelNotification()
        stopSelf()
    }

    private fun enterForeground(isSender: Boolean, transferId: String) {
        val initialNotification = notificationManager.buildInitialNotification(isSender, transferId)
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            TransferNotificationManager.NOTIFICATION_ID,
            initialNotification,
            foregroundType
        )
    }

    private fun cleanupActiveTransfer(cancelled: Boolean) {
        try {
            if (cancelled) {
                currentSender?.cancel()
                currentReceiver?.cancel()
            }
            currentConnection?.close()
            currentServerTransport?.close()
            activeJob?.cancel()
        } catch (_: Throwable) {}
        currentConnection = null
        currentServerTransport = null
        currentSender = null
        currentReceiver = null
        activeJob = null
    }

    private fun <T> handleFinalResult(
        transferId: String,
        isSender: Boolean,
        result: Result<T>,
        state: TransferState
    ) {
        if (result.isSuccess) {
            _serviceState.value = TransferServiceState.Terminated(transferId, isSender, state)
            notificationManager.updateNotification(isSender, state, force = true)
        } else {
            val ex = result.exceptionOrNull()
            val netError = if (ex is NetworkError) ex else NetworkError.IoError(ex?.message ?: "Transfer failed", ex)
            handleFailed(transferId, isSender, netError)
        }
    }

    /**
     * Publishes a service-state update emitted asynchronously by a transfer coroutine.
     *
     * [handleCancel], [handleStop] and failure handlers set the final state synchronously on the
     * calling thread, but the transfer's state collector runs on [Dispatchers.IO] and can emit a
     * trailing [TransferServiceState.Active] or [TransferServiceState.Initializing] afterwards.
     * Letting such a stale emission overwrite a state already reached via cancellation, failure,
     * stop or destroy would make the observable post-lifecycle state non-deterministic.
     */
    private fun publishAsyncState(newState: TransferServiceState) {
        val current = _serviceState.value
        if (current is TransferServiceState.Terminated || current is TransferServiceState.Idle) return
        _serviceState.value = newState
    }

    private fun handleCancelled(transferId: String, isSender: Boolean) {
        val cancelledState = TransferState.Cancelled
        _serviceState.value = TransferServiceState.Terminated(transferId, isSender, cancelledState)
        notificationManager.updateNotification(isSender, cancelledState, force = true)
    }

    private fun handleFailed(transferId: String, isSender: Boolean, error: NetworkError) {
        val failedState = TransferState.Failed(error)
        _serviceState.value = TransferServiceState.Terminated(transferId, isSender, failedState)
        notificationManager.updateNotification(isSender, failedState, force = true)
    }

    companion object {
        private val _serviceState = MutableStateFlow<TransferServiceState>(TransferServiceState.Idle)
        val serviceState: StateFlow<TransferServiceState> = _serviceState.asStateFlow()
    }
}

package com.filo.transfer.core.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.filo.transfer.core.network.model.ManifestFileItem
import com.filo.transfer.core.network.model.NetworkError
import com.filo.transfer.core.network.model.TransferManifest
import com.filo.transfer.core.network.model.TransferState
import com.filo.transfer.core.network.transfer.LocalFileSource
import com.filo.transfer.core.network.transfer.TransferFileSource
import com.filo.transfer.core.network.transfer.TransferReceiver
import com.filo.transfer.core.network.transfer.TransferSender
import com.filo.transfer.core.network.transport.SocketConnection
import com.filo.transfer.core.network.transport.TcpClientTransport
import com.filo.transfer.core.network.transport.TcpServerTransport
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
                    _serviceState.value = TransferServiceState.Active(
                        transferId = command.transferId,
                        isSender = true,
                        networkState = state
                    )
                    notificationManager.updateNotification(isSender = true, state = state)
                }
            }

            var connection: SocketConnection? = null
            try {
                connection = TcpClientTransport.connect(command.targetHost, command.targetPort)
                currentConnection = connection

                val files = command.filePaths.map { path -> File(path) }.filter { it.exists() && it.isFile }
                val manifestItems = files.mapIndexed { idx, file ->
                    ManifestFileItem(
                        fileId = "file-$idx",
                        fileName = file.name,
                        size = file.length(),
                        mimeType = "application/octet-stream",
                        lastModified = file.lastModified()
                    )
                }
                val manifest = TransferManifest(
                    transferId = command.transferId,
                    senderDeviceName = command.deviceName,
                    files = manifestItems
                )
                val sources = manifestItems.mapIndexed { idx, item ->
                    item.fileId to LocalFileSource(files[idx], item.fileId)
                }.toMap()

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
                    _serviceState.value = TransferServiceState.Active(
                        transferId = command.transferId,
                        isSender = false,
                        networkState = state
                    )
                    notificationManager.updateNotification(isSender = false, state = state)
                }
            }

            var server: TcpServerTransport? = null
            var connection: SocketConnection? = null
            try {
                server = TcpServerTransport()
                currentServerTransport = server
                server.bind(command.listenPort)

                connection = server.accept()
                currentConnection = connection

                val destinationDir = File(command.destinationDir)
                val result = receiver.receive(connection, destinationDir, allowResume = true)
                handleFinalResult(command.transferId, isSender = false, result = result, state = receiver.state.value)
            } catch (e: CancellationException) {
                handleCancelled(command.transferId, isSender = false)
            } catch (e: Throwable) {
                val netError = if (e is NetworkError) e else NetworkError.IoError(e.message ?: "Receive failed", e)
                handleFailed(command.transferId, isSender = false, netError)
            } finally {
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
        val sender = currentSender ?: return
        serviceScope.launch {
            sender.pause()
        }
    }

    private fun handleResume() {
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

package com.filo.transfer.core.service

import android.content.Context
import androidx.core.content.ContextCompat
import com.filo.transfer.core.network.protocol.ProtocolConstants
import kotlinx.coroutines.flow.StateFlow

/**
 * Clean, lightweight client controller for interacting with [TransferService].
 *
 * Keeps Android [android.content.Intent] generation and foreground-service dispatch
 * isolated from UI/domain callers.
 */
object TransferServiceController {

    /**
     * Observes the active service lifecycle and transfer state.
     */
    val state: StateFlow<TransferServiceState>
        get() = TransferService.serviceState

    /**
     * Starts a background file sending session using file paths or content URIs.
     */
    fun startSend(
        context: Context,
        transferId: String,
        targetHost: String,
        targetPort: Int = ProtocolConstants.DEFAULT_PORT,
        filePaths: List<String>,
        deviceName: String = "FiloSender"
    ) {
        val command = TransferCommand.StartSend(
            transferId = transferId,
            targetHost = targetHost,
            targetPort = targetPort,
            filePaths = filePaths,
            deviceName = deviceName
        )
        val intent = TransferCommand.toIntent(context, command)
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Starts a background file sending session directly from a list of SAF/MediaStore [android.net.Uri]s.
     */
    fun startSendUris(
        context: Context,
        transferId: String,
        targetHost: String,
        targetPort: Int = ProtocolConstants.DEFAULT_PORT,
        uris: List<android.net.Uri>,
        deviceName: String = "FiloSender"
    ) {
        startSend(
            context = context,
            transferId = transferId,
            targetHost = targetHost,
            targetPort = targetPort,
            filePaths = uris.map { it.toString() },
            deviceName = deviceName
        )
    }

    /**
     * Starts a background file receiving session.
     */
    fun startReceive(
        context: Context,
        transferId: String,
        listenPort: Int = ProtocolConstants.DEFAULT_PORT,
        destinationDir: String,
        deviceName: String = "FiloReceiver"
    ) {
        val command = TransferCommand.StartReceive(
            transferId = transferId,
            listenPort = listenPort,
            destinationDir = destinationDir,
            deviceName = deviceName
        )
        val intent = TransferCommand.toIntent(context, command)
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Pauses the active transfer.
     */
    fun pause(context: Context) {
        val intent = TransferCommand.toIntent(context, TransferCommand.Pause)
        context.startService(intent)
    }

    /**
     * Resumes the paused transfer.
     */
    fun resume(context: Context) {
        val intent = TransferCommand.toIntent(context, TransferCommand.Resume)
        context.startService(intent)
    }

    /**
     * Cancels the active transfer.
     */
    fun cancel(context: Context) {
        val intent = TransferCommand.toIntent(context, TransferCommand.Cancel)
        context.startService(intent)
    }

    /**
     * Stops the foreground service.
     */
    fun stop(context: Context) {
        val intent = TransferCommand.toIntent(context, TransferCommand.Stop)
        context.startService(intent)
    }
}

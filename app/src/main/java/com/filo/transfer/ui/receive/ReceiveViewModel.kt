package com.filo.transfer.ui.receive

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.lifecycle.ViewModel
import com.filo.transfer.core.network.protocol.ProtocolConstants
import com.filo.transfer.core.service.TransferServiceController
import com.filo.transfer.core.service.TransferServiceState
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

/**
 * ViewModel managing the receiver workflow:
 * - Starting the TCP receiver server via [TransferServiceController].
 * - Observing service and incoming transfer state.
 * - Clean teardown upon exit.
 */
class ReceiveViewModel : ViewModel() {

    val serviceState: StateFlow<TransferServiceState> = TransferServiceController.state

    private var activeTransferId: String? = null

    val deviceName: String by lazy {
        val model = try {
            Build.MODEL.filter { it.isLetterOrDigit() }.take(10)
        } catch (_: Throwable) {
            "Android"
        }.ifBlank { "Android" }
        "Filo-$model"
    }

    /**
     * Starts the foreground receiver service awaiting an incoming connection.
     */
    fun startReceiving(context: Context) {
        val transferId = activeTransferId ?: "rx-${UUID.randomUUID().toString().take(8)}".also {
            activeTransferId = it
        }

        val destinationDir = getDownloadDestination(context)

        TransferServiceController.startReceive(
            context = context.applicationContext,
            transferId = transferId,
            listenPort = ProtocolConstants.DEFAULT_PORT,
            destinationDir = destinationDir,
            deviceName = deviceName
        )
    }

    /**
     * Stops the receiver service if active.
     */
    fun stopReceiving(context: Context) {
        TransferServiceController.stop(context.applicationContext)
        activeTransferId = null
    }

    private fun getDownloadDestination(context: Context): String {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val filoDir = File(downloads, "Filo")
        if (!filoDir.exists()) {
            filoDir.mkdirs()
        }
        return if (filoDir.canWrite()) {
            filoDir.absolutePath
        } else {
            val appFiles = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val fallback = File(appFiles, "Filo").apply { mkdirs() }
            fallback.absolutePath
        }
    }
}

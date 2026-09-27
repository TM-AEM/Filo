package com.filo.transfer.core.service

import android.content.Context
import android.content.Intent
import com.filo.transfer.core.network.protocol.ProtocolConstants

/**
 * Strongly typed command model for controlling [TransferService].
 *
 * Encapsulates intent action constants, serialization, deserialization, and parameter validation.
 */
sealed interface TransferCommand {

    /**
     * Initiates a client-side file sending session to a peer server.
     */
    data class StartSend(
        val transferId: String,
        val targetHost: String,
        val targetPort: Int = ProtocolConstants.DEFAULT_PORT,
        val filePaths: List<String>,
        val deviceName: String = "FiloSender"
    ) : TransferCommand {
        init {
            require(transferId.isNotBlank()) { "transferId must not be blank" }
            require(targetHost.isNotBlank()) { "targetHost must not be blank" }
            require(targetPort in 1..65535) { "targetPort must be between 1 and 65535" }
            require(filePaths.isNotEmpty()) { "filePaths must not be empty" }
        }
    }

    /**
     * Initiates a server-side receiving session awaiting a peer client connection.
     */
    data class StartReceive(
        val transferId: String,
        val listenPort: Int = ProtocolConstants.DEFAULT_PORT,
        val destinationDir: String,
        val deviceName: String = "FiloReceiver"
    ) : TransferCommand {
        init {
            require(transferId.isNotBlank()) { "transferId must not be blank" }
            require(listenPort in 1..65535) { "listenPort must be between 1 and 65535" }
            require(destinationDir.isNotBlank()) { "destinationDir must not be blank" }
        }
    }

    /**
     * Pauses the active transfer.
     */
    data object Pause : TransferCommand

    /**
     * Resumes the paused transfer.
     */
    data object Resume : TransferCommand

    /**
     * Cancels the active transfer and aborts all network and file I/O.
     */
    data object Cancel : TransferCommand

    /**
     * Stops the foreground service cleanly if no transfer is active,
     * or cancels and stops if a transfer is active.
     */
    data object Stop : TransferCommand

    companion object {
        const val ACTION_START_SEND = "com.filo.transfer.action.START_SEND"
        const val ACTION_START_RECEIVE = "com.filo.transfer.action.START_RECEIVE"
        const val ACTION_PAUSE = "com.filo.transfer.action.PAUSE"
        const val ACTION_RESUME = "com.filo.transfer.action.RESUME"
        const val ACTION_CANCEL = "com.filo.transfer.action.CANCEL"
        const val ACTION_STOP = "com.filo.transfer.action.STOP"

        const val EXTRA_TRANSFER_ID = "com.filo.transfer.extra.TRANSFER_ID"
        const val EXTRA_TARGET_HOST = "com.filo.transfer.extra.TARGET_HOST"
        const val EXTRA_PORT = "com.filo.transfer.extra.PORT"
        const val EXTRA_FILE_PATHS = "com.filo.transfer.extra.FILE_PATHS"
        const val EXTRA_DESTINATION_DIR = "com.filo.transfer.extra.DESTINATION_DIR"
        const val EXTRA_DEVICE_NAME = "com.filo.transfer.extra.DEVICE_NAME"

        /**
         * Builds an [Intent] targeting [TransferService] for the given [command].
         */
        fun toIntent(context: Context, command: TransferCommand): Intent {
            val intent = Intent(context, TransferService::class.java)
            when (command) {
                is StartSend -> {
                    intent.action = ACTION_START_SEND
                    intent.putExtra(EXTRA_TRANSFER_ID, command.transferId)
                    intent.putExtra(EXTRA_TARGET_HOST, command.targetHost)
                    intent.putExtra(EXTRA_PORT, command.targetPort)
                    intent.putStringArrayListExtra(EXTRA_FILE_PATHS, ArrayList(command.filePaths))
                    intent.putExtra(EXTRA_DEVICE_NAME, command.deviceName)
                }
                is StartReceive -> {
                    intent.action = ACTION_START_RECEIVE
                    intent.putExtra(EXTRA_TRANSFER_ID, command.transferId)
                    intent.putExtra(EXTRA_PORT, command.listenPort)
                    intent.putExtra(EXTRA_DESTINATION_DIR, command.destinationDir)
                    intent.putExtra(EXTRA_DEVICE_NAME, command.deviceName)
                }
                is Pause -> intent.action = ACTION_PAUSE
                is Resume -> intent.action = ACTION_RESUME
                is Cancel -> intent.action = ACTION_CANCEL
                is Stop -> intent.action = ACTION_STOP
            }
            return intent
        }

        /**
         * Parses and validates a [TransferCommand] from an incoming service [Intent].
         * Returns null if the intent is null, has an unknown action, or contains invalid parameters.
         */
        fun fromIntent(intent: Intent?): TransferCommand? {
            if (intent == null) return null
            return when (intent.action) {
                ACTION_START_SEND -> {
                    val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID) ?: return null
                    val targetHost = intent.getStringExtra(EXTRA_TARGET_HOST) ?: return null
                    val port = intent.getIntExtra(EXTRA_PORT, ProtocolConstants.DEFAULT_PORT)
                    val filePaths = intent.getStringArrayListExtra(EXTRA_FILE_PATHS) ?: return null
                    val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "FiloSender"
                    try {
                        StartSend(transferId, targetHost, port, filePaths, deviceName)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
                ACTION_START_RECEIVE -> {
                    val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID) ?: return null
                    val port = intent.getIntExtra(EXTRA_PORT, ProtocolConstants.DEFAULT_PORT)
                    val destinationDir = intent.getStringExtra(EXTRA_DESTINATION_DIR) ?: return null
                    val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "FiloReceiver"
                    try {
                        StartReceive(transferId, port, destinationDir, deviceName)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
                ACTION_PAUSE -> Pause
                ACTION_RESUME -> Resume
                ACTION_CANCEL -> Cancel
                ACTION_STOP -> Stop
                else -> null
            }
        }
    }
}

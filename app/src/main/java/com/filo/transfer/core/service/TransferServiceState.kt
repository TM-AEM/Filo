package com.filo.transfer.core.service

import com.filo.transfer.core.network.model.NetworkError
import com.filo.transfer.core.network.model.TransferProgress
import com.filo.transfer.core.network.model.TransferState

/**
 * Service-level state model for [TransferService].
 *
 * Clearly distinguishes the Android service hosting lifecycle state from the underlying
 * TCP network state machine ([TransferState]).
 */
sealed interface TransferServiceState {

    /** Service is running but idle, with no active transfer job. */
    data object Idle : TransferServiceState

    /** A transfer has been requested and initialization/connection is underway. */
    data class Initializing(
        val transferId: String,
        val isSender: Boolean
    ) : TransferServiceState

    /**
     * An active transfer is in progress, hosted inside the foreground service.
     *
     * @property transferId Unique transfer session identifier.
     * @property isSender True if this device is sending, false if receiving.
     * @property networkState The active domain state from the network transfer engine.
     */
    data class Active(
        val transferId: String,
        val isSender: Boolean,
        val networkState: TransferState
    ) : TransferServiceState {
        val progress: TransferProgress?
            get() = when (networkState) {
                is TransferState.Transferring -> networkState.progress
                is TransferState.Paused -> networkState.progress
                else -> null
            }

        val isPaused: Boolean
            get() = networkState is TransferState.Paused

        val isCompleted: Boolean
            get() = networkState is TransferState.Completed

        val isFailed: Boolean
            get() = networkState is TransferState.Failed

        val isCancelled: Boolean
            get() = networkState is TransferState.Cancelled
    }

    /**
     * Terminal state indicating the transfer session finished, either successfully or with error.
     */
    data class Terminated(
        val transferId: String,
        val isSender: Boolean,
        val finalState: TransferState
    ) : TransferServiceState
}

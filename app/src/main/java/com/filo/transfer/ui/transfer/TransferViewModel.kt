package com.filo.transfer.ui.transfer

import android.content.Context
import androidx.lifecycle.ViewModel
import com.filo.transfer.core.service.TransferServiceController
import com.filo.transfer.core.service.TransferServiceState
import com.filo.transfer.ui.model.SelectedFileItem
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel managing active transfer state observation, metric formatting, and playback controls.
 */
class TransferViewModel : ViewModel() {

    val serviceState: StateFlow<TransferServiceState> = TransferServiceController.state

    fun pause(context: Context) {
        TransferServiceController.pause(context.applicationContext)
    }

    fun resume(context: Context) {
        TransferServiceController.resume(context.applicationContext)
    }

    fun cancel(context: Context) {
        TransferServiceController.cancel(context.applicationContext)
    }

    fun stop(context: Context) {
        TransferServiceController.stop(context.applicationContext)
    }

    companion object {
        fun formatSpeed(bytesPerSec: Long): String {
            return "${SelectedFileItem.formatFileSize(bytesPerSec)}/s"
        }

        fun formatEta(seconds: Long): String {
            if (seconds <= 0) return "--"
            val mins = seconds / 60
            val secs = seconds % 60
            return if (mins >= 60) {
                val hours = mins / 60
                val remMins = mins % 60
                "%d:%02d:%02d".format(hours, remMins, secs)
            } else {
                "%d:%02d".format(mins, secs)
            }
        }
    }
}

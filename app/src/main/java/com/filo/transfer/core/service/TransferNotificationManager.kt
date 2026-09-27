package com.filo.transfer.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.filo.transfer.core.network.model.TransferProgress
import com.filo.transfer.core.network.model.TransferState
import java.util.Locale

/**
 * Manages foreground notification creation, channel setup, throttled progress updates,
 * and user action PendingIntents (Pause, Resume, Cancel) for [TransferService].
 */
class TransferNotificationManager(
    private val context: Context,
    private val updateThrottleMs: Long = DEFAULT_UPDATE_THROTTLE_MS
) {
    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var lastUpdateTimeMs: Long = 0L

    init {
        createNotificationChannel()
    }

    /**
     * Idempotently creates the Filo notification channel for ongoing file transfers.
     */
    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time progress and controls for Filo file transfers"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Builds the initial ongoing notification displayed when the service enters the foreground.
     */
    fun buildInitialNotification(isSender: Boolean, transferId: String): Notification {
        val title = if (isSender) "Filo — Sending files" else "Filo — Receiving files"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Connecting to peer...")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .addAction(createCancelAction())

        return builder.build()
    }

    /**
     * Builds an updated notification reflecting the active [networkState].
     */
    fun buildNotificationForState(
        isSender: Boolean,
        networkState: TransferState
    ): Notification {
        val prefix = if (isSender) "Sending" else "Receiving"

        return when (networkState) {
            is TransferState.Idle, is TransferState.Connecting -> {
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle("Filo — $prefix files")
                    .setContentText("Connecting to peer...")
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setProgress(0, 0, true)
                    .addAction(createCancelAction())
                    .build()
            }
            is TransferState.Handshaking -> {
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle("Filo — $prefix files")
                    .setContentText("Handshaking with peer...")
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setProgress(0, 0, true)
                    .addAction(createCancelAction())
                    .build()
            }
            is TransferState.Transferring -> {
                val p = networkState.progress
                val percent = p.overallPercentage.toInt()
                val speedStr = formatSpeed(p.speedBytesPerSecond)
                val etaStr = if (p.etaSeconds > 0) " • ETA ${p.etaSeconds}s" else ""
                val text = "${p.currentFileName} (${p.currentFileIndex + 1}/${p.totalFiles}) • $percent% ($speedStr$etaStr)"

                val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle("Filo — $prefix files")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setProgress(100, percent, false)

                if (isSender) {
                    builder.addAction(createPauseAction())
                }
                builder.addAction(createCancelAction())
                builder.build()
            }
            is TransferState.Paused -> {
                val p = networkState.progress
                val percent = p.overallPercentage.toInt()
                val text = "Paused: ${p.currentFileName} (${p.currentFileIndex + 1}/${p.totalFiles}) • $percent%"

                val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle("Filo — Transfer Paused")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setProgress(100, percent, false)

                if (isSender) {
                    builder.addAction(createResumeAction())
                }
                builder.addAction(createCancelAction())
                builder.build()
            }
            is TransferState.Verifying -> {
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle("Filo — Verifying files")
                    .setContentText("Verifying SHA-256 integrity for ${networkState.fileName}...")
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setProgress(0, 0, true)
                    .addAction(createCancelAction())
                    .build()
            }
            is TransferState.Completed -> {
                val sizeStr = formatBytes(networkState.totalBytes)
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle("Filo — Transfer Completed")
                    .setContentText("${networkState.totalFiles} files transferred successfully ($sizeStr)")
                    .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .build()
            }
            is TransferState.Failed -> {
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle("Filo — Transfer Failed")
                    .setContentText(networkState.error.message)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .build()
            }
            is TransferState.Cancelled -> {
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle("Filo — Transfer Cancelled")
                    .setContentText("Transfer was cancelled by user")
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .build()
            }
        }
    }

    /**
     * Posts or updates the notification using throttled scheduling for progress updates.
     * State changes (Paused, Completed, Failed, Cancelled) bypass throttling.
     */
    fun updateNotification(isSender: Boolean, state: TransferState, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val isTerminalOrStateTransition = force ||
                state !is TransferState.Transferring ||
                (now - lastUpdateTimeMs >= updateThrottleMs)

        if (!isTerminalOrStateTransition) {
            return
        }

        try {
            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                val notification = buildNotificationForState(isSender, state)
                notificationManager.notify(NOTIFICATION_ID, notification)
                lastUpdateTimeMs = now
            }
        } catch (_: SecurityException) {
            // Permission not granted on Android 13+; safely ignore
        }
    }

    fun cancelNotification() {
        try {
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (_: Throwable) {}
    }

    private fun createPauseAction(): NotificationCompat.Action {
        val intent = TransferCommand.toIntent(context, TransferCommand.Pause)
        val pendingIntent = PendingIntent.getService(
            context,
            REQUEST_CODE_PAUSE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_pause,
            "Pause",
            pendingIntent
        ).build()
    }

    private fun createResumeAction(): NotificationCompat.Action {
        val intent = TransferCommand.toIntent(context, TransferCommand.Resume)
        val pendingIntent = PendingIntent.getService(
            context,
            REQUEST_CODE_RESUME,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_play,
            "Resume",
            pendingIntent
        ).build()
    }

    private fun createCancelAction(): NotificationCompat.Action {
        val intent = TransferCommand.toIntent(context, TransferCommand.Cancel)
        val pendingIntent = PendingIntent.getService(
            context,
            REQUEST_CODE_CANCEL,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Cancel",
            pendingIntent
        ).build()
    }

    companion object {
        const val CHANNEL_ID = "filo_transfer_channel"
        const val CHANNEL_NAME = "Filo File Transfers"
        const val NOTIFICATION_ID = 1001

        private const val DEFAULT_UPDATE_THROTTLE_MS = 500L
        private const val REQUEST_CODE_PAUSE = 101
        private const val REQUEST_CODE_RESUME = 102
        private const val REQUEST_CODE_CANCEL = 103

        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
            return String.format(Locale.US, "%.1f %s", value, units[digitGroups])
        }

        fun formatSpeed(bytesPerSec: Long): String {
            return "${formatBytes(bytesPerSec)}/s"
        }
    }
}

package com.filo.transfer.core.service

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.filo.transfer.core.network.model.NetworkError
import com.filo.transfer.core.network.model.TransferProgress
import com.filo.transfer.core.network.model.TransferState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferNotificationManagerTest {

    private lateinit var context: Context
    private lateinit var manager: TransferNotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = TransferNotificationManager(context)
    }

    @Test
    fun testNotificationChannelCreated() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = notificationManager.getNotificationChannel(TransferNotificationManager.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(TransferNotificationManager.CHANNEL_NAME, channel.name)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun testInitialNotificationProperties() {
        val notification = manager.buildInitialNotification(isSender = true, transferId = "tx-1")
        assertNotNull(notification)
        assertEquals(TransferNotificationManager.CHANNEL_ID, notification.channelId)
        // Should have cancel action
        assertTrue(notification.actions != null && notification.actions.isNotEmpty())
    }

    @Test
    fun testTransferringNotificationContainsProgressAndActions() {
        val progress = TransferProgress(
            currentFileName = "movie.mp4",
            currentFileIndex = 0,
            totalFiles = 3,
            bytesTransferredForCurrentFile = 50_000_000L,
            currentFileSize = 100_000_000L,
            totalBytesTransferred = 50_000_000L,
            totalBytesOverall = 300_000_000L,
            speedBytesPerSecond = 10_000_000L,
            etaSeconds = 25L
        )

        val notification = manager.buildNotificationForState(
            isSender = true,
            networkState = TransferState.Transferring(progress)
        )
        assertNotNull(notification)
        // Sender has Pause and Cancel actions
        assertEquals(2, notification.actions?.size)
    }

    @Test
    fun testPausedNotificationContainsResumeAction() {
        val progress = TransferProgress(
            currentFileName = "song.mp3",
            currentFileIndex = 1,
            totalFiles = 2,
            bytesTransferredForCurrentFile = 10_000L,
            currentFileSize = 20_000L,
            totalBytesTransferred = 10_000L,
            totalBytesOverall = 20_000L,
            speedBytesPerSecond = 0L,
            etaSeconds = 0L
        )

        val notification = manager.buildNotificationForState(
            isSender = true,
            networkState = TransferState.Paused(progress)
        )
        assertNotNull(notification)
        // Sender has Resume and Cancel actions
        assertEquals(2, notification.actions?.size)
    }

    @Test
    fun testTerminalStatesNotifications() {
        val completedNotif = manager.buildNotificationForState(
            isSender = false,
            networkState = TransferState.Completed(totalFiles = 5, totalBytes = 1024L * 1024L, durationMs = 1200L)
        )
        assertNotNull(completedNotif)

        val failedNotif = manager.buildNotificationForState(
            isSender = true,
            networkState = TransferState.Failed(NetworkError.ChecksumMismatch("a", "b"))
        )
        assertNotNull(failedNotif)

        val cancelledNotif = manager.buildNotificationForState(
            isSender = true,
            networkState = TransferState.Cancelled
        )
        assertNotNull(cancelledNotif)
    }

    @Test
    fun testFormattingHelpers() {
        assertEquals("0 B", TransferNotificationManager.formatBytes(0))
        assertEquals("1.0 KB", TransferNotificationManager.formatBytes(1024))
        assertEquals("1.0 MB", TransferNotificationManager.formatBytes(1024 * 1024))
        assertEquals("1.5 GB", TransferNotificationManager.formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))

        assertEquals("5.0 MB/s", TransferNotificationManager.formatSpeed(5 * 1024 * 1024))
    }
}

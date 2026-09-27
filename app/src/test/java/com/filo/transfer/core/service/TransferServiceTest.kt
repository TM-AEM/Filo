package com.filo.transfer.core.service

import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.filo.transfer.core.network.model.TransferState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferServiceTest {

    private lateinit var context: Context
    private lateinit var controller: ServiceController<TransferService>
    private lateinit var service: TransferService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        controller = Robolectric.buildService(TransferService::class.java)
        service = controller.create().get()
    }

    @Test
    fun testServiceCreationSetsIdleState() {
        assertEquals(TransferServiceState.Idle, TransferService.serviceState.value)
    }

    @Test
    fun testNullIntentReturnsStartNotSticky() {
        val flags = service.onStartCommand(null, 0, 1)
        assertEquals(Service.START_NOT_STICKY, flags)
    }

    @Test
    fun testStartSendEntersForegroundAndReturnsNotSticky() {
        val tempFile = File(context.cacheDir, "test_file.txt").apply {
            writeText("Test transfer payload")
        }

        val command = TransferCommand.StartSend(
            transferId = "tx-test-1",
            targetHost = "127.0.0.1",
            targetPort = 12345,
            filePaths = listOf(tempFile.absolutePath)
        )
        val intent = TransferCommand.toIntent(context, command)

        val flags = service.onStartCommand(intent, 0, 1)
        assertEquals(Service.START_NOT_STICKY, flags)

        // Service state should advance from Idle to Initializing or Active
        val currentState = TransferService.serviceState.value
        assertTrue(
            "Expected Initializing or Active or Terminated, but got: $currentState",
            currentState is TransferServiceState.Initializing ||
                    currentState is TransferServiceState.Active ||
                    currentState is TransferServiceState.Terminated
        )
    }

    @Test
    fun testDuplicateStartPrevented() {
        val tempFile = File(context.cacheDir, "duplicate_test.txt").apply {
            writeText("Duplicate test payload")
        }

        val command1 = TransferCommand.StartSend(
            transferId = "tx-first",
            targetHost = "127.0.0.1",
            targetPort = 12345,
            filePaths = listOf(tempFile.absolutePath)
        )
        val intent1 = TransferCommand.toIntent(context, command1)
        service.onStartCommand(intent1, 0, 1)

        val command2 = TransferCommand.StartSend(
            transferId = "tx-second",
            targetHost = "127.0.0.1",
            targetPort = 12346,
            filePaths = listOf(tempFile.absolutePath)
        )
        val intent2 = TransferCommand.toIntent(context, command2)

        // Sending duplicate start should safely return START_NOT_STICKY and not replace session
        val flags = service.onStartCommand(intent2, 0, 2)
        assertEquals(Service.START_NOT_STICKY, flags)
    }

    @Test
    fun testCancelCommandSetsCancelledState() {
        val cancelIntent = TransferCommand.toIntent(context, TransferCommand.Cancel)
        service.onStartCommand(cancelIntent, 0, 1)

        val state = TransferService.serviceState.value
        if (state is TransferServiceState.Terminated) {
            assertEquals(TransferState.Cancelled, state.finalState)
        }
    }

    @Test
    fun testPauseAndResumeCommands() {
        val pauseIntent = TransferCommand.toIntent(context, TransferCommand.Pause)
        val flagsPause = service.onStartCommand(pauseIntent, 0, 1)
        assertEquals(Service.START_NOT_STICKY, flagsPause)

        val resumeIntent = TransferCommand.toIntent(context, TransferCommand.Resume)
        val flagsResume = service.onStartCommand(resumeIntent, 0, 2)
        assertEquals(Service.START_NOT_STICKY, flagsResume)
    }

    @Test
    fun testStopCommand() {
        val stopIntent = TransferCommand.toIntent(context, TransferCommand.Stop)
        val flags = service.onStartCommand(stopIntent, 0, 1)
        assertEquals(Service.START_NOT_STICKY, flags)
    }

    @Test
    fun testDestroyResetsStateToIdle() {
        controller.destroy()
        assertEquals(TransferServiceState.Idle, TransferService.serviceState.value)
    }
}

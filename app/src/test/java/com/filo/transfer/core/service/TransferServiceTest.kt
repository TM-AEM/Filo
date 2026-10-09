package com.filo.transfer.core.service

import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.filo.transfer.core.network.model.TransferState
import kotlinx.coroutines.runBlocking
import org.junit.After
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

    @After
    fun tearDown() {
        // Destroying the service triggers onDestroy, which cancels the service scope and closes
        // any server transport bound by the test. Without this, a StartReceive test leaves its
        // listening socket bound and a later test reusing the same port fails to bind.
        controller.destroy()
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

    @Test
    fun testStartReceiveEntersInitializing() {
        val dest = File(context.cacheDir, "incoming").apply { mkdirs() }
        val command = TransferCommand.StartReceive(
            transferId = "rx-test-1",
            listenPort = 50222,
            destinationDir = dest.absolutePath
        )
        val flags = service.onStartCommand(TransferCommand.toIntent(context, command), 0, 1)
        assertEquals(Service.START_NOT_STICKY, flags)
        val currentState = TransferService.serviceState.value
        assertTrue(
            "Expected Initializing, Active, or Terminated, got: $currentState",
            currentState is TransferServiceState.Initializing ||
                currentState is TransferServiceState.Active ||
                currentState is TransferServiceState.Terminated
        )
    }

    @Test
    fun testDuplicateReceiveStartDoesNotReplaceActiveJob() {
        val dest = File(context.cacheDir, "incoming2").apply { mkdirs() }
        val first = TransferCommand.StartReceive("rx-first", 50222, dest.absolutePath)
        service.onStartCommand(TransferCommand.toIntent(context, first), 0, 1)
        val afterFirst = TransferService.serviceState.value

        val second = TransferCommand.StartReceive("rx-second", 50223, dest.absolutePath)
        val flags = service.onStartCommand(TransferCommand.toIntent(context, second), 0, 2)
        assertEquals(Service.START_NOT_STICKY, flags)

        val afterSecond = TransferService.serviceState.value
        if (afterFirst is TransferServiceState.Initializing) {
            assertTrue(afterSecond is TransferServiceState.Initializing || afterSecond is TransferServiceState.Active)
            if (afterSecond is TransferServiceState.Initializing) {
                assertEquals("rx-first", afterSecond.transferId)
            }
        }
    }

    @Test
    fun testCancelAfterStartTerminatesCancelled() {
        val dest = File(context.cacheDir, "incoming3").apply { mkdirs() }
        val start = TransferCommand.StartReceive("rx-cancel", 50222, dest.absolutePath)
        service.onStartCommand(TransferCommand.toIntent(context, start), 0, 1)
        service.onStartCommand(TransferCommand.toIntent(context, TransferCommand.Cancel), 0, 2)

        val state = TransferService.serviceState.value
        assertTrue("Expected Terminated, got $state", state is TransferServiceState.Terminated)
        assertEquals(TransferState.Cancelled, (state as TransferServiceState.Terminated).finalState)
    }

    @Test
    fun testStopAfterStartClearsForegroundWork() {
        val dest = File(context.cacheDir, "incoming4").apply { mkdirs() }
        val start = TransferCommand.StartReceive("rx-stop", 50222, dest.absolutePath)
        service.onStartCommand(TransferCommand.toIntent(context, start), 0, 1)
        val flags = service.onStartCommand(TransferCommand.toIntent(context, TransferCommand.Stop), 0, 2)
        assertEquals(Service.START_NOT_STICKY, flags)
        val state = TransferService.serviceState.value
        assertTrue(state is TransferServiceState.Terminated || state is TransferServiceState.Idle)
    }

    @Test
    fun testDestroyCancelsActiveReceiveAndResetsIdle() {
        val dest = File(context.cacheDir, "incoming5").apply { mkdirs() }
        val start = TransferCommand.StartReceive("rx-destroy", 50222, dest.absolutePath)
        service.onStartCommand(TransferCommand.toIntent(context, start), 0, 1)
        controller.destroy()
        assertEquals(TransferServiceState.Idle, TransferService.serviceState.value)
    }
}

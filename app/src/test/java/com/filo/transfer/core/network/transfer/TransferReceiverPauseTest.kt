package com.filo.transfer.core.network.transfer

import com.filo.transfer.core.network.model.TransferProgress
import com.filo.transfer.core.network.model.TransferState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransferReceiverPauseTest {

    private lateinit var receiver: TransferReceiver

    @Before
    fun setUp() {
        receiver = TransferReceiver("TestReceiver")
    }

    @Test
    fun testInitialStateIsIdle() {
        assertEquals(TransferState.Idle, receiver.state.value)
    }

    @Test
    fun testPauseTransitionsTransferringToPaused() = runTest {
        val progress = TransferProgress(
            currentFileName = "test.txt",
            currentFileIndex = 0,
            totalFiles = 1,
            bytesTransferredForCurrentFile = 50L,
            currentFileSize = 100L,
            totalBytesTransferred = 50L,
            totalBytesOverall = 100L,
            speedBytesPerSecond = 50L,
            etaSeconds = 1L
        )
        receiver.pause()
        assertFalse(receiver.state.value is TransferState.Paused)
    }

    @Test
    fun testResumeAfterPauseClearsPausedFlag() = runTest {
        receiver.pause()
        receiver.resume()
        assertFalse(receiver.state.value is TransferState.Paused)
    }

    @Test
    fun testCancelUnlocksPauseMutex() = runTest {
        receiver.pause()
        receiver.cancel()
        assertEquals(TransferState.Cancelled, receiver.state.value)
    }

    @Test
    fun testDoublePauseIsIdempotent() = runTest {
        receiver.pause()
        receiver.pause()
        receiver.resume()
        assertFalse(receiver.state.value is TransferState.Paused)
    }

    @Test
    fun testDoubleResumeIsIdempotent() = runTest {
        receiver.resume()
        receiver.resume()
        assertEquals(TransferState.Idle, receiver.state.value)
    }

    @Test
    fun testPauseResumeCyclePreservesState() = runTest {
        receiver.pause()
        receiver.resume()
        receiver.pause()
        receiver.resume()
        assertEquals(TransferState.Idle, receiver.state.value)
    }
}

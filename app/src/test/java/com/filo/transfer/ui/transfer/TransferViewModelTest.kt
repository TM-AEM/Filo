package com.filo.transfer.ui.transfer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferViewModelTest {

    private lateinit var context: Context
    private lateinit var viewModel: TransferViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        viewModel = TransferViewModel()
    }

    @Test
    fun `formatSpeed formats bytes per second correctly`() {
        assertEquals("0 B/s", TransferViewModel.formatSpeed(0L))
        assertEquals("1 KB/s", TransferViewModel.formatSpeed(1024L))
        assertEquals("10.5 MB/s", TransferViewModel.formatSpeed((10.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun `formatEta formats seconds into readable time format`() {
        assertEquals("--", TransferViewModel.formatEta(0L))
        assertEquals("--", TransferViewModel.formatEta(-10L))
        assertEquals("0:45", TransferViewModel.formatEta(45L))
        assertEquals("2:05", TransferViewModel.formatEta(125L))
        assertEquals("1:01:05", TransferViewModel.formatEta(3665L))
    }

    @Test
    fun `control commands execute without error`() {
        // Verify controller dispatch integration
        viewModel.pause(context)
        viewModel.resume(context)
        viewModel.cancel(context)
        viewModel.stop(context)
    }
}

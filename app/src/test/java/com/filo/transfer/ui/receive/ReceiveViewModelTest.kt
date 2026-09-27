package com.filo.transfer.ui.receive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReceiveViewModelTest {

    private lateinit var context: Context
    private lateinit var viewModel: ReceiveViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        viewModel = ReceiveViewModel()
    }

    @Test
    fun `deviceName is non blank and prefixed with Filo`() {
        assertNotNull(viewModel.deviceName)
        assertTrue(viewModel.deviceName.startsWith("Filo-"))
    }

    @Test
    fun `startReceiving and stopReceiving execute safely`() {
        viewModel.startReceiving(context)
        viewModel.stopReceiving(context)
    }
}

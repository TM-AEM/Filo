package com.filo.transfer.ui

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.filo.transfer.core.network.discovery.DiscoveryDevice
import com.filo.transfer.core.network.discovery.DiscoveryState
import com.filo.transfer.core.network.model.TransferProgress
import com.filo.transfer.core.network.model.TransferState
import com.filo.transfer.core.service.TransferServiceState
import com.filo.transfer.ui.home.HomeScreen
import com.filo.transfer.ui.model.SelectedFileItem
import com.filo.transfer.ui.receive.ReceiveScreen
import com.filo.transfer.ui.send.DiscoverDevicesScreen
import com.filo.transfer.ui.send.SelectFilesScreen
import com.filo.transfer.ui.theme.FiloTheme
import com.filo.transfer.ui.transfer.TransferProgressScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ComposeUiTransferTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `HomeScreen renders title and action cards`() {
        var sendClicked = false
        var receiveClicked = false

        composeTestRule.setContent {
            FiloTheme {
                HomeScreen(
                    serviceState = TransferServiceState.Idle,
                    onNavigateToSend = { sendClicked = true },
                    onNavigateToReceive = { receiveClicked = true },
                    onNavigateToProgress = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("app_title").assertIsDisplayed()
        composeTestRule.onNodeWithTag("send_button").assertIsDisplayed().performClick()
        assertTrue(sendClicked)

        composeTestRule.onNodeWithTag("receive_button").assertIsDisplayed().performClick()
        assertTrue(receiveClicked)
    }

    @Test
    fun `SelectFilesScreen with empty list disables continue button`() {
        composeTestRule.setContent {
            FiloTheme {
                SelectFilesScreen(
                    selectedFiles = emptyList(),
                    formattedTotalSize = "0 B",
                    onAddUris = {},
                    onRemoveFile = {},
                    onClearAll = {},
                    onContinue = {},
                    onBack = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("select_files_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("continue_button").assertIsNotEnabled()
    }

    @Test
    fun `SelectFilesScreen with selected items enables continue button`() {
        var continueClicked = false
        val items = listOf(
            SelectedFileItem(
                uri = Uri.parse("content://media/external/files/1"),
                name = "photo.jpg",
                size = 1024L * 1024L,
                mimeType = "image/jpeg",
                formattedSize = "1 MB"
            )
        )

        composeTestRule.setContent {
            FiloTheme {
                SelectFilesScreen(
                    selectedFiles = items,
                    formattedTotalSize = "1 MB",
                    onAddUris = {},
                    onRemoveFile = {},
                    onClearAll = {},
                    onContinue = { continueClicked = true },
                    onBack = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("continue_button").assertIsEnabled().performClick()
        assertTrue(continueClicked)
        composeTestRule.onNodeWithText("photo.jpg").assertIsDisplayed()
    }

    @Test
    fun `DiscoverDevicesScreen displays device cards and triggers selection`() {
        var selectedDevice: DiscoveryDevice? = null
        val device = DiscoveryDevice(
            id = "d1",
            serviceName = "Filo-Peer-Phone",
            host = "192.168.1.100",
            port = 50222
        )

        composeTestRule.setContent {
            FiloTheme {
                DiscoverDevicesScreen(
                    discoveredDevices = listOf(device),
                    discoveryState = DiscoveryState.Discovering,
                    onSelectDevice = { selectedDevice = it },
                    onBack = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("searching_indicator").assertIsDisplayed()
        composeTestRule.onNodeWithTag("device_card_${device.serviceName}").assertIsDisplayed().performClick()
        assertTrue(selectedDevice == device)
    }

    @Test
    fun `ReceiveScreen renders device identity and waiting indicator`() {
        var stoppedReceiving = false

        composeTestRule.setContent {
            FiloTheme {
                ReceiveScreen(
                    deviceName = "Filo-TestDevice",
                    serviceState = TransferServiceState.Initializing("rx-1", isSender = false),
                    onStartReceiving = {},
                    onStopReceiving = { stoppedReceiving = true },
                    onTransferActive = {},
                    onBack = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("waiting_indicator").assertIsDisplayed()
        composeTestRule.onNodeWithTag("device_name_text").assertIsDisplayed()
        composeTestRule.onNodeWithText("Filo-TestDevice").assertIsDisplayed()
        composeTestRule.onNodeWithTag("cancel_receive_button").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(stoppedReceiving)
    }

    @Test
    fun `TransferProgressScreen displays active metrics and controls`() {
        var pauseClicked = false
        var cancelConfirmed = false

        val progress = TransferProgress(
            currentFileName = "sample_video.mp4",
            currentFileIndex = 0,
            totalFiles = 1,
            bytesTransferredForCurrentFile = 5_000_000L,
            currentFileSize = 10_000_000L,
            totalBytesTransferred = 5_000_000L,
            totalBytesOverall = 10_000_000L,
            speedBytesPerSecond = 2_500_000L,
            etaSeconds = 2L
        )

        val activeState = TransferServiceState.Active(
            transferId = "tx-1",
            isSender = true,
            networkState = TransferState.Transferring(progress)
        )

        composeTestRule.setContent {
            FiloTheme {
                TransferProgressScreen(
                    serviceState = activeState,
                    onPause = { pauseClicked = true },
                    onResume = {},
                    onCancel = { cancelConfirmed = true },
                    onDone = {},
                    onBack = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("file_name_text").assertIsDisplayed()
        composeTestRule.onNodeWithText("sample_video.mp4").assertIsDisplayed()
        composeTestRule.onNodeWithTag("overall_progress_bar").assertIsDisplayed()

        composeTestRule.onNodeWithTag("pause_button").assertIsDisplayed().performClick()
        assertTrue(pauseClicked)

        // Test cancel confirmation dialog
        composeTestRule.onNodeWithTag("cancel_button").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("cancel_confirm_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_cancel_button").assertIsDisplayed().performClick()
        assertTrue(cancelConfirmed)
    }
}

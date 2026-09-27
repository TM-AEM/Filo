package com.filo.transfer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.filo.transfer.ui.home.HomeScreen
import com.filo.transfer.ui.navigation.FiloScreen
import com.filo.transfer.ui.receive.ReceiveScreen
import com.filo.transfer.ui.receive.ReceiveViewModel
import com.filo.transfer.ui.send.DiscoverDevicesScreen
import com.filo.transfer.ui.send.SelectFilesScreen
import com.filo.transfer.ui.send.SendViewModel
import com.filo.transfer.ui.transfer.TransferProgressScreen
import com.filo.transfer.ui.transfer.TransferViewModel

/**
 * Top-level application composable managing screen transitions, ViewModels,
 * and system back navigation.
 */
@Composable
fun FiloApp(
    modifier: Modifier = Modifier,
    sendViewModel: SendViewModel = viewModel(),
    receiveViewModel: ReceiveViewModel = viewModel(),
    transferViewModel: TransferViewModel = viewModel()
) {
    val context = LocalContext.current
    var currentScreen by rememberSaveable { mutableStateOf(FiloScreen.HOME) }

    val serviceState by transferViewModel.serviceState.collectAsState()
    val selectedFiles by sendViewModel.selectedFiles.collectAsState()
    val formattedTotalSize by sendViewModel.formattedTotalSize.collectAsState()
    val discoveredDevices by sendViewModel.discoveredDevices.collectAsState()
    val discoveryState by sendViewModel.discoveryState.collectAsState()

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when (currentScreen) {
            FiloScreen.HOME -> {
                HomeScreen(
                    serviceState = serviceState,
                    onNavigateToSend = { currentScreen = FiloScreen.SELECT_FILES },
                    onNavigateToReceive = { currentScreen = FiloScreen.RECEIVE },
                    onNavigateToProgress = { currentScreen = FiloScreen.TRANSFER_PROGRESS }
                )
            }

            FiloScreen.SELECT_FILES -> {
                BackHandler {
                    currentScreen = FiloScreen.HOME
                }
                SelectFilesScreen(
                    selectedFiles = selectedFiles,
                    formattedTotalSize = formattedTotalSize,
                    onAddUris = { uris -> sendViewModel.addUris(context, uris) },
                    onRemoveFile = { uri -> sendViewModel.removeFile(uri) },
                    onClearAll = { sendViewModel.clearFiles() },
                    onContinue = {
                        sendViewModel.startDiscovery(context)
                        currentScreen = FiloScreen.DISCOVER_DEVICES
                    },
                    onBack = { currentScreen = FiloScreen.HOME }
                )
            }

            FiloScreen.DISCOVER_DEVICES -> {
                BackHandler {
                    sendViewModel.stopDiscovery()
                    currentScreen = FiloScreen.SELECT_FILES
                }
                DiscoverDevicesScreen(
                    discoveredDevices = discoveredDevices,
                    discoveryState = discoveryState,
                    onSelectDevice = { device ->
                        sendViewModel.startTransfer(context, device)
                        currentScreen = FiloScreen.TRANSFER_PROGRESS
                    },
                    onBack = {
                        sendViewModel.stopDiscovery()
                        currentScreen = FiloScreen.SELECT_FILES
                    }
                )
            }

            FiloScreen.RECEIVE -> {
                BackHandler {
                    receiveViewModel.stopReceiving(context)
                    currentScreen = FiloScreen.HOME
                }
                ReceiveScreen(
                    deviceName = receiveViewModel.deviceName,
                    serviceState = serviceState,
                    onStartReceiving = { receiveViewModel.startReceiving(context) },
                    onStopReceiving = { receiveViewModel.stopReceiving(context) },
                    onTransferActive = { currentScreen = FiloScreen.TRANSFER_PROGRESS },
                    onBack = {
                        receiveViewModel.stopReceiving(context)
                        currentScreen = FiloScreen.HOME
                    }
                )
            }

            FiloScreen.TRANSFER_PROGRESS -> {
                BackHandler {
                    // Safe back handling: returns to Home while allowing background transfer to continue safely
                    currentScreen = FiloScreen.HOME
                }
                TransferProgressScreen(
                    serviceState = serviceState,
                    onPause = { transferViewModel.pause(context) },
                    onResume = { transferViewModel.resume(context) },
                    onCancel = { transferViewModel.cancel(context) },
                    onDone = {
                        sendViewModel.clearFiles()
                        transferViewModel.stop(context)
                        currentScreen = FiloScreen.HOME
                    },
                    onBack = {
                        currentScreen = FiloScreen.HOME
                    }
                )
            }
        }
    }
}

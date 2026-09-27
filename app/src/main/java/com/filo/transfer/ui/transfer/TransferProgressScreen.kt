package com.filo.transfer.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filo.transfer.R
import com.filo.transfer.core.network.model.TransferProgress
import com.filo.transfer.core.network.model.TransferState
import com.filo.transfer.core.service.TransferServiceState
import com.filo.transfer.ui.model.SelectedFileItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferProgressScreen(
    serviceState: TransferServiceState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showCancelDialog by remember { mutableStateOf(false) }

    val isTransferActive = serviceState is TransferServiceState.Active &&
            (serviceState.networkState is TransferState.Transferring ||
                    serviceState.networkState is TransferState.Connecting ||
                    serviceState.networkState is TransferState.Handshaking ||
                    serviceState.networkState is TransferState.Verifying ||
                    serviceState.networkState is TransferState.Paused)

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text(stringResource(R.string.cancel_transfer_confirm_title)) },
            text = { Text(stringResource(R.string.cancel_transfer_confirm_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showCancelDialog = false
                        onCancel()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_cancel_button")
                ) {
                    Text(stringResource(R.string.yes_cancel))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCancelDialog = false },
                    modifier = Modifier.testTag("dismiss_cancel_button")
                ) {
                    Text(stringResource(R.string.keep_transferring))
                }
            },
            modifier = Modifier.testTag("cancel_confirm_dialog")
        )
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("transfer_progress_screen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.transfer_progress)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (isTransferActive) {
                                onBack() // Navigate back while allowing background transfer to continue safely
                            } else {
                                onDone()
                            }
                        },
                        modifier = Modifier.testTag("back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (serviceState) {
                    is TransferServiceState.Initializing -> {
                        Spacer(modifier = Modifier.height(48.dp))
                        CircularProgressIndicator(modifier = Modifier.size(56.dp))
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = stringResource(R.string.connecting),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(48.dp))
                        OutlinedButton(
                            onClick = { showCancelDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("cancel_button")
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                    }

                    is TransferServiceState.Active -> {
                        ActiveTransferContent(
                            activeState = serviceState,
                            onPause = onPause,
                            onResume = onResume,
                            onRequestCancel = { showCancelDialog = true },
                            onDone = onDone
                        )
                    }

                    is TransferServiceState.Terminated -> {
                        TerminatedTransferContent(
                            finalState = serviceState.finalState,
                            onDone = onDone
                        )
                    }

                    is TransferServiceState.Idle -> {
                        Spacer(modifier = Modifier.height(48.dp))
                        Text(
                            text = stringResource(R.string.completed),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = onDone,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("done_button")
                        ) {
                            Text(stringResource(R.string.done))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveTransferContent(
    activeState: TransferServiceState.Active,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRequestCancel: () -> Unit,
    onDone: () -> Unit
) {
    val networkState = activeState.networkState

    when (networkState) {
        is TransferState.Connecting, is TransferState.Handshaking -> {
            Spacer(modifier = Modifier.height(48.dp))
            CircularProgressIndicator(modifier = Modifier.size(56.dp))
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.connecting),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(48.dp))
            OutlinedButton(
                onClick = onRequestCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cancel_button")
            ) {
                Text(stringResource(R.string.cancel))
            }
        }

        is TransferState.Transferring, is TransferState.Paused -> {
            val isPaused = networkState is TransferState.Paused
            val progress: TransferProgress = if (isPaused) {
                (networkState as TransferState.Paused).progress
            } else {
                (networkState as TransferState.Transferring).progress
            }

            TransferMetricsCard(progress = progress, isPaused = isPaused)

            Spacer(modifier = Modifier.height(32.dp))

            // Action Buttons: Pause/Resume + Cancel
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isPaused) {
                    Button(
                        onClick = onResume,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("resume_button")
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.resume))
                    }
                } else {
                    FilledTonalButton(
                        onClick = onPause,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("pause_button")
                    ) {
                        Icon(imageVector = Icons.Default.Pause, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.pause))
                    }
                }

                OutlinedButton(
                    onClick = onRequestCancel,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cancel_button"),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.cancel))
                }
            }
        }

        is TransferState.Verifying -> {
            Spacer(modifier = Modifier.height(32.dp))
            CircularProgressIndicator(modifier = Modifier.size(56.dp))
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.verifying),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = networkState.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        is TransferState.Completed -> {
            TerminatedTransferContent(finalState = networkState, onDone = onDone)
        }

        is TransferState.Failed -> {
            TerminatedTransferContent(finalState = networkState, onDone = onDone)
        }

        is TransferState.Cancelled -> {
            TerminatedTransferContent(finalState = networkState, onDone = onDone)
        }

        is TransferState.Idle -> {
            TerminatedTransferContent(finalState = networkState, onDone = onDone)
        }
    }
}

@Composable
private fun TransferMetricsCard(
    progress: TransferProgress,
    isPaused: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Status Badge
            Text(
                text = if (isPaused) stringResource(R.string.paused) else stringResource(R.string.transferring),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (isPaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            // File Name & Index
            Text(
                text = progress.currentFileName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("file_name_text")
            )
            Text(
                text = stringResource(
                    R.string.file_index_format,
                    progress.currentFileIndex + 1,
                    progress.totalFiles
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Progress Bar & Percentage
            val overallFraction = (progress.overallPercentage / 100f).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { overallFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .testTag("overall_progress_bar"),
                color = if (isPaused) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "%.1f%%".format(progress.overallPercentage),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                val transferredFormatted = SelectedFileItem.formatFileSize(progress.totalBytesTransferred)
                val totalFormatted = SelectedFileItem.formatFileSize(progress.totalBytesOverall)
                Text(
                    text = "$transferredFormatted / $totalFormatted",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("bytes_text")
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Speed & ETA
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val speed = if (isPaused) "--" else TransferViewModel.formatSpeed(progress.speedBytesPerSecond)
                Text(
                    text = stringResource(R.string.speed_format, speed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("speed_text")
                )
                val eta = if (isPaused) "--" else TransferViewModel.formatEta(progress.etaSeconds)
                Text(
                    text = stringResource(R.string.eta_format, eta),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("eta_text")
                )
            }
        }
    }
}

@Composable
private fun TerminatedTransferContent(
    finalState: TransferState,
    onDone: () -> Unit
) {
    Spacer(modifier = Modifier.height(32.dp))

    when (finalState) {
        is TransferState.Completed -> {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.completed),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            val transferredSize = SelectedFileItem.formatFileSize(finalState.totalBytes)
            Text(
                text = "${finalState.totalFiles} files ($transferredSize)",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        is TransferState.Failed -> {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(72.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.failed),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = finalState.error.message ?: "Transfer encountered an error",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        is TransferState.Cancelled -> {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(72.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.cancelled),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }

        else -> {
            Text(
                text = stringResource(R.string.done),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }

    Spacer(modifier = Modifier.height(48.dp))

    Button(
        onClick = onDone,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("done_button")
    ) {
        Text(stringResource(R.string.done))
    }
}

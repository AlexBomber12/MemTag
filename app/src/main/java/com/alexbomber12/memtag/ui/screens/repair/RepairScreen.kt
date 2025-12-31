@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.screens.repair

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexbomber12.memtag.app.HardwareAction
import com.alexbomber12.memtag.ui.components.LoadingState
import com.alexbomber12.memtag.ui.components.PrimaryButton
import com.alexbomber12.memtag.ui.components.SecondaryButton
import com.alexbomber12.memtag.ui.components.SectionCard
import com.alexbomber12.memtag.ui.components.StatChip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

@Composable
fun RepairScreen(
    viewModel: RepairViewModel,
    hardwareActions: Flow<HardwareAction>,
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value

    state.confirmation?.let { confirmation ->
        ConfirmationDialog(
            confirmation = confirmation,
            onConfirm = viewModel::confirmWrite,
            onCancel = viewModel::dismissConfirmation,
        )
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.cancelOperations() }
    }

    LaunchedEffect(Unit) {
        viewModel.onScreenOpened()
    }

    LaunchedEffect(hardwareActions) {
        hardwareActions.collect { action ->
            when (action) {
                HardwareAction.Rfid -> viewModel.scanRfid()
                HardwareAction.Scan -> viewModel.scanQr()
            }
        }
    }

    val isBusy = state.isReading || state.isScanningQr || state.isWriting || state.isVerifying
    val expectedBlank = state.expectedEpc.isBlank()
    val canWrite = canWriteExpectedEpc(state.expectedEpc, state.scannedEpc, state.isWriting)
    val selectedLabel =
        state.selectedLookup?.name?.takeIf { it.isNotBlank() }
            ?: state.selectedLookup?.epc?.takeIf { it.isNotBlank() }
    val statusLabel =
        when (state.status) {
            is VerifyWriteStatus.Ok -> "Matched"
            is VerifyWriteStatus.Mismatch -> "Mismatch"
            is VerifyWriteStatus.NotScanned -> "Not scanned"
            is VerifyWriteStatus.Invalid -> "Idle"
        }
    val statusMessage =
        state.errorMessage
            ?: state.message
            ?: (state.status as? VerifyWriteStatus.Invalid)?.message
    val statusMessageColor =
        when {
            state.errorMessage != null -> MaterialTheme.colorScheme.error
            state.message != null -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    val highlightColor =
        when (state.status) {
            is VerifyWriteStatus.Ok -> MaterialTheme.colorScheme.primary
            is VerifyWriteStatus.Mismatch -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        }
    val epcTextStyle =
        MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
        )
    val scannedEpcValue = state.scannedEpc?.takeIf { it.isNotBlank() }.orEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(
                title = "Scan tag",
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SecondaryButton(
                            text = "Scan RFID",
                            onClick = viewModel::scanRfid,
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryButton(
                            text = "Scan QR",
                            onClick = viewModel::scanQr,
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (state.isReading) {
                        LoadingState(message = "Scanning RFID...")
                    } else if (state.isScanningQr) {
                        LoadingState(message = "Scanning QR...")
                    }
                }
            }
        }

        item {
            SectionCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "Verification", style = MaterialTheme.typography.titleMedium)
                    StatChip(label = statusLabel)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (statusMessage != null) {
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusMessageColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (selectedLabel != null) {
                        Text(
                            text = "Selected: $selectedLabel",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedTextField(
                        value = state.expectedEpc,
                        onValueChange = viewModel::onExpectedEpcChange,
                        label = { Text(text = "Expected EPC") },
                        placeholder = { Text(text = "Expected EPC (hex)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isBusy,
                        textStyle = epcTextStyle,
                        trailingIcon = {
                            if (state.expectedEpc.isNotBlank()) {
                                IconButton(
                                    onClick = { viewModel.onExpectedEpcChange("") },
                                    enabled = !isBusy,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Clear,
                                        contentDescription = "Clear expected EPC",
                                    )
                                }
                            }
                        },
                    )
                    OutlinedTextField(
                        value = scannedEpcValue,
                        onValueChange = {},
                        label = { Text(text = "Scanned EPC") },
                        placeholder = { Text(text = "--") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        readOnly = true,
                        textStyle = epcTextStyle.copy(color = highlightColor),
                    )
                    if (!expectedBlank) {
                        if (state.isWriting) {
                            LoadingState(message = "Writing EPC...")
                        } else if (state.isVerifying) {
                            LoadingState(message = "Verifying tag EPC...")
                        }
                    }
                    PrimaryButton(
                        text = "Write expected EPC",
                        onClick = viewModel::startWriteConfirmation,
                        enabled = canWrite,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfirmationDialog(
    confirmation: WriteConfirmation,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text = "Confirm write") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "Review the EPCs before writing:", style = MaterialTheme.typography.bodyMedium)
                EpcLine(
                    label = "Expected EPC",
                    epc = confirmation.expectedEpc,
                    color = MaterialTheme.colorScheme.primary,
                    valueStyle = MaterialTheme.typography.titleMedium,
                )
                EpcLine(
                    label = "Scanned EPC",
                    epc = confirmation.scannedEpc,
                    valueStyle = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Write EPC ${confirmation.expectedEpc} to tag?",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                text = "Confirm",
                onClick = onConfirm,
            )
        },
        dismissButton = {
            SecondaryButton(
                text = "Cancel",
                onClick = onCancel,
            )
        },
    )
}

@Composable
private fun EpcLine(
    label: String,
    epc: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    valueStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "$label:", style = MaterialTheme.typography.labelMedium)
        Text(
            text = epc,
            style = valueStyle,
            fontFamily = FontFamily.Monospace,
            color = color,
            modifier = Modifier.widthIn(min = 180.dp),
        )
    }
}

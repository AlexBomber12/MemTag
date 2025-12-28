@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.screens.repair

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexbomber12.memtag.app.HardwareAction
import com.alexbomber12.memtag.domain.repair.RepairActionLog
import com.alexbomber12.memtag.domain.repair.RepairActionResult
import com.alexbomber12.memtag.ui.components.AppCard
import com.alexbomber12.memtag.ui.components.ErrorState
import com.alexbomber12.memtag.ui.components.LoadingState
import com.alexbomber12.memtag.ui.components.PrimaryButton
import com.alexbomber12.memtag.ui.components.SecondaryButton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import java.text.DateFormat
import java.util.Date

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
    val isExpectedValid = state.status !is VerifyWriteStatus.Invalid
    val canWrite =
        !isBusy &&
            state.confirmation == null &&
            isExpectedValid &&
            (state.scannedEpc.isNullOrBlank() || state.status is VerifyWriteStatus.Mismatch)

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppCard(title = "Expected EPC") {
                OutlinedTextField(
                    value = state.expectedEpc,
                    onValueChange = viewModel::onExpectedEpcChange,
                    label = { Text(text = "Expected EPC") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SecondaryButton(
                        text = "Use from Find",
                        onClick = viewModel::useExpectedFromFind,
                        enabled = !isBusy && state.lastFindTargetEpc.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "Use from Lookup",
                        onClick = viewModel::useExpectedFromLookup,
                        enabled = !isBusy && state.lastLookupEpc.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item {
            AppCard(title = "Scanned EPC") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SecondaryButton(
                        text = "Clear scanned",
                        onClick = viewModel::clearScanned,
                        enabled = !isBusy && !state.scannedEpc.isNullOrBlank(),
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "Stop/Cancel",
                        onClick = viewModel::cancelOperations,
                        enabled = isBusy || state.confirmation != null,
                        modifier = Modifier.weight(1f),
                    )
                }
                when {
                    state.isReading -> LoadingState(message = "Scanning RFID...")
                    state.isScanningQr -> LoadingState(message = "Scanning QR...")
                    !state.scannedEpc.isNullOrBlank() -> EpcLine(label = "Scanned EPC", epc = state.scannedEpc)
                    else -> Text(text = "No tag scanned yet.", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        item {
            AppCard(title = "Status") {
                when (val status = state.status) {
                    is VerifyWriteStatus.NotScanned -> {
                        Text(
                            text = "Not scanned yet. Scan RFID or QR to verify.",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        EpcLine(label = "Expected EPC", epc = status.expectedEpc)
                    }
                    is VerifyWriteStatus.Ok -> {
                        Text(
                            text = "OK: scanned EPC matches expected.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        EpcLine(label = "Expected EPC", epc = status.expectedEpc, color = MaterialTheme.colorScheme.primary)
                    }
                    is VerifyWriteStatus.Mismatch -> {
                        Text(
                            text = "Mismatch: scanned EPC does not match expected.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        EpcLine(label = "Expected EPC", epc = status.expectedEpc, color = MaterialTheme.colorScheme.error)
                        EpcLine(label = "Scanned EPC", epc = status.scannedEpc, color = MaterialTheme.colorScheme.error)
                    }
                    is VerifyWriteStatus.Invalid -> {
                        ErrorState(message = status.message)
                    }
                }
                when {
                    state.isWriting -> LoadingState(message = "Writing EPC...")
                    state.isVerifying -> LoadingState(message = "Verifying tag EPC...")
                }
                state.message?.let { message ->
                    Text(text = message, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                state.errorMessage?.let { message ->
                    ErrorState(message = message)
                }
                PrimaryButton(
                    text = "Write expected EPC",
                    onClick = viewModel::startWriteConfirmation,
                    enabled = canWrite,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (state.logs.isNotEmpty()) {
            item {
                AppCard(title = "Recent actions") {
                    state.logs.forEach { log ->
                        LogRow(log = log)
                    }
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
    val scannedText = confirmation.scannedEpc ?: "not scanned yet"
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
                    epc = scannedText,
                    valueStyle = MaterialTheme.typography.titleMedium,
                )
                when (confirmation.warning) {
                    WriteWarning.NOT_SCANNED -> {
                        Text(
                            text = "Warning: writing without prior verification.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    WriteWarning.MISMATCH -> {
                        Text(
                            text = "Warning: current tag does not match expected.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    null -> Unit
                }
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

@Composable
private fun LogRow(log: RepairActionLog) {
    val color =
        when (log.result) {
            RepairActionResult.SUCCESS -> MaterialTheme.colorScheme.primary
            RepairActionResult.FAILURE -> MaterialTheme.colorScheme.error
            RepairActionResult.CANCELLED -> MaterialTheme.colorScheme.tertiary
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "${formatTimestamp(log.createdAtEpochMs)} - ${formatAction(log.actionType.name)}",
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
        Text(
            text = "Result: ${log.result.name}",
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
        log.message?.takeIf { it.isNotBlank() }?.let { message ->
            Text(text = message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatTimestamp(epochMs: Long): String {
    val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    return formatter.format(Date(epochMs))
}

private fun formatAction(action: String): String {
    return action
        .replace('_', ' ')
        .lowercase()
        .replaceFirstChar { char -> char.uppercase() }
}

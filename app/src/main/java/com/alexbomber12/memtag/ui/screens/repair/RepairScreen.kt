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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexbomber12.memtag.app.HardwareAction
import com.alexbomber12.memtag.ui.components.AppCard
import com.alexbomber12.memtag.ui.components.ErrorState
import com.alexbomber12.memtag.ui.components.LoadingState
import com.alexbomber12.memtag.ui.components.PrimaryButton
import com.alexbomber12.memtag.ui.components.SecondaryButton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

@Composable
fun RepairScreen(
    viewModel: RepairViewModel,
    hardwareActions: Flow<HardwareAction>,
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val clipboard = LocalClipboardManager.current

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
    val scannedBlank = state.scannedEpc.isNullOrBlank()
    val canWrite =
        !isBusy &&
            state.confirmation == null &&
            !expectedBlank &&
            !scannedBlank &&
            state.status is VerifyWriteStatus.Mismatch
    val selectedLabel =
        state.selectedLookup?.name?.takeIf { it.isNotBlank() }
            ?: state.selectedLookup?.epc?.takeIf { it.isNotBlank() }
    val selectedLine = selectedLabel ?: "none"
    val hasSelection = !state.selectedLookup?.epc.isNullOrBlank()

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppCard(title = "Scan tag") {
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
                when {
                    state.isReading -> LoadingState(message = "Scanning RFID...")
                    state.isScanningQr -> LoadingState(message = "Scanning QR...")
                    else -> Text(text = "Ready to scan.", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        item {
            AppCard(title = "Status") {
                val highlightColor =
                    when (state.status) {
                        is VerifyWriteStatus.Ok -> MaterialTheme.colorScheme.primary
                        is VerifyWriteStatus.Mismatch -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                when (val status = state.status) {
                    is VerifyWriteStatus.NotScanned -> {
                        Text(
                            text = "Not scanned yet.",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    is VerifyWriteStatus.Ok -> {
                        Text(
                            text = "OK: scanned EPC matches expected.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    is VerifyWriteStatus.Mismatch -> {
                        Text(
                            text = "Mismatch: scanned EPC does not match expected.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    is VerifyWriteStatus.Invalid -> {
                        ErrorState(message = status.message)
                    }
                }
                OutlinedTextField(
                    value = state.expectedEpc,
                    onValueChange = viewModel::onExpectedEpcChange,
                    label = { Text(text = "Expected EPC") },
                    placeholder = { Text(text = "Expected EPC (hex)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isBusy,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val text = clipboard.getText()?.text.orEmpty()
                                viewModel.pasteExpectedEpc(text)
                            },
                            enabled = !isBusy,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentPaste,
                                contentDescription = "Paste",
                            )
                        }
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Selected card: $selectedLine",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    AssistChip(
                        onClick = viewModel::useSelectedLookup,
                        label = { Text(text = "Use selected card") },
                        enabled = hasSelection && !isBusy,
                    )
                }
                EpcLine(
                    label = "Scanned EPC",
                    epc = state.scannedEpc?.ifBlank { "--" } ?: "--",
                    color = highlightColor,
                )
                if (!expectedBlank) {
                    when {
                        state.isWriting -> LoadingState(message = "Writing EPC...")
                        state.isVerifying -> LoadingState(message = "Verifying tag EPC...")
                    }
                    state.message?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
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

@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.screens.repair

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexbomber12.memtag.app.HardwareAction
import com.alexbomber12.memtag.domain.InventoryItem
import com.alexbomber12.memtag.domain.repair.RepairActionLog
import com.alexbomber12.memtag.domain.repair.RepairActionResult
import com.alexbomber12.memtag.domain.repair.RepairComparison
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

    if (state.showConfirmation) {
        ConfirmationDialog(state = state, onConfirm = viewModel::confirmRepair, onCancel = viewModel::cancelOperations)
    }

    LaunchedEffect(hardwareActions) {
        hardwareActions.collect { action ->
            if (action == HardwareAction.Rfid) {
                viewModel.readTag()
            }
        }
    }

    val isBusy = state.isReading || state.isWriting || state.isVerifying
    val expectedEpc = state.selectedItem?.epcNormalized ?: state.expectedEpc
    val canRepair =
        state.comparison is RepairComparison.Mismatch &&
            !isBusy

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppCard(title = "Target item") {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::onSearchQueryChange,
                    label = { Text(text = "Search by name, UM, or EPC") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PrimaryButton(
                        text = "Search",
                        onClick = viewModel::searchInventory,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "Clear selection",
                        onClick = viewModel::clearSelection,
                        enabled = state.selectedItem != null,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = expectedEpc.orEmpty(),
                    onValueChange = viewModel::onExpectedEpcChange,
                    label = { Text(text = "Expected EPC") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.selectedItem == null,
                    readOnly = state.selectedItem != null,
                )
                state.selectedItem?.let { item ->
                    SelectedItemCard(item = item)
                } ?: run {
                    if (state.searchResults.isEmpty()) {
                        Text(text = "No results yet.", style = MaterialTheme.typography.labelMedium)
                    } else {
                        state.searchResults.forEach { item ->
                            SearchResultRow(item = item, onSelect = viewModel::selectItem)
                        }
                    }
                }
            }
        }

        item {
            AppCard(title = "Current tag") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PrimaryButton(
                        text = "Read tag EPC",
                        onClick = viewModel::readTag,
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "Stop/Cancel",
                        onClick = viewModel::cancelOperations,
                        enabled = isBusy || state.showConfirmation,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (state.isReading) {
                    LoadingState(message = "Reading tag EPC...")
                } else if (state.currentEpc != null) {
                    EpcLine(label = "Current EPC", epc = state.currentEpc)
                } else {
                    Text(text = "No tag read yet.", style = MaterialTheme.typography.labelMedium)
                }
                if (state.selectedItem == null) {
                    when (val lookup = state.lookupState) {
                        RepairLookupState.Idle -> Unit
                        is RepairLookupState.Found -> {
                            Text(
                                text = "This tag matches ${lookup.item.name ?: "an item"}.",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            SelectedItemCard(item = lookup.item)
                        }
                        RepairLookupState.NotFound -> {
                            Text(
                                text = "No matching item found for this EPC.",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
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
            }
        }

        item {
            AppCard(title = "Comparison") {
                when (val comparison = state.comparison) {
                    RepairComparison.NotReady -> {
                        Text(
                            text = "Not ready. Set an expected EPC and read a tag.",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    is RepairComparison.Match -> {
                        Text(
                            text = "Match: tag EPC already matches the selected item.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        EpcLine(label = "Expected EPC", epc = comparison.expectedEpc)
                    }
                    is RepairComparison.Mismatch -> {
                        Text(
                            text = "Mismatch detected.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        EpcLine(label = "Expected EPC", epc = comparison.expectedEpc, color = MaterialTheme.colorScheme.error)
                        EpcLine(label = "Current EPC", epc = comparison.currentEpc, color = MaterialTheme.colorScheme.error)
                    }
                    is RepairComparison.Invalid -> {
                        ErrorState(message = comparison.message)
                    }
                }
                PrimaryButton(
                    text = "Repair (Write EPC)",
                    onClick = viewModel::startRepairConfirmation,
                    enabled = canRepair,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.selectedItem == null) {
                    Text(
                        text = "Select a target item or expected EPC to enable repair.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        item {
            AppCard(title = "Recent actions") {
                if (state.logs.isEmpty()) {
                    Text(text = "No actions logged yet.", style = MaterialTheme.typography.labelMedium)
                } else {
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
    state: RepairUiState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val expected = state.selectedItem?.epcNormalized ?: state.expectedEpc.orEmpty()
    val current = state.currentEpc.orEmpty()
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text = "Confirm EPC rewrite") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "Review the EPCs before writing:", style = MaterialTheme.typography.bodyMedium)
                EpcLine(
                    label = "Current EPC",
                    epc = current,
                    color = MaterialTheme.colorScheme.error,
                    valueStyle = MaterialTheme.typography.titleMedium,
                )
                EpcLine(
                    label = "Expected EPC",
                    epc = expected,
                    color = MaterialTheme.colorScheme.primary,
                    valueStyle = MaterialTheme.typography.titleMedium,
                )
                if (!state.confirmEnabled) {
                    Text(
                        text = "Confirm will unlock in 2 seconds.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                text = "Confirm Write",
                onClick = onConfirm,
                enabled = state.confirmEnabled,
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
private fun SelectedItemCard(item: InventoryItem) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "Selected item", style = MaterialTheme.typography.labelMedium)
        EpcLine(label = "EPC", epc = item.epcNormalized)
        Text(text = "Name: ${item.name ?: "(none)"}")
        Text(text = "UM: ${item.um ?: "(none)"}")
        Text(text = "Location: ${item.locationPath ?: "(none)"}")
    }
}

@Composable
private fun SearchResultRow(
    item: InventoryItem,
    onSelect: (InventoryItem) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { onSelect(item) }
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = item.name ?: "(unnamed)", style = MaterialTheme.typography.labelMedium)
        EpcLine(label = "EPC", epc = item.epcNormalized)
        Text(text = "UM: ${item.um ?: "(none)"}", style = MaterialTheme.typography.bodySmall)
    }
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

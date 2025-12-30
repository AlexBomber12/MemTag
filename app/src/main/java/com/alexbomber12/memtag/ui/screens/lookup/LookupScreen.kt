@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.screens.lookup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexbomber12.memtag.app.HardwareAction
import com.alexbomber12.memtag.domain.InventoryItem
import com.alexbomber12.memtag.ui.components.AppCard
import com.alexbomber12.memtag.ui.components.ErrorState
import com.alexbomber12.memtag.ui.components.LoadingState
import com.alexbomber12.memtag.ui.components.SecondaryButton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import java.text.DateFormat
import java.util.Date

@Composable
fun LookupScreen(
    viewModel: LookupViewModel,
    hardwareActions: Flow<HardwareAction>,
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val selectedLabel =
        remember(state.selectedEpc, state.results) {
            val selectedItem = state.results.firstOrNull { it.epcNormalized == state.selectedEpc }
            val name = selectedItem?.name?.takeIf { it.isNotBlank() }
            name ?: state.selectedEpc
        }

    DisposableEffect(Unit) {
        onDispose { viewModel.cancelUhfScan() }
    }
    LaunchedEffect(hardwareActions) {
        hardwareActions.collect { action ->
            when (action) {
                HardwareAction.Rfid -> viewModel.scanUhf()
                HardwareAction.Scan -> viewModel.scanQr()
            }
        }
    }

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppCard(title = "Lookup") {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::updateQuery,
                    label = { Text(text = "Search") },
                    placeholder = { Text(text = "Type name, EPC, status, location...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SecondaryButton(
                        text = "Scan RFID",
                        onClick = viewModel::scanUhf,
                        enabled = state.uhfScanStatus !is ScanUhfStatus.Scanning,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "Scan QR",
                        onClick = viewModel::scanQr,
                        enabled = state.scanStatus !is ScanQrStatus.Scanning,
                        modifier = Modifier.weight(1f),
                    )
                }
                when (val uhfStatus = state.uhfScanStatus) {
                    is ScanUhfStatus.Scanning -> {
                        LoadingState(
                            message = "Scanning RFID...",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    is ScanUhfStatus.Error -> {
                        ErrorState(
                            message = uhfStatus.message,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    is ScanUhfStatus.Idle -> Unit
                }
                when (val scanStatus = state.scanStatus) {
                    is ScanQrStatus.Scanning -> {
                        LoadingState(
                            message = "Scanning QR...",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    is ScanQrStatus.Error -> {
                        ErrorState(
                            message = scanStatus.message,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    is ScanQrStatus.Idle -> Unit
                }
                if (selectedLabel != null) {
                    Text(
                        text = "Selected: $selectedLabel",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        state.searchError?.let { message ->
            item {
                ErrorState(
                    message = message,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (state.query.isBlank()) {
            item {
                Text(
                    text = "Type to search the library.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else if (state.isSearching) {
            item {
                LoadingState(
                    message = "Searching...",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else if (state.results.isEmpty()) {
            item {
                Text(
                    text = "No results. Sync may be needed.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            items(state.results, key = { it.entryId }) { item ->
                LookupResultRow(
                    item = item,
                    isSelected = item.epcNormalized == state.selectedEpc,
                    onClick = { viewModel.selectItem(item) },
                )
            }
        }

        item {
            AppCard(
                title = "Sync",
                modifier = Modifier.fillMaxWidth(),
            ) {
                val lastSync = state.lastSyncState
                if (lastSync == null) {
                    Text(text = "Last sync: --")
                } else {
                    Text(text = "Last sync: ${formatTimestamp(lastSync.lastSyncAt)}")
                    Text(text = "Last status: ${lastSync.lastSyncStatus.name.lowercase()}")
                    if (!lastSync.lastErrorMessage.isNullOrBlank()) {
                        Text(text = "Last error: ${lastSync.lastErrorMessage}")
                    }
                }
            }
        }
    }
}

@Composable
private fun LookupResultRow(
    item: InventoryItem,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor =
        if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    val name = item.name?.takeIf { it.isNotBlank() } ?: "(no name)"
    val status = item.status?.takeIf { it.isNotBlank() } ?: "(none)"
    val location = item.locationPath?.takeIf { it.isNotBlank() } ?: "(none)"
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Name: $name",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "EPC: ${item.epcNormalized}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "Status: $status",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Location: $location",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun formatTimestamp(epochMs: Long): String {
    val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    return formatter.format(Date(epochMs))
}

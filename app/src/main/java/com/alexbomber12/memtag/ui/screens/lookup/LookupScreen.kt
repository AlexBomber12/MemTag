@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.screens.lookup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexbomber12.memtag.app.HardwareAction
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
                        LoadingState(message = "Scanning RFID...")
                    }

                    is ScanUhfStatus.Error -> {
                        ErrorState(message = uhfStatus.message)
                    }

                    is ScanUhfStatus.Idle -> Unit
                }
                when (val scanStatus = state.scanStatus) {
                    is ScanQrStatus.Scanning -> {
                        LoadingState(message = "Scanning QR...")
                    }

                    is ScanQrStatus.Error -> {
                        ErrorState(message = scanStatus.message)
                    }

                    is ScanQrStatus.Idle -> Unit
                }
                Text(
                    text = "Last scanned: ${state.lastScannedEpc.ifBlank { "--" }}",
                    style = MaterialTheme.typography.labelMedium,
                )
                when (val status = state.lookupStatus) {
                    is LookupStatus.Idle -> {
                        val message =
                            if (state.lastScannedEpc.isBlank()) {
                                "Scan RFID or QR to look up a tag."
                            } else {
                                "Scan another tag to update the lookup."
                            }
                        Text(text = message, style = MaterialTheme.typography.labelMedium)
                    }

                    is LookupStatus.Loading -> {
                        LoadingState(message = "Looking up EPC...")
                    }

                    is LookupStatus.NotFound -> {
                        Text(text = "Not found. Sync may be needed.", style = MaterialTheme.typography.labelMedium)
                    }

                    is LookupStatus.Error -> {
                        ErrorState(message = status.message)
                    }

                    is LookupStatus.Found -> {
                        Text(text = "Status: Found", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        val foundItem = (state.lookupStatus as? LookupStatus.Found)?.item
        if (foundItem != null) {
            item {
                AppCard(title = "Card") {
                    Text(text = "EPC: ${foundItem.epcNormalized}")
                    Text(text = "Name: ${foundItem.name ?: "(none)"}")
                    Text(text = "Content: ${foundItem.content ?: "(none)"}")
                    Text(text = "Status: ${foundItem.status ?: "(none)"}")
                    Text(text = "Category: ${foundItem.category ?: "(none)"}")
                    Text(text = "Location: ${foundItem.locationPath ?: "(none)"}")
                    Text(text = "Comment: ${foundItem.comment ?: "(none)"}")
                    Text(text = "Label rev: ${foundItem.labelRev ?: "(none)"}")
                    Text(text = "To print: ${foundItem.toPrint?.toString() ?: "(none)"}")
                    Text(text = "UM: ${foundItem.um ?: "(none)"}")
                    Text(text = "QR: ${foundItem.qrRaw ?: "(none)"}")
                }
            }
        }

        item {
            AppCard(title = "Sync") {
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

private fun formatTimestamp(epochMs: Long): String {
    val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    return formatter.format(Date(epochMs))
}

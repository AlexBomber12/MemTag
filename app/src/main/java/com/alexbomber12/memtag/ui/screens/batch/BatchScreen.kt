@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.screens.batch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexbomber12.memtag.app.HardwareAction
import com.alexbomber12.memtag.domain.batch.BatchExtraEntry
import com.alexbomber12.memtag.domain.batch.BatchInputItem
import com.alexbomber12.memtag.domain.batch.BatchSessionEntry
import com.alexbomber12.memtag.domain.batch.BatchStatus
import com.alexbomber12.memtag.ui.components.AppCard
import com.alexbomber12.memtag.ui.components.ErrorState
import com.alexbomber12.memtag.ui.components.LoadingState
import com.alexbomber12.memtag.ui.components.PrimaryButton
import com.alexbomber12.memtag.ui.components.SecondaryButton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BatchScreen(
    viewModel: BatchViewModel,
    hardwareActions: Flow<HardwareAction>,
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }
    var showInvalidRows by rememberSaveable { mutableStateOf(false) }
    val dateFormatter = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val exportFormatter = remember { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US) }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopSweep() }
    }

    LaunchedEffect(state.lastImportReport) {
        showInvalidRows = false
    }

    LaunchedEffect(hardwareActions) {
        hardwareActions.collect { action ->
            if (action == HardwareAction.Rfid) {
                viewModel.onHardwareTrigger()
            }
        }
    }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                viewModel.loadCsv(uri, context.contentResolver)
            }
        }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) {
                viewModel.exportCsv(uri, context.contentResolver)
            }
        }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(text = "Clear batch?") },
            text = { Text(text = "This will remove all batch items, statuses, and extras.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clearBatch()
                    },
                ) {
                    Text(text = "Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(text = "Cancel")
                }
            },
        )
    }

    val canExport = state.inputItems.isNotEmpty() || state.extras.isNotEmpty()

    val filteredItems =
        if (state.mode == BatchMode.MANUAL_SCAN) {
            when (state.manualFilter) {
                BatchFilter.ALL -> state.inputItems
                BatchFilter.MISSING ->
                    state.inputItems.filter {
                        state.sessionMap[it.epcNormalized]?.status == BatchStatus.MISSING
                    }
                BatchFilter.UNKNOWN ->
                    state.inputItems.filter {
                        state.sessionMap[it.epcNormalized]?.status == BatchStatus.UNKNOWN
                    }
            }
        } else {
            state.inputItems
        }

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppCard(title = "Batch Actions") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PrimaryButton(
                        text = "Import CSV",
                        onClick = { importLauncher.launch(arrayOf("text/csv", "text/plain")) },
                        modifier = Modifier.weight(1f),
                        enabled = !state.isImporting,
                    )
                    SecondaryButton(
                        text = "Export CSV",
                        onClick = {
                            val name = "batch_export_${exportFormatter.format(Date())}.csv"
                            exportLauncher.launch(name)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !state.isExporting && canExport,
                    )
                }
                SecondaryButton(
                    text = "Clear batch",
                    onClick = { showClearConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.inputItems.isNotEmpty() || state.extras.isNotEmpty(),
                )
                if (state.isImporting) {
                    LoadingState(message = "Importing CSV...")
                }
                if (state.isExporting) {
                    LoadingState(message = "Exporting CSV...")
                }
                state.lastErrorMessage?.let { message ->
                    ErrorState(message = message)
                }
            }
        }

        item {
            AppCard(title = "Summary") {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SummaryChip(label = "Total", count = state.summary.total)
                    SummaryChip(label = "Present", count = state.summary.present)
                    SummaryChip(label = "Missing", count = state.summary.missing)
                    SummaryChip(label = "Unknown", count = state.summary.unknown)
                    SummaryChip(label = "Extra", count = state.summary.extra)
                }
            }
        }

        item {
            AppCard(title = "Mode") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilterChip(
                        selected = state.mode == BatchMode.SWEEP,
                        onClick = { viewModel.setMode(BatchMode.SWEEP) },
                        label = { Text(text = "Inventory sweep") },
                        enabled = !state.sweepRunning,
                    )
                    FilterChip(
                        selected = state.mode == BatchMode.MANUAL_SCAN,
                        onClick = { viewModel.setMode(BatchMode.MANUAL_SCAN) },
                        label = { Text(text = "Manual scan") },
                        enabled = !state.sweepRunning,
                    )
                }
            }
        }

        if (state.mode == BatchMode.SWEEP) {
            item {
                SweepPanel(
                    state = state,
                    onDurationChange = viewModel::setSweepDuration,
                    onIncludeExtrasChange = viewModel::setIncludeExtrasInExport,
                    onStart = viewModel::startSweep,
                    onStop = viewModel::stopSweep,
                )
            }
        } else {
            item {
                ManualPanel(
                    state = state,
                    dateFormatter = dateFormatter,
                    onScan = viewModel::scanOnce,
                    onMarkMissing = viewModel::markMissingCurrent,
                    onUndo = viewModel::undoLast,
                    onFilterChange = viewModel::setManualFilter,
                )
            }
        }

        val report = state.lastImportReport
        if (report != null) {
            item {
                AppCard(title = "Last Import") {
                    Text(text = "Imported: ${report.importedCount}")
                    Text(text = "Duplicates skipped: ${report.duplicateCount}")
                    Text(text = "Invalid rows: ${report.invalidCount}")
                    if (report.invalidRows.isNotEmpty()) {
                        TextButton(
                            onClick = { showInvalidRows = !showInvalidRows },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(text = if (showInvalidRows) "Hide invalid rows" else "Show invalid rows")
                        }
                        AnimatedVisibility(visible = showInvalidRows) {
                            Text(
                                text = "Rows: ${report.invalidRows.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        item {
            ExtrasPanel(
                extras = state.extras,
                dateFormatter = dateFormatter,
            )
        }

        item {
            Text(
                text = "Batch Items",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        if (state.inputItems.isEmpty()) {
            item {
                Text(text = "Import a CSV to get started.")
            }
        } else if (filteredItems.isEmpty()) {
            item {
                Text(text = "No items match the current filter.")
            }
        } else {
            items(filteredItems, key = { it.epcNormalized }) { item ->
                val session = state.sessionMap[item.epcNormalized]
                BatchItemRow(
                    item = item,
                    session = session,
                    isSelected = item.epcNormalized == state.currentRowEpc,
                    isLastScanned = item.epcNormalized == state.lastScanEpc,
                    dateFormatter = dateFormatter,
                    onClick = { viewModel.selectItem(item.epcNormalized) },
                )
            }
        }
    }
}

@Composable
private fun SweepPanel(
    state: BatchUiState,
    onDurationChange: (Long) -> Unit,
    onIncludeExtrasChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    AppCard(title = "Inventory Sweep") {
        Text(text = "Duration")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val options = listOf(2_000L to "2s", 5_000L to "5s", 10_000L to "10s")
            options.forEach { (duration, label) ->
                FilterChip(
                    selected = state.sweepDurationMs == duration,
                    onClick = { onDurationChange(duration) },
                    label = { Text(text = label) },
                    enabled = !state.sweepRunning,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = state.includeExtrasInExport,
                onCheckedChange = onIncludeExtrasChange,
            )
            Text(text = "Include EXTRA tags in export")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrimaryButton(
                text = "Start sweep",
                onClick = onStart,
                modifier = Modifier.weight(1f),
                enabled = !state.sweepRunning,
            )
            SecondaryButton(
                text = "Stop",
                onClick = onStop,
                modifier = Modifier.weight(1f),
                enabled = state.sweepRunning,
            )
        }
        if (state.sweepRunning) {
            LoadingState(message = "Sweeping tags...")
        }
    }
}

@Composable
private fun ManualPanel(
    state: BatchUiState,
    dateFormatter: DateFormat,
    onScan: () -> Unit,
    onMarkMissing: () -> Unit,
    onUndo: () -> Unit,
    onFilterChange: (BatchFilter) -> Unit,
) {
    AppCard(title = "Manual Scan") {
        val current = state.currentRowEpc
        if (current == null) {
            Text(text = "Select an item from the list to get started.")
        } else {
            Text(
                text = "Current: $current",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            val session = state.sessionMap[current]
            val updatedAt = session?.updatedAt
            if (updatedAt != null && updatedAt > 0) {
                Text(
                    text = "Updated: ${dateFormatter.format(Date(updatedAt))}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (state.lastScanEpc != null) {
            val rssiLabel = state.lastScanRssi?.let { " RSSI $it" }.orEmpty()
            Text(
                text = "Last scan: ${state.lastScanEpc}$rssiLabel",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.lastInfoMessage?.let { message ->
            Text(text = message, style = MaterialTheme.typography.bodySmall)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrimaryButton(
                text = "Scan",
                onClick = onScan,
                modifier = Modifier.weight(1f),
                enabled = !state.isScanning && !state.sweepRunning,
            )
            SecondaryButton(
                text = "Mark missing",
                onClick = onMarkMissing,
                modifier = Modifier.weight(1f),
                enabled = current != null,
            )
        }
        SecondaryButton(
            text = "Undo last",
            onClick = onUndo,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canUndo,
        )
        if (state.isScanning) {
            LoadingState(message = "Scanning RFID...")
        }
        Text(text = "Filter")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilterChip(
                selected = state.manualFilter == BatchFilter.ALL,
                onClick = { onFilterChange(BatchFilter.ALL) },
                label = { Text(text = "All") },
            )
            FilterChip(
                selected = state.manualFilter == BatchFilter.MISSING,
                onClick = { onFilterChange(BatchFilter.MISSING) },
                label = { Text(text = "Missing") },
            )
            FilterChip(
                selected = state.manualFilter == BatchFilter.UNKNOWN,
                onClick = { onFilterChange(BatchFilter.UNKNOWN) },
                label = { Text(text = "Unknown") },
            )
        }
    }
}

@Composable
private fun ExtrasPanel(
    extras: Map<String, BatchExtraEntry>,
    dateFormatter: DateFormat,
) {
    AppCard(title = "Extras") {
        if (extras.isEmpty()) {
            Text(text = "No extra tags scanned yet.")
        } else {
            extras.values.sortedBy { it.epcNormalized }.forEach { extra ->
                ExtraRow(extra = extra, dateFormatter = dateFormatter)
            }
        }
    }
}

@Composable
private fun ExtraRow(
    extra: BatchExtraEntry,
    dateFormatter: DateFormat,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = extra.epcNormalized,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        val seen = extra.lastSeenAt?.let { dateFormatter.format(Date(it)) } ?: "--"
        val rssi = extra.lastRssi?.toString() ?: "--"
        Text(
            text = "Last seen: $seen | RSSI: $rssi",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun BatchItemRow(
    item: BatchInputItem,
    session: BatchSessionEntry?,
    isSelected: Boolean,
    isLastScanned: Boolean,
    dateFormatter: DateFormat,
    onClick: () -> Unit,
) {
    val containerColor =
        when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            isLastScanned -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surface
        }
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = item.epcNormalized,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            item.name?.let { name ->
                Text(text = name, style = MaterialTheme.typography.bodySmall)
            }
            item.note?.let { note ->
                Text(text = note, style = MaterialTheme.typography.bodySmall)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(status = session?.status ?: BatchStatus.UNKNOWN)
                val lastSeen =
                    session?.lastSeenAt?.let { dateFormatter.format(Date(it)) }
                        ?: session?.updatedAt?.let { dateFormatter.format(Date(it)) }
                        ?: "--"
                Text(
                    text = lastSeen,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun SummaryChip(
    label: String,
    count: Int,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = "$label: $count",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun StatusBadge(status: BatchStatus) {
    val (label, containerColor, contentColor) =
        when (status) {
            BatchStatus.UNKNOWN ->
                Triple(
                    "Unknown",
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                )
            BatchStatus.PRESENT ->
                Triple(
                    "Present",
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                )
            BatchStatus.MISSING ->
                Triple(
                    "Missing",
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                )
            BatchStatus.EXTRA ->
                Triple(
                    "Extra",
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                )
        }
    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}

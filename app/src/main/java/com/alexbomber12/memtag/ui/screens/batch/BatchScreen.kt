@file:Suppress("FunctionName")
@file:OptIn(ExperimentalMaterial3Api::class)

package com.alexbomber12.memtag.ui.screens.batch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexbomber12.memtag.app.HardwareAction
import com.alexbomber12.memtag.domain.batch.BatchInputItem
import com.alexbomber12.memtag.domain.batch.BatchSessionEntry
import com.alexbomber12.memtag.domain.batch.BatchStatus
import com.alexbomber12.memtag.ui.components.AppScaffold
import com.alexbomber12.memtag.ui.components.ErrorState
import com.alexbomber12.memtag.ui.components.LoadingState
import com.alexbomber12.memtag.ui.components.PrimaryButton
import com.alexbomber12.memtag.ui.components.SecondaryButton
import com.alexbomber12.memtag.ui.components.SectionCard
import com.alexbomber12.memtag.ui.components.StatChip
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
    var showOverflowMenu by remember { mutableStateOf(false) }
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

    val canExport =
        state.inputItems.isNotEmpty() &&
            !state.manualSessionActive &&
            !state.manualSessionFinishing
    val canClear = state.inputItems.isNotEmpty() || state.extras.isNotEmpty()
    val importTypes = remember { arrayOf("text/csv", "text/plain") }
    val report = state.lastImportReport
    val isEmpty = state.summary.total == 0

    AppScaffold(
        title = "Batch",
        actions = {
            IconButton(onClick = { importLauncher.launch(importTypes) }) {
                Icon(
                    imageVector = Icons.Filled.FileUpload,
                    contentDescription = "Import CSV",
                )
            }
            IconButton(
                onClick = {
                    val name = "batch_export_${exportFormatter.format(Date())}.csv"
                    exportLauncher.launch(name)
                },
                enabled = !state.isExporting && canExport,
            ) {
                Icon(
                    imageVector = Icons.Filled.FileDownload,
                    contentDescription = "Export CSV",
                )
            }
            Box {
                IconButton(onClick = { showOverflowMenu = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "More actions",
                    )
                }
                DropdownMenu(
                    expanded = showOverflowMenu,
                    onDismissRequest = { showOverflowMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(text = "Clear batch") },
                        onClick = {
                            showOverflowMenu = false
                            showClearConfirm = true
                        },
                        enabled = canClear,
                        modifier = Modifier.semantics { contentDescription = "Clear batch" },
                    )
                }
            }
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SummaryChip(label = "Total", count = state.summary.total)
                        SummaryChip(label = "Found", count = state.summary.found)
                        SummaryChip(label = "Not found", count = state.summary.notFound)
                        SummaryChip(label = "Unknown", count = state.summary.unknown)
                    }
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = state.mode == BatchMode.INVENTORY_SWEEP,
                            onClick = { viewModel.setMode(BatchMode.INVENTORY_SWEEP) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            label = { Text(text = "Inventory sweep") },
                            enabled = !state.sweepRunning && !state.manualSessionActive,
                        )
                        SegmentedButton(
                            selected = state.mode == BatchMode.MANUAL_SCAN,
                            onClick = { viewModel.setMode(BatchMode.MANUAL_SCAN) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            label = { Text(text = "Manual scan") },
                            enabled = !state.sweepRunning && !state.manualSessionActive,
                        )
                    }
                }
            }

            if (state.isImporting) {
                item { LoadingState(message = "Importing CSV...", modifier = Modifier.fillMaxWidth()) }
            }
            if (state.isExporting) {
                item { LoadingState(message = "Exporting CSV...", modifier = Modifier.fillMaxWidth()) }
            }
            state.lastErrorMessage?.let { message ->
                item { ErrorState(message = message, modifier = Modifier.fillMaxWidth()) }
            }

            item {
                if (state.mode == BatchMode.INVENTORY_SWEEP) {
                    SweepPanel(
                        state = state,
                        onToggle = {
                            if (state.sweepRunning) {
                                viewModel.stopSweep()
                            } else {
                                viewModel.startSweep()
                            }
                        },
                    )
                } else {
                    ManualPanel(
                        state = state,
                        dateFormatter = dateFormatter,
                        onScan = viewModel::scanOnce,
                        onToggleSession = viewModel::toggleManualSession,
                        onUndo = viewModel::undoLast,
                    )
                }
            }

            if (report != null) {
                item {
                    SectionCard(
                        title = "Last Import",
                        modifier = Modifier.fillMaxWidth(),
                    ) {
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

            if (isEmpty) {
                item {
                    EmptyState(onImport = { importLauncher.launch(importTypes) })
                }
            } else {
                items(state.inputItems, key = { it.epcNormalized }) { item ->
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
}

@Composable
private fun SweepPanel(
    state: BatchUiState,
    onToggle: () -> Unit,
) {
    SectionCard(title = "Inventory sweep") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Session", style = MaterialTheme.typography.labelMedium)
            StatChip(label = if (state.sweepRunning) "Running" else "Stopped")
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SummaryChip(label = "Found", count = state.summary.found)
            SummaryChip(label = "Unknown", count = state.summary.unknown)
            SummaryChip(label = "Extra", count = state.summary.extra)
        }
        PrimaryButton(
            text = if (state.sweepRunning) "Stop sweep" else "Start sweep",
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.sweepRunning) {
            LoadingState(message = "Sweeping tags...", modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ManualPanel(
    state: BatchUiState,
    dateFormatter: DateFormat,
    onScan: () -> Unit,
    onToggleSession: () -> Unit,
    onUndo: () -> Unit,
) {
    SectionCard(title = "Manual scan") {
        val sessionLabel =
            when {
                state.manualSessionFinishing -> "Finishing"
                state.manualSessionActive -> "Running"
                else -> "Stopped"
            }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Session", style = MaterialTheme.typography.labelMedium)
            StatChip(label = sessionLabel)
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SummaryChip(label = "Scans", count = state.manualScanCount)
            SummaryChip(label = "Found", count = state.manualFoundCount)
        }
        val current = state.currentRowEpc
        if (current != null) {
            Text(
                text = "Current: $current",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
            val session = state.sessionMap[current]
            val updatedAt = session?.updatedAt
            if (updatedAt != null) {
                Text(
                    text = "Updated: ${dateFormatter.format(Date(updatedAt))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.lastScanEpc != null) {
            val rssiLabel = state.lastScanRssi?.let { " RSSI $it" }.orEmpty()
            val matchLabel =
                when (state.lastScanMatched) {
                    true -> "Matched"
                    false -> "Extra"
                    null -> null
                }
            val matchSuffix = matchLabel?.let { " ($it)" }.orEmpty()
            Text(
                text = "Last scan: ${state.lastScanEpc}$rssiLabel$matchSuffix",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.lastInfoMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrimaryButton(
                text =
                    when {
                        state.manualSessionFinishing -> "Finishing..."
                        state.manualSessionActive -> "Finish session"
                        else -> "Start session"
                    },
                onClick = onToggleSession,
                modifier = Modifier.weight(1f),
                enabled = !state.isScanning && !state.sweepRunning && !state.manualSessionFinishing,
            )
            SecondaryButton(
                text = "Scan",
                onClick = onScan,
                modifier = Modifier.weight(1f),
                enabled =
                    state.manualSessionActive &&
                        !state.manualSessionFinishing &&
                        !state.isScanning &&
                        !state.sweepRunning,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = onUndo,
                enabled = state.canUndo,
            ) {
                Text(text = "Undo last")
            }
        }
        if (state.isScanning) {
            LoadingState(message = "Scanning RFID...", modifier = Modifier.fillMaxWidth())
        }
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
    val status = session?.status ?: BatchStatus.UNKNOWN
    val updatedAt = session?.updatedAt?.let { dateFormatter.format(Date(it)) } ?: "--"
    val nameText = item.name.ifBlank { "--" }
    ListItem(
        headlineContent = {
            Text(
                text = nameText,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = item.epcNormalized,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Status: ${statusLabel(status)} | Updated: $updatedAt",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = { StatusBadge(status = status) },
        colors = ListItemDefaults.colors(containerColor = containerColor),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
    )
}

@Composable
private fun EmptyState(onImport: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "No batch loaded", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Import a CSV to start.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryButton(
            text = "Import CSV",
            onClick = onImport,
            fullWidth = false,
        )
    }
}

@Composable
private fun SummaryChip(
    label: String,
    count: Int,
) {
    StatChip(label = "$label $count")
}

private fun statusLabel(status: BatchStatus): String {
    return when (status) {
        BatchStatus.UNKNOWN -> "Unknown"
        BatchStatus.FOUND -> "Found"
        BatchStatus.NOT_FOUND -> "Not found"
        BatchStatus.EXTRA -> "Extra"
    }
}

@Composable
private fun StatusBadge(status: BatchStatus) {
    val label = statusLabel(status)
    val (containerColor, contentColor) =
        when (status) {
            BatchStatus.UNKNOWN ->
                Pair(
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                )
            BatchStatus.FOUND ->
                Pair(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                )
            BatchStatus.NOT_FOUND ->
                Pair(
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                )
            BatchStatus.EXTRA ->
                Pair(
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

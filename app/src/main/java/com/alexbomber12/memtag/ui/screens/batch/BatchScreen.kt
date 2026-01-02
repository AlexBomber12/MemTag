@file:Suppress("FunctionName")
@file:OptIn(ExperimentalMaterial3Api::class)

package com.alexbomber12.memtag.ui.screens.batch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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
import com.alexbomber12.memtag.ui.components.ResultsSectionHeader
import com.alexbomber12.memtag.ui.components.SecondaryButton
import com.alexbomber12.memtag.ui.components.SectionCard
import com.alexbomber12.memtag.ui.components.StatChip
import com.alexbomber12.memtag.ui.theme.SuccessGreen
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
    var showOverflowMenu by remember { mutableStateOf(false) }
    val dateFormatter = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val exportFormatter = remember { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US) }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopSweep() }
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
    val isEmpty = state.summary.total == 0
    val menuItemPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    val segmentedColors =
        SegmentedButtonDefaults.colors(
            activeContainerColor = MaterialTheme.colorScheme.surface,
            activeContentColor = MaterialTheme.colorScheme.onSurface,
            inactiveContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            activeBorderColor = MaterialTheme.colorScheme.surfaceVariant,
            inactiveBorderColor = MaterialTheme.colorScheme.surfaceVariant,
        )

    AppScaffold(
        title = "Batch Scan",
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
                    modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline),
                    shape = RectangleShape,
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    DropdownMenuItem(
                        text = { Text(text = "Import CSV") },
                        onClick = {
                            showOverflowMenu = false
                            importLauncher.launch(importTypes)
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.FileUpload,
                                contentDescription = null,
                            )
                        },
                        contentPadding = menuItemPadding,
                    )
                    DropdownMenuItem(
                        text = { Text(text = "Export CSV") },
                        onClick = {
                            showOverflowMenu = false
                            val name = "batch_export_${exportFormatter.format(Date())}.csv"
                            exportLauncher.launch(name)
                        },
                        enabled = !state.isExporting && canExport,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.FileDownload,
                                contentDescription = null,
                            )
                        },
                        contentPadding = menuItemPadding,
                    )
                    DropdownMenuItem(
                        text = { Text(text = "Clear batch") },
                        onClick = {
                            showOverflowMenu = false
                            showClearConfirm = true
                        },
                        enabled = canClear,
                        modifier = Modifier.semantics { contentDescription = "Clear batch" },
                        contentPadding = menuItemPadding,
                    )
                }
            }
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SummaryStatCard(
                            label = "Total",
                            count = state.summary.total,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            valueColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        SummaryStatCard(
                            label = "Found",
                            count = state.summary.found,
                            labelColor = SuccessGreen,
                            valueColor = SuccessGreen,
                            modifier = Modifier.weight(1f),
                        )
                        SummaryStatCard(
                            label = "Extra",
                            count = state.summary.extra,
                            labelColor = MaterialTheme.colorScheme.tertiary,
                            valueColor = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.weight(1f),
                        )
                        SummaryStatCard(
                            label = "Unknown",
                            count = state.summary.unknown,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            valueColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        SingleChoiceSegmentedButtonRow(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(4.dp),
                        ) {
                            SegmentedButton(
                                selected = state.mode == BatchMode.INVENTORY_SWEEP,
                                onClick = { viewModel.setMode(BatchMode.INVENTORY_SWEEP) },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                label = { Text(text = "Inventory Sweep") },
                                enabled = !state.sweepRunning && !state.manualSessionActive,
                                colors = segmentedColors,
                            )
                            SegmentedButton(
                                selected = state.mode == BatchMode.MANUAL_SCAN,
                                onClick = { viewModel.setMode(BatchMode.MANUAL_SCAN) },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                label = { Text(text = "Manual Scan") },
                                enabled = !state.sweepRunning && !state.manualSessionActive,
                                colors = segmentedColors,
                            )
                        }
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
                val firstResult = state.inputItems.firstOrNull()
                Column(modifier = Modifier.fillMaxWidth()) {
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
                            onScan = viewModel::scanOnce,
                            onToggleSession = viewModel::toggleManualSession,
                            onUndo = viewModel::undoLast,
                        )
                    }
                    if (!isEmpty) {
                        ResultsSectionHeader(
                            label = "LIVE RESULTS",
                            onClear = { showClearConfirm = true },
                            canClear = canClear,
                        )
                        if (firstResult != null) {
                            val session = state.sessionMap[firstResult.epcNormalized]
                            val lastUpdatedLabel =
                                session?.updatedAt?.let { dateFormatter.format(Date(it)) }
                            BatchItemRow(
                                item = firstResult,
                                session = session,
                                isSelected = firstResult.epcNormalized == state.currentRowEpc,
                                isLastScanned = firstResult.epcNormalized == state.lastScanEpc,
                                lastUpdatedLabel = lastUpdatedLabel,
                                onClick = { viewModel.selectItem(firstResult.epcNormalized) },
                            )
                        }
                    }
                }
            }

            if (isEmpty) {
                item {
                    EmptyState(onImport = { importLauncher.launch(importTypes) })
                }
            } else {
                items(state.inputItems.drop(1), key = { it.epcNormalized }) { item ->
                    val session = state.sessionMap[item.epcNormalized]
                    val lastUpdatedLabel =
                        session?.updatedAt?.let { dateFormatter.format(Date(it)) }
                    BatchItemRow(
                        item = item,
                        session = session,
                        isSelected = item.epcNormalized == state.currentRowEpc,
                        isLastScanned = item.epcNormalized == state.lastScanEpc,
                        lastUpdatedLabel = lastUpdatedLabel,
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
    val statusLabel = if (state.sweepRunning) "Running" else "Idle"
    val subTitle = if (state.sweepRunning) "Sweeping tags..." else null
    val buttonIcon = if (state.sweepRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "Session", style = MaterialTheme.typography.titleMedium)
                if (!subTitle.isNullOrBlank()) {
                    Text(
                        text = subTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            StatChip(label = statusLabel)
        }
        PrimaryButton(
            text = if (state.sweepRunning) "Stop Sweep" else "Start Sweep",
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(imageVector = buttonIcon, contentDescription = null) },
        )
        if (state.sweepRunning) {
            LoadingState(message = "Sweeping tags...", modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ManualPanel(
    state: BatchUiState,
    onScan: () -> Unit,
    onToggleSession: () -> Unit,
    onUndo: () -> Unit,
) {
    SectionCard {
        val sessionLabel =
            when {
                state.manualSessionFinishing -> "Finishing"
                state.manualSessionActive -> "Running"
                else -> "Idle"
            }
        val subTitle = if (state.manualSessionFinishing) "Finishing session..." else null
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "Session", style = MaterialTheme.typography.titleMedium)
                if (!subTitle.isNullOrBlank()) {
                    Text(
                        text = subTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            StatChip(label = sessionLabel)
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
                        state.manualSessionActive -> "Finish Session"
                        else -> "Start Session"
                    },
                onClick = onToggleSession,
                modifier = Modifier.weight(1f),
                enabled = !state.isScanning && !state.sweepRunning && !state.manualSessionFinishing,
            )
            SecondaryButton(
                text = "Scan RFID",
                onClick = onScan,
                modifier = Modifier.weight(1f),
                enabled =
                    state.manualSessionActive &&
                        !state.manualSessionFinishing &&
                        !state.isScanning &&
                        !state.sweepRunning,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.CenterFocusStrong,
                        contentDescription = "Scan RFID",
                    )
                },
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
    }
}

@Composable
private fun BatchItemRow(
    item: BatchInputItem,
    session: BatchSessionEntry?,
    isSelected: Boolean,
    isLastScanned: Boolean,
    lastUpdatedLabel: String?,
    onClick: () -> Unit,
) {
    val containerColor =
        when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            isLastScanned -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surface
        }
    val status = session?.status ?: BatchStatus.UNKNOWN
    val accentColor = statusAccentColor(status)
    val nameText = item.name.ifBlank { "Unidentified item" }
    val isPlaceholderName = item.name.isBlank()
    OutlinedCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        colors = CardDefaults.outlinedCardColors(containerColor = containerColor),
        border = CardDefaults.outlinedCardBorder(),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
        ) {
            Box(
                modifier =
                    Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(accentColor),
            )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = nameText,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color =
                            if (isPlaceholderName) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        fontStyle = if (isPlaceholderName) FontStyle.Italic else FontStyle.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    StatusBadge(status = status)
                }
                Text(
                    text = "EPC: ${formatEpcForDisplay(item.epcNormalized)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace,
                )
                if (!lastUpdatedLabel.isNullOrBlank()) {
                    Text(
                        text = "Last updated: $lastUpdatedLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
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
private fun SummaryStatCard(
    label: String,
    count: Int,
    labelColor: Color,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier.heightIn(min = 76.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = valueColor,
            )
        }
    }
}

@Composable
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
    val label = statusLabel(status).uppercase()
    val (containerColor, contentColor) = statusBadgeColors(status)
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

@Composable
private fun statusBadgeColors(status: BatchStatus): Pair<Color, Color> {
    return when (status) {
        BatchStatus.UNKNOWN ->
            Pair(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        BatchStatus.FOUND ->
            Pair(
                SuccessGreen.copy(alpha = 0.16f),
                SuccessGreen,
            )
        BatchStatus.NOT_FOUND ->
            Pair(
                MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                MaterialTheme.colorScheme.error,
            )
        BatchStatus.EXTRA ->
            Pair(
                MaterialTheme.colorScheme.tertiaryContainer,
                MaterialTheme.colorScheme.onTertiaryContainer,
            )
    }
}

@Composable
private fun statusAccentColor(status: BatchStatus): Color {
    return when (status) {
        BatchStatus.UNKNOWN -> MaterialTheme.colorScheme.outline
        BatchStatus.FOUND -> SuccessGreen
        BatchStatus.NOT_FOUND -> MaterialTheme.colorScheme.error
        BatchStatus.EXTRA -> MaterialTheme.colorScheme.tertiary
    }
}

private fun formatEpcForDisplay(epcNormalized: String): String {
    return epcNormalized.chunked(4).joinToString(" ")
}

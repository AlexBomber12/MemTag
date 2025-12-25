@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.screens.queue

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.alexbomber12.memtag.domain.queue.QueueItem
import com.alexbomber12.memtag.domain.queue.QueueItemStatus
import com.alexbomber12.memtag.ui.components.AppCard
import com.alexbomber12.memtag.ui.components.ErrorState
import com.alexbomber12.memtag.ui.components.LoadingState
import com.alexbomber12.memtag.ui.components.PrimaryButton
import com.alexbomber12.memtag.ui.components.SecondaryButton
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun QueueScreen(
    viewModel: QueueViewModel,
    onStartFind: (String, Boolean) -> Unit,
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }
    var showInvalidRows by rememberSaveable { mutableStateOf(false) }
    val dateFormatter = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val exportFormatter = remember { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US) }

    LaunchedEffect(state.lastImportReport) {
        showInvalidRows = false
    }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                viewModel.importCsv(uri, context.contentResolver)
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
            title = { Text(text = "Clear queue?") },
            text = { Text(text = "This will remove all queue items and statuses.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clearQueue()
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

    val currentItem = state.items.firstOrNull { it.epcNormalized == state.currentEpc }

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppCard(title = "Queue Actions") {
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
                            val name = "queue_export_${exportFormatter.format(Date())}.csv"
                            exportLauncher.launch(name)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !state.isExporting && state.items.isNotEmpty(),
                    )
                }
                SecondaryButton(
                    text = "Clear queue",
                    onClick = { showClearConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.items.isNotEmpty(),
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
                    SummaryChip(label = "Pending", count = state.summary.pending)
                    SummaryChip(label = "Found", count = state.summary.found)
                    SummaryChip(label = "Skipped", count = state.summary.skipped)
                    SummaryChip(label = "Not found", count = state.summary.notFound)
                }
            }
        }

        item {
            AppCard(title = "Current Item") {
                if (currentItem == null) {
                    Text(text = "No items in the queue.")
                } else {
                    Text(
                        text = currentItem.epcNormalized,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StatusBadge(status = currentItem.status)
                        Text(
                            text = "Updated: ${dateFormatter.format(Date(currentItem.updatedAt))}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    PrimaryButton(
                        text = "Start Find",
                        onClick = { onStartFind(currentItem.epcNormalized, true) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.currentEpc != null,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SecondaryButton(
                            text = "Previous",
                            onClick = viewModel::selectPreviousPending,
                            modifier = Modifier.weight(1f),
                            enabled = state.items.isNotEmpty(),
                        )
                        SecondaryButton(
                            text = "Next",
                            onClick = viewModel::selectNextPending,
                            modifier = Modifier.weight(1f),
                            enabled = state.items.isNotEmpty(),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SecondaryButton(
                            text = "Mark Found",
                            onClick = { viewModel.markStatus(QueueItemStatus.FOUND) },
                            modifier = Modifier.weight(1f),
                            enabled = state.currentEpc != null,
                        )
                        SecondaryButton(
                            text = "Mark Skipped",
                            onClick = { viewModel.markStatus(QueueItemStatus.SKIPPED) },
                            modifier = Modifier.weight(1f),
                            enabled = state.currentEpc != null,
                        )
                    }
                    SecondaryButton(
                        text = "Mark Not Found",
                        onClick = { viewModel.markStatus(QueueItemStatus.NOT_FOUND) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.currentEpc != null,
                    )
                }
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
            Text(
                text = "Queue Items",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        if (state.items.isEmpty()) {
            item {
                Text(text = "Import a CSV to get started.")
            }
        } else {
            items(state.items, key = { it.epcNormalized }) { item ->
                QueueItemRow(
                    item = item,
                    isSelected = item.epcNormalized == state.currentEpc,
                    dateFormatter = dateFormatter,
                    onClick = { viewModel.selectItem(item.epcNormalized) },
                )
            }
        }
    }
}

@Composable
private fun QueueItemRow(
    item: QueueItem,
    isSelected: Boolean,
    dateFormatter: DateFormat,
    onClick: () -> Unit,
) {
    val containerColor =
        if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(status = item.status)
                Text(
                    text = dateFormatter.format(Date(item.updatedAt)),
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
private fun StatusBadge(status: QueueItemStatus) {
    val (label, containerColor, contentColor) =
        when (status) {
            QueueItemStatus.PENDING ->
                Triple(
                    "Pending",
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                )
            QueueItemStatus.FOUND ->
                Triple(
                    "Found",
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                )
            QueueItemStatus.SKIPPED ->
                Triple(
                    "Skipped",
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                )
            QueueItemStatus.NOT_FOUND ->
                Triple(
                    "Not found",
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
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

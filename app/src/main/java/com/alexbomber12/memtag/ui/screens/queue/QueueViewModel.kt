package com.alexbomber12.memtag.ui.screens.queue

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexbomber12.memtag.data.queue.QueueCsvExporter
import com.alexbomber12.memtag.data.queue.QueueCsvParser
import com.alexbomber12.memtag.data.queue.QueueRepository
import com.alexbomber12.memtag.domain.queue.QueueItem
import com.alexbomber12.memtag.domain.queue.QueueItemStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

data class QueueSummary(
    val pending: Int = 0,
    val found: Int = 0,
    val skipped: Int = 0,
    val notFound: Int = 0,
)

data class QueueImportReport(
    val importedCount: Int,
    val duplicateCount: Int,
    val invalidCount: Int,
    val invalidRows: List<Int>,
)

data class QueueUiState(
    val items: List<QueueItem> = emptyList(),
    val summary: QueueSummary = QueueSummary(),
    val currentEpc: String? = null,
    val isImporting: Boolean = false,
    val isExporting: Boolean = false,
    val lastImportReport: QueueImportReport? = null,
    val lastErrorMessage: String? = null,
)

class QueueViewModel(
    private val repository: QueueRepository,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(QueueUiState())
    val uiState: StateFlow<QueueUiState> = mutableState

    private var importJob: Job? = null
    private var exportJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                repository.observeItems(),
                repository.observeMeta(),
            ) { items, meta -> items to meta }
                .collect { (items, meta) ->
                    val summary = summaryFor(items)
                    val resolvedCurrent = resolveCurrentEpc(items, meta?.currentEpcNormalized)
                    if (resolvedCurrent != meta?.currentEpcNormalized) {
                        repository.setCurrentEpc(resolvedCurrent)
                    }
                    mutableState.update {
                        it.copy(
                            items = items,
                            summary = summary,
                            currentEpc = resolvedCurrent,
                        )
                    }
                }
        }
    }

    fun selectItem(epcNormalized: String) {
        viewModelScope.launch {
            repository.setCurrentEpc(epcNormalized)
        }
    }

    fun selectNextPending() {
        viewModelScope.launch {
            val next = findNextPending(uiState.value.items, uiState.value.currentEpc)
            if (next != null) {
                repository.setCurrentEpc(next.epcNormalized)
            }
        }
    }

    fun selectPreviousPending() {
        viewModelScope.launch {
            val prev = findPreviousPending(uiState.value.items, uiState.value.currentEpc)
            if (prev != null) {
                repository.setCurrentEpc(prev.epcNormalized)
            }
        }
    }

    fun markStatus(status: QueueItemStatus) {
        val current = uiState.value.currentEpc ?: return
        viewModelScope.launch {
            repository.updateStatus(current, status, clock())
            val next = findNextPending(uiState.value.items, current)
            if (next != null) {
                repository.setCurrentEpc(next.epcNormalized)
            }
        }
    }

    fun clearQueue() {
        viewModelScope.launch {
            repository.clearAll()
            mutableState.update {
                it.copy(
                    lastImportReport = null,
                    lastErrorMessage = null,
                )
            }
        }
    }

    fun importCsv(
        uri: Uri,
        contentResolver: ContentResolver,
    ) {
        if (importJob != null) {
            return
        }
        mutableState.update { it.copy(isImporting = true, lastErrorMessage = null) }
        val job =
            viewModelScope.launch {
                val report =
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val text =
                                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                                    ?: throw IOException("Unable to read CSV.")
                            val parsed = QueueCsvParser.parse(text)
                            val now = clock()
                            val insertResult = repository.insertItems(parsed.epcs, now)
                            repository.setLastImportAt(now)
                            QueueImportReport(
                                importedCount = insertResult.insertedCount,
                                duplicateCount = parsed.duplicateCount + insertResult.ignoredCount,
                                invalidCount = parsed.invalidRows.size,
                                invalidRows = parsed.invalidRows.take(MAX_INVALID_ROWS),
                            )
                        }
                    }
                mutableState.update { state ->
                    val error = report.exceptionOrNull()
                    state.copy(
                        isImporting = false,
                        lastImportReport = report.getOrNull() ?: state.lastImportReport,
                        lastErrorMessage = error?.message ?: if (error != null) "Import failed." else null,
                    )
                }
            }
        importJob = job
        job.invokeOnCompletion { importJob = null }
    }

    fun exportCsv(
        uri: Uri,
        contentResolver: ContentResolver,
    ) {
        if (exportJob != null) {
            return
        }
        mutableState.update { it.copy(isExporting = true, lastErrorMessage = null) }
        val job =
            viewModelScope.launch {
                val result =
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val items = repository.getAll()
                            val csv = QueueCsvExporter.export(items)
                            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                                writer.write(csv)
                            } ?: throw IOException("Unable to write CSV.")
                            repository.setLastExportAt(clock())
                        }
                    }
                mutableState.update { state ->
                    val error = result.exceptionOrNull()
                    state.copy(
                        isExporting = false,
                        lastErrorMessage = error?.message ?: if (error != null) "Export failed." else null,
                    )
                }
            }
        exportJob = job
        job.invokeOnCompletion { exportJob = null }
    }

    private fun summaryFor(items: List<QueueItem>): QueueSummary {
        var pending = 0
        var found = 0
        var skipped = 0
        var notFound = 0
        items.forEach { item ->
            when (item.status) {
                QueueItemStatus.PENDING -> pending += 1
                QueueItemStatus.FOUND -> found += 1
                QueueItemStatus.SKIPPED -> skipped += 1
                QueueItemStatus.NOT_FOUND -> notFound += 1
            }
        }
        return QueueSummary(
            pending = pending,
            found = found,
            skipped = skipped,
            notFound = notFound,
        )
    }

    private fun resolveCurrentEpc(
        items: List<QueueItem>,
        currentEpc: String?,
    ): String? {
        if (items.isEmpty()) {
            return null
        }
        val existing = items.firstOrNull { it.epcNormalized == currentEpc }
        if (existing != null) {
            return existing.epcNormalized
        }
        val pending = items.firstOrNull { it.status == QueueItemStatus.PENDING }
        return pending?.epcNormalized ?: items.first().epcNormalized
    }

    private fun findNextPending(
        items: List<QueueItem>,
        currentEpc: String?,
    ): QueueItem? {
        if (items.isEmpty()) {
            return null
        }
        val startIndex = items.indexOfFirst { it.epcNormalized == currentEpc }
        val next =
            if (startIndex >= 0) {
                items.drop(startIndex + 1).firstOrNull { it.status == QueueItemStatus.PENDING }
            } else {
                null
            }
        return next ?: items.firstOrNull { it.status == QueueItemStatus.PENDING && it.epcNormalized != currentEpc }
    }

    private fun findPreviousPending(
        items: List<QueueItem>,
        currentEpc: String?,
    ): QueueItem? {
        if (items.isEmpty()) {
            return null
        }
        val startIndex = items.indexOfFirst { it.epcNormalized == currentEpc }
        val prev =
            if (startIndex > 0) {
                items.subList(0, startIndex).lastOrNull { it.status == QueueItemStatus.PENDING }
            } else {
                null
            }
        return prev ?: items.lastOrNull { it.status == QueueItemStatus.PENDING && it.epcNormalized != currentEpc }
    }

    private companion object {
        const val MAX_INVALID_ROWS = 50
    }
}

package com.alexbomber12.memtag.ui.screens.batch

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexbomber12.memtag.data.batch.BatchCsvExporter
import com.alexbomber12.memtag.data.batch.BatchCsvParser
import com.alexbomber12.memtag.data.batch.BatchRepository
import com.alexbomber12.memtag.domain.batch.BatchExportRow
import com.alexbomber12.memtag.domain.batch.BatchExtraEntry
import com.alexbomber12.memtag.domain.batch.BatchInputItem
import com.alexbomber12.memtag.domain.batch.BatchSessionEntry
import com.alexbomber12.memtag.domain.batch.BatchSource
import com.alexbomber12.memtag.domain.batch.BatchStatus
import com.alexbomber12.memtag.domain.batch.BatchUhfUseCase
import com.alexbomber12.memtag.integrations.uhf.UhfError
import com.alexbomber12.memtag.integrations.uhf.UhfException
import com.alexbomber12.memtag.util.epc.EpcNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.collections.ArrayDeque

enum class BatchMode {
    SWEEP,
    MANUAL_SCAN,
}

enum class BatchFilter {
    ALL,
    MISSING,
    UNKNOWN,
}

private const val DEFAULT_SWEEP_DURATION_MS = 5_000L
private const val SCAN_TIMEOUT_MS = 1_500L
private const val MAX_INVALID_ROWS = 50

data class BatchSummary(
    val total: Int = 0,
    val present: Int = 0,
    val missing: Int = 0,
    val unknown: Int = 0,
    val extra: Int = 0,
)

data class BatchImportReport(
    val importedCount: Int,
    val duplicateCount: Int,
    val invalidCount: Int,
    val invalidRows: List<Int>,
)

data class BatchUiState(
    val inputItems: List<BatchInputItem> = emptyList(),
    val sessionMap: Map<String, BatchSessionEntry> = emptyMap(),
    val extras: Map<String, BatchExtraEntry> = emptyMap(),
    val summary: BatchSummary = BatchSummary(),
    val mode: BatchMode = BatchMode.SWEEP,
    val sweepRunning: Boolean = false,
    val sweepDurationMs: Long = DEFAULT_SWEEP_DURATION_MS,
    val includeExtrasInExport: Boolean = true,
    val currentRowEpc: String? = null,
    val lastScanEpc: String? = null,
    val lastScanRssi: Int? = null,
    val isImporting: Boolean = false,
    val isExporting: Boolean = false,
    val isScanning: Boolean = false,
    val manualFilter: BatchFilter = BatchFilter.ALL,
    val lastImportReport: BatchImportReport? = null,
    val lastErrorMessage: String? = null,
    val lastInfoMessage: String? = null,
    val canUndo: Boolean = false,
)

class BatchViewModel(
    private val repository: BatchRepository,
    private val uhfUseCase: BatchUhfUseCase,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(BatchUiState())
    val uiState: StateFlow<BatchUiState> = mutableState

    private var importJob: Job? = null
    private var exportJob: Job? = null
    private var sweepJob: Job? = null
    private var scanJob: Job? = null
    private val undoStack = ArrayDeque<BatchUndoAction>()

    init {
        viewModelScope.launch {
            combine(
                repository.observeItems(),
                repository.observeMeta(),
            ) { items, meta -> items to meta }
                .collect { (items, meta) ->
                    val inputItems = items.map { it.input }
                    val sessionMap = items.associate { it.input.epcNormalized to it.session }
                    val resolvedCurrent = resolveCurrentEpc(inputItems, sessionMap, meta?.currentEpcNormalized)
                    if (resolvedCurrent != meta?.currentEpcNormalized) {
                        repository.setCurrentEpc(resolvedCurrent)
                    }
                    mutableState.update { state ->
                        state.copy(
                            inputItems = inputItems,
                            sessionMap = sessionMap,
                            summary = summaryFor(inputItems, sessionMap, state.extras),
                            currentRowEpc = resolvedCurrent,
                        )
                    }
                }
        }
    }

    fun setMode(mode: BatchMode) {
        if (uiState.value.sweepRunning) {
            return
        }
        mutableState.update { it.copy(mode = mode, lastErrorMessage = null, lastInfoMessage = null) }
    }

    fun setSweepDuration(durationMs: Long) {
        if (uiState.value.sweepRunning) {
            return
        }
        mutableState.update { it.copy(sweepDurationMs = durationMs) }
    }

    fun setIncludeExtrasInExport(include: Boolean) {
        mutableState.update { it.copy(includeExtrasInExport = include) }
    }

    fun setManualFilter(filter: BatchFilter) {
        mutableState.update { it.copy(manualFilter = filter) }
    }

    fun selectItem(epcNormalized: String) {
        mutableState.update { it.copy(currentRowEpc = epcNormalized) }
        viewModelScope.launch {
            repository.setCurrentEpc(epcNormalized)
        }
    }

    fun clearBatch() {
        viewModelScope.launch {
            repository.clearAll()
            undoStack.clear()
            mutableState.update {
                it.copy(
                    extras = emptyMap(),
                    lastImportReport = null,
                    lastErrorMessage = null,
                    lastInfoMessage = null,
                    lastScanEpc = null,
                    lastScanRssi = null,
                    canUndo = false,
                )
            }
        }
    }

    fun loadCsv(
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
                            val parsed = BatchCsvParser.parse(text)
                            if (parsed.items.isEmpty()) {
                                throw IOException("No valid EPC rows found.")
                            }
                            val now = clock()
                            repository.clearAll()
                            val insertResult = repository.insertItems(parsed.items, now)
                            repository.setLastImportAt(now)
                            BatchImportReport(
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
                        lastInfoMessage = null,
                        extras = if (report.isSuccess) emptyMap() else state.extras,
                        lastScanEpc = if (report.isSuccess) null else state.lastScanEpc,
                        lastScanRssi = if (report.isSuccess) null else state.lastScanRssi,
                        canUndo = if (report.isSuccess) false else state.canUndo,
                    )
                }
                if (report.isSuccess) {
                    undoStack.clear()
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
                            val includeExtras = uiState.value.includeExtrasInExport
                            val rows = buildExportRows(items, includeExtras)
                            val csv = BatchCsvExporter.export(rows)
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

    fun startSweep() {
        val state = uiState.value
        if (state.sweepRunning || scanJob != null) {
            return
        }
        mutableState.update { it.copy(sweepRunning = true, lastErrorMessage = null, lastInfoMessage = null) }
        val job =
            viewModelScope.launch {
                val durationMs = uiState.value.sweepDurationMs
                val result = runCatching { uhfUseCase.runSweep(durationMs) }
                if (result.isFailure) {
                    mutableState.update {
                        it.copy(
                            sweepRunning = false,
                            lastErrorMessage = mapUhfError(result.exceptionOrNull()),
                        )
                    }
                    return@launch
                }
                val sweepResult = result.getOrNull()
                val now = clock()
                val seenEntries = sweepResult?.entries.orEmpty()
                val inputItems = uiState.value.inputItems
                val sessionMap = uiState.value.sessionMap
                withContext(Dispatchers.IO) {
                    inputItems.forEach { item ->
                        val previous = sessionMap[item.epcNormalized]
                        val seen = seenEntries[item.epcNormalized]
                        val nextStatus = if (seen != null) BatchStatus.PRESENT else BatchStatus.MISSING
                        val lastSeenAt = seen?.lastSeenAt ?: previous?.lastSeenAt
                        val lastRssi = seen?.bestRssi ?: previous?.lastRssi
                        repository.updateSession(
                            epcNormalized = item.epcNormalized,
                            status = nextStatus,
                            updatedAt = now,
                            lastSeenAt = lastSeenAt,
                            lastRssi = lastRssi,
                            source = BatchSource.INVENTORY,
                        )
                    }
                }
                val extras = buildExtrasFromSweep(seenEntries, inputItems)
                undoStack.clear()
                mutableState.update { state ->
                    state.copy(
                        sweepRunning = false,
                        extras = extras,
                        summary = summaryFor(state.inputItems, state.sessionMap, extras),
                        canUndo = false,
                    )
                }
            }
        sweepJob = job
        job.invokeOnCompletion {
            sweepJob = null
            mutableState.update { it.copy(sweepRunning = false) }
        }
    }

    fun stopSweep() {
        sweepJob?.cancel()
        sweepJob = null
        mutableState.update { it.copy(sweepRunning = false) }
    }

    fun scanOnce() {
        val state = uiState.value
        if (scanJob != null || state.sweepRunning) {
            return
        }
        mutableState.update { it.copy(isScanning = true, lastErrorMessage = null, lastInfoMessage = null) }
        val job =
            viewModelScope.launch {
                val scanResult = runCatching { uhfUseCase.scanOnce(SCAN_TIMEOUT_MS) }
                if (scanResult.isFailure) {
                    mutableState.update {
                        it.copy(
                            isScanning = false,
                            lastErrorMessage = mapUhfError(scanResult.exceptionOrNull()),
                        )
                    }
                    return@launch
                }
                val reading = scanResult.getOrNull()
                if (reading == null) {
                    mutableState.update {
                        it.copy(isScanning = false, lastErrorMessage = "No tag found.")
                    }
                    return@launch
                }
                val normalized = runCatching { EpcNormalizer.normalize(reading.epcHex) }.getOrNull()
                if (normalized == null) {
                    mutableState.update {
                        it.copy(isScanning = false, lastErrorMessage = "Invalid tag read.")
                    }
                    return@launch
                }
                handleManualScan(normalized, reading.rssi, reading.timestampMs)
                mutableState.update { it.copy(isScanning = false) }
            }
        scanJob = job
        job.invokeOnCompletion { scanJob = null }
    }

    fun markMissingCurrent() {
        val current = uiState.value.currentRowEpc ?: return
        val previous = uiState.value.sessionMap[current] ?: return
        pushUndo(UpdateEntry(current, previous))
        viewModelScope.launch {
            repository.updateSession(
                epcNormalized = current,
                status = BatchStatus.MISSING,
                updatedAt = clock(),
                lastSeenAt = previous.lastSeenAt,
                lastRssi = previous.lastRssi,
                source = BatchSource.MANUAL,
            )
        }
        advanceAfterManual(current)
    }

    fun undoLast() {
        if (undoStack.isEmpty()) {
            return
        }
        val action = undoStack.removeLast()
        when (action) {
            is UpdateEntry -> {
                val previous = action.previous
                viewModelScope.launch {
                    repository.updateSession(
                        epcNormalized = action.epcNormalized,
                        status = previous.status,
                        updatedAt = previous.updatedAt ?: clock(),
                        lastSeenAt = previous.lastSeenAt,
                        lastRssi = previous.lastRssi,
                        source = previous.source,
                    )
                }
            }
            is UpdateExtra -> {
                mutableState.update { state ->
                    val updatedExtras = state.extras.toMutableMap()
                    if (action.previous == null) {
                        updatedExtras.remove(action.epcNormalized)
                    } else {
                        updatedExtras[action.epcNormalized] = action.previous
                    }
                    state.copy(
                        extras = updatedExtras,
                        summary = summaryFor(state.inputItems, state.sessionMap, updatedExtras),
                    )
                }
            }
        }
        mutableState.update { it.copy(canUndo = undoStack.isNotEmpty()) }
    }

    fun onHardwareTrigger() {
        val state = uiState.value
        if (state.mode == BatchMode.SWEEP) {
            if (state.sweepRunning) {
                stopSweep()
            } else {
                startSweep()
            }
        } else {
            scanOnce()
        }
    }

    private fun handleManualScan(
        epcNormalized: String,
        rssi: Int?,
        timestampMs: Long,
    ) {
        val state = uiState.value
        val session = state.sessionMap[epcNormalized]
        if (session != null) {
            pushUndo(UpdateEntry(epcNormalized, session))
            viewModelScope.launch {
                repository.updateSession(
                    epcNormalized = epcNormalized,
                    status = BatchStatus.PRESENT,
                    updatedAt = clock(),
                    lastSeenAt = timestampMs,
                    lastRssi = rssi ?: session.lastRssi,
                    source = BatchSource.SCAN,
                )
            }
            mutableState.update {
                it.copy(
                    lastScanEpc = epcNormalized,
                    lastScanRssi = rssi,
                    lastInfoMessage = null,
                )
            }
            advanceAfterManual(epcNormalized)
        } else {
            val previous = state.extras[epcNormalized]
            pushUndo(UpdateExtra(epcNormalized, previous))
            val updatedExtras =
                state.extras.toMutableMap().apply {
                    put(
                        epcNormalized,
                        BatchExtraEntry(
                            epcNormalized = epcNormalized,
                            lastSeenAt = timestampMs,
                            lastRssi = rssi,
                            source = BatchSource.SCAN,
                        ),
                    )
                }
            mutableState.update {
                it.copy(
                    lastScanEpc = epcNormalized,
                    lastScanRssi = rssi,
                    lastInfoMessage = "Extra scanned: $epcNormalized",
                    extras = updatedExtras,
                    summary = summaryFor(it.inputItems, it.sessionMap, updatedExtras),
                )
            }
        }
    }

    private fun advanceAfterManual(currentEpc: String) {
        val next = findNextTarget(uiState.value.inputItems, uiState.value.sessionMap, currentEpc)
        if (next != null) {
            selectItem(next)
        }
    }

    private fun buildExtrasFromSweep(
        entries: Map<String, BatchUhfUseCase.SweepEntry>,
        inputItems: List<BatchInputItem>,
    ): Map<String, BatchExtraEntry> {
        val inputSet = inputItems.map { it.epcNormalized }.toSet()
        val extras = mutableMapOf<String, BatchExtraEntry>()
        entries.forEach { (epc, entry) ->
            if (!inputSet.contains(epc)) {
                extras[epc] =
                    BatchExtraEntry(
                        epcNormalized = epc,
                        lastSeenAt = entry.lastSeenAt,
                        lastRssi = entry.bestRssi,
                        source = BatchSource.INVENTORY,
                    )
            }
        }
        return extras
    }

    private fun buildExportRows(
        items: List<com.alexbomber12.memtag.domain.batch.BatchItem>,
        includeExtras: Boolean,
    ): List<BatchExportRow> {
        val rows =
            items.map { item ->
                BatchExportRow(
                    epc = item.input.epcNormalized,
                    name = item.input.name,
                    status = item.session.status,
                    lastSeenAt = item.session.lastSeenAt,
                    lastRssi = item.session.lastRssi,
                    source = item.session.source,
                    note = item.input.note,
                )
            }.toMutableList()
        if (includeExtras) {
            uiState.value.extras.values.forEach { extra ->
                rows.add(
                    BatchExportRow(
                        epc = extra.epcNormalized,
                        name = null,
                        status = BatchStatus.EXTRA,
                        lastSeenAt = extra.lastSeenAt,
                        lastRssi = extra.lastRssi,
                        source = extra.source,
                        note = null,
                    ),
                )
            }
        }
        return rows
    }

    private fun summaryFor(
        inputItems: List<BatchInputItem>,
        sessionMap: Map<String, BatchSessionEntry>,
        extras: Map<String, BatchExtraEntry>,
    ): BatchSummary {
        var present = 0
        var missing = 0
        var unknown = 0
        inputItems.forEach { item ->
            val status = sessionMap[item.epcNormalized]?.status ?: BatchStatus.UNKNOWN
            when (status) {
                BatchStatus.PRESENT -> present += 1
                BatchStatus.MISSING -> missing += 1
                else -> unknown += 1
            }
        }
        return BatchSummary(
            total = inputItems.size,
            present = present,
            missing = missing,
            unknown = unknown,
            extra = extras.size,
        )
    }

    private fun resolveCurrentEpc(
        inputItems: List<BatchInputItem>,
        sessionMap: Map<String, BatchSessionEntry>,
        currentEpc: String?,
    ): String? {
        if (inputItems.isEmpty()) {
            return null
        }
        val existing = inputItems.firstOrNull { it.epcNormalized == currentEpc }
        if (existing != null) {
            return existing.epcNormalized
        }
        val unknown = inputItems.firstOrNull { sessionMap[it.epcNormalized]?.status == BatchStatus.UNKNOWN }
        if (unknown != null) {
            return unknown.epcNormalized
        }
        val missing = inputItems.firstOrNull { sessionMap[it.epcNormalized]?.status == BatchStatus.MISSING }
        return missing?.epcNormalized ?: inputItems.first().epcNormalized
    }

    private fun findNextTarget(
        inputItems: List<BatchInputItem>,
        sessionMap: Map<String, BatchSessionEntry>,
        currentEpc: String,
    ): String? {
        if (inputItems.isEmpty()) {
            return null
        }
        val statuses = setOf(BatchStatus.UNKNOWN, BatchStatus.MISSING)
        val startIndex = inputItems.indexOfFirst { it.epcNormalized == currentEpc }
        val forward =
            if (startIndex >= 0) {
                inputItems.drop(startIndex + 1).firstOrNull { item ->
                    statuses.contains(sessionMap[item.epcNormalized]?.status)
                }
            } else {
                null
            }
        return forward?.epcNormalized
            ?: inputItems.firstOrNull { item -> statuses.contains(sessionMap[item.epcNormalized]?.status) }?.epcNormalized
    }

    private fun mapUhfError(error: Throwable?): String {
        val uhfError = (error as? UhfException)?.error
        return when (uhfError) {
            UhfError.NotInitialized -> "UHF not initialized."
            UhfError.HardwareUnavailable -> "UHF hardware unavailable."
            UhfError.Timeout -> "UHF operation timed out."
            UhfError.OperationInProgress -> "Another UHF operation is already running."
            is UhfError.VendorError -> uhfError.message
            null -> error?.message ?: "Unknown UHF error."
        }
    }

    private fun pushUndo(action: BatchUndoAction) {
        undoStack.add(action)
        mutableState.update { it.copy(canUndo = true) }
    }

    private sealed class BatchUndoAction

    private data class UpdateEntry(
        val epcNormalized: String,
        val previous: BatchSessionEntry,
    ) : BatchUndoAction()

    private data class UpdateExtra(
        val epcNormalized: String,
        val previous: BatchExtraEntry?,
    ) : BatchUndoAction()
}

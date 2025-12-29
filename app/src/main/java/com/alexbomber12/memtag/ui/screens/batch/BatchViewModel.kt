package com.alexbomber12.memtag.ui.screens.batch

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexbomber12.memtag.app.SessionFlagsStore
import com.alexbomber12.memtag.data.batch.BatchCsvExporter
import com.alexbomber12.memtag.data.batch.BatchCsvParser
import com.alexbomber12.memtag.data.batch.BatchRepository
import com.alexbomber12.memtag.domain.batch.BatchExportRow
import com.alexbomber12.memtag.domain.batch.BatchExtraEntry
import com.alexbomber12.memtag.domain.batch.BatchInputItem
import com.alexbomber12.memtag.domain.batch.BatchItem
import com.alexbomber12.memtag.domain.batch.BatchSessionEntry
import com.alexbomber12.memtag.domain.batch.BatchSource
import com.alexbomber12.memtag.domain.batch.BatchStatus
import com.alexbomber12.memtag.domain.batch.BatchUhfUseCase
import com.alexbomber12.memtag.integrations.uhf.TagReading
import com.alexbomber12.memtag.integrations.uhf.UhfError
import com.alexbomber12.memtag.integrations.uhf.UhfException
import com.alexbomber12.memtag.util.epc.EpcNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.collections.ArrayDeque

enum class BatchMode {
    INVENTORY_SWEEP,
    MANUAL_SCAN,
}

private const val SCAN_TIMEOUT_MS = 1_500L
private const val TRIGGER_DEBOUNCE_MS = 300L
private const val MAX_INVALID_ROWS = 50

data class BatchSummary(
    val total: Int = 0,
    val found: Int = 0,
    val notFound: Int = 0,
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
    val mode: BatchMode = BatchMode.INVENTORY_SWEEP,
    val sweepRunning: Boolean = false,
    val currentRowEpc: String? = null,
    val lastScanEpc: String? = null,
    val lastScanRssi: Int? = null,
    val isImporting: Boolean = false,
    val isExporting: Boolean = false,
    val isScanning: Boolean = false,
    val manualSessionActive: Boolean = false,
    val manualScanCount: Int = 0,
    val manualFoundCount: Int = 0,
    val manualUnknownCount: Int = 0,
    val lastScanMatched: Boolean? = null,
    val lastImportReport: BatchImportReport? = null,
    val lastErrorMessage: String? = null,
    val lastInfoMessage: String? = null,
    val canUndo: Boolean = false,
)

class BatchViewModel(
    private val repository: BatchRepository,
    private val uhfUseCase: BatchUhfUseCase,
    private val sessionFlagsStore: SessionFlagsStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(BatchUiState())
    val uiState: StateFlow<BatchUiState> = mutableState

    private var importJob: Job? = null
    private var exportJob: Job? = null
    private var sweepJob: Job? = null
    private var scanJob: Job? = null
    private var scanInProgress: Boolean = false
    private val undoStack = ArrayDeque<BatchUndoAction>()
    private val sweepEntries = mutableMapOf<String, BatchUhfUseCase.SweepEntry>()
    private val sweepExtras = mutableMapOf<String, BatchUhfUseCase.SweepEntry>()
    private val sweepMatched = mutableSetOf<String>()
    private var sweepInputSet: Set<String> = emptySet()
    private var sweepTotalCount: Int = 0
    private var lastTriggerAtMs: Long = 0L

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
                        val nextSummary =
                            if (state.sweepRunning) {
                                state.summary
                            } else {
                                summaryFor(inputItems, sessionMap, state.extras)
                            }
                        state.copy(
                            inputItems = inputItems,
                            sessionMap = sessionMap,
                            summary = nextSummary,
                            currentRowEpc = resolvedCurrent,
                            manualFoundCount = nextSummary.found,
                            manualUnknownCount = nextSummary.unknown,
                        )
                    }
                }
        }
        viewModelScope.launch {
            uiState.collect { state ->
                sessionFlagsStore.setBatchRunning(
                    state.sweepRunning || state.isScanning || state.manualSessionActive,
                )
            }
        }
    }

    fun setMode(mode: BatchMode) {
        val state = uiState.value
        if (state.sweepRunning || state.manualSessionActive) {
            return
        }
        mutableState.update { it.copy(mode = mode, lastErrorMessage = null, lastInfoMessage = null) }
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
                    lastScanMatched = null,
                    manualSessionActive = false,
                    manualScanCount = 0,
                    manualFoundCount = 0,
                    manualUnknownCount = 0,
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
                        lastScanMatched = if (report.isSuccess) null else state.lastScanMatched,
                        manualSessionActive = if (report.isSuccess) false else state.manualSessionActive,
                        manualScanCount = if (report.isSuccess) 0 else state.manualScanCount,
                        manualFoundCount = if (report.isSuccess) 0 else state.manualFoundCount,
                        manualUnknownCount = if (report.isSuccess) 0 else state.manualUnknownCount,
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
        if (uiState.value.manualSessionActive) {
            mutableState.update { it.copy(lastErrorMessage = "Finish the session before exporting.") }
            return
        }
        mutableState.update { it.copy(isExporting = true, lastErrorMessage = null) }
        val job =
            viewModelScope.launch {
                val result =
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val items = repository.getAll()
                            val rows = buildExportRows(items)
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
        if (state.sweepRunning) {
            return
        }
        scanJob?.cancel()
        resetSweepTracking(state.inputItems)
        mutableState.update {
            it.copy(
                sweepRunning = true,
                lastErrorMessage = null,
                lastInfoMessage = null,
                extras = emptyMap(),
                summary =
                    BatchSummary(
                        total = sweepTotalCount,
                        found = 0,
                        notFound = 0,
                        unknown = sweepTotalCount,
                        extra = 0,
                    ),
            )
        }
        val job =
            viewModelScope.launch {
                val result = runCatching { uhfUseCase.collectSweep(::handleSweepReading) }
                val error = result.exceptionOrNull()
                if (error != null && error !is CancellationException) {
                    mutableState.update {
                        it.copy(
                            sweepRunning = false,
                            lastErrorMessage = mapUhfError(error),
                        )
                    }
                }
            }
        sweepJob = job
        job.invokeOnCompletion { cause ->
            sweepJob = null
            if (cause != null && cause !is CancellationException) {
                mutableState.update { it.copy(sweepRunning = false) }
            }
        }
    }

    fun stopSweep() {
        if (!uiState.value.sweepRunning) {
            return
        }
        val job = sweepJob
        viewModelScope.launch {
            job?.cancelAndJoin()
            finalizeSweep()
        }
    }

    fun scanOnce() {
        val state = uiState.value
        if (state.mode != BatchMode.MANUAL_SCAN) {
            return
        }
        if (!state.manualSessionActive) {
            mutableState.update { it.copy(lastInfoMessage = "Start session first.", lastErrorMessage = null) }
            return
        }
        if (scanInProgress || state.sweepRunning) {
            return
        }
        scanInProgress = true
        mutableState.update {
            it.copy(
                isScanning = true,
                manualScanCount = it.manualScanCount + 1,
                lastErrorMessage = null,
                lastInfoMessage = null,
            )
        }
        val job =
            viewModelScope.launch {
                try {
                    val scanResult = runCatching { uhfUseCase.scanOnce(SCAN_TIMEOUT_MS) }
                    if (scanResult.isFailure) {
                        mutableState.update {
                            it.copy(
                                lastErrorMessage = mapUhfError(scanResult.exceptionOrNull()),
                            )
                        }
                        return@launch
                    }
                    val reading = scanResult.getOrNull()
                    if (reading == null) {
                        mutableState.update {
                            it.copy(lastErrorMessage = "No tag found.")
                        }
                        return@launch
                    }
                    val normalized = runCatching { EpcNormalizer.normalize(reading.epcHex) }.getOrNull()
                    if (normalized == null) {
                        mutableState.update {
                            it.copy(lastErrorMessage = "Invalid tag read.")
                        }
                        return@launch
                    }
                    handleManualScan(normalized, reading.rssi, reading.timestampMs)
                } finally {
                    scanInProgress = false
                    mutableState.update { it.copy(isScanning = false) }
                }
            }
        scanJob = job
        job.invokeOnCompletion { scanJob = null }
    }

    fun toggleManualSession() {
        val state = uiState.value
        if (state.sweepRunning || state.isScanning) {
            return
        }
        if (state.manualSessionActive) {
            finishManualSession()
        } else {
            startManualSession()
        }
    }

    private fun startManualSession() {
        val summary = uiState.value.summary
        mutableState.update {
            it.copy(
                manualSessionActive = true,
                manualScanCount = 0,
                manualFoundCount = summary.found,
                manualUnknownCount = summary.unknown,
                lastScanEpc = null,
                lastScanRssi = null,
                lastScanMatched = null,
                lastErrorMessage = null,
                lastInfoMessage = null,
            )
        }
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
                        updatedAt = previous.updatedAt ?: 0L,
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
        val now = clock()
        if (now - lastTriggerAtMs < TRIGGER_DEBOUNCE_MS) {
            return
        }
        lastTriggerAtMs = now
        val state = uiState.value
        if (state.mode == BatchMode.INVENTORY_SWEEP) {
            if (state.sweepRunning) {
                stopSweep()
            } else {
                startSweep()
            }
        } else {
            scanOnce()
        }
    }

    private fun resetSweepTracking(inputItems: List<BatchInputItem>) {
        sweepEntries.clear()
        sweepExtras.clear()
        sweepMatched.clear()
        sweepInputSet = inputItems.map { it.epcNormalized }.toSet()
        sweepTotalCount = inputItems.size
    }

    private fun handleSweepReading(reading: TagReading) {
        val normalized = runCatching { EpcNormalizer.normalize(reading.epcHex) }.getOrNull() ?: return
        val previous = sweepEntries[normalized]
        val bestRssi = bestRssi(previous?.bestRssi, reading.rssi)
        val entry =
            BatchUhfUseCase.SweepEntry(
                epcNormalized = normalized,
                lastSeenAt = reading.timestampMs,
                bestRssi = bestRssi,
            )
        sweepEntries[normalized] = entry
        val isInput = sweepInputSet.contains(normalized)
        if (!isInput) {
            sweepExtras[normalized] = entry
        }
        if (previous == null) {
            if (isInput) {
                sweepMatched.add(normalized)
            }
            updateSweepSummary()
        }
    }

    private fun updateSweepSummary() {
        if (!uiState.value.sweepRunning) {
            return
        }
        val found = sweepMatched.size
        val unknown = (sweepTotalCount - found).coerceAtLeast(0)
        mutableState.update {
            it.copy(
                summary =
                    BatchSummary(
                        total = sweepTotalCount,
                        found = found,
                        notFound = 0,
                        unknown = unknown,
                        extra = sweepExtras.size,
                    ),
            )
        }
    }

    private suspend fun finalizeSweep() {
        val entriesSnapshot = sweepEntries.toMap()
        val inputItems = uiState.value.inputItems
        val sessionMap = uiState.value.sessionMap
        val now = clock()
        withContext(Dispatchers.IO) {
            inputItems.forEach { item ->
                val previous = sessionMap[item.epcNormalized]
                val seen = entriesSnapshot[item.epcNormalized]
                val nextStatus = if (seen != null) BatchStatus.FOUND else BatchStatus.NOT_FOUND
                val updatedAt = if (seen != null) now else 0L
                val lastSeenAt = seen?.lastSeenAt ?: previous?.lastSeenAt
                val lastRssi = seen?.bestRssi ?: previous?.lastRssi
                repository.updateSession(
                    epcNormalized = item.epcNormalized,
                    status = nextStatus,
                    updatedAt = updatedAt,
                    lastSeenAt = lastSeenAt,
                    lastRssi = lastRssi,
                    source = BatchSource.INVENTORY,
                )
            }
        }
        val extras = buildExtrasFromSweep(entriesSnapshot, inputItems)
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

    private fun bestRssi(
        current: Int?,
        candidate: Int?,
    ): Int? {
        return when {
            current == null -> candidate
            candidate == null -> current
            else -> maxOf(current, candidate)
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
            val updatedAt =
                if (session.status == BatchStatus.FOUND && (session.updatedAt ?: 0L) > 0L) {
                    session.updatedAt ?: 0L
                } else {
                    clock()
                }
            viewModelScope.launch {
                repository.updateSession(
                    epcNormalized = epcNormalized,
                    status = BatchStatus.FOUND,
                    updatedAt = updatedAt,
                    lastSeenAt = timestampMs,
                    lastRssi = rssi ?: session.lastRssi,
                    source = BatchSource.SCAN,
                )
            }
            mutableState.update {
                it.copy(
                    lastScanEpc = epcNormalized,
                    lastScanRssi = rssi,
                    lastScanMatched = true,
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
                    lastScanMatched = false,
                    lastInfoMessage = "Extra scanned: $epcNormalized",
                    extras = updatedExtras,
                    summary = summaryFor(it.inputItems, it.sessionMap, updatedExtras),
                )
            }
        }
    }

    private fun finishManualSession() {
        if (uiState.value.sweepRunning) {
            return
        }
        mutableState.update { it.copy(manualSessionActive = false, lastInfoMessage = null, lastErrorMessage = null) }
        viewModelScope.launch {
            val inputItems = uiState.value.inputItems
            val sessionMap = uiState.value.sessionMap
            val now = clock()
            withContext(Dispatchers.IO) {
                inputItems.forEach { item ->
                    val session = sessionMap[item.epcNormalized] ?: return@forEach
                    if (session.status == BatchStatus.UNKNOWN) {
                        repository.updateSession(
                            epcNormalized = item.epcNormalized,
                            status = BatchStatus.NOT_FOUND,
                            updatedAt = now,
                            lastSeenAt = session.lastSeenAt,
                            lastRssi = session.lastRssi,
                            source = BatchSource.MANUAL,
                        )
                    }
                }
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

    private fun buildExportRows(items: List<BatchItem>): List<BatchExportRow> {
        return items.map { item ->
            BatchExportRow(
                epc = item.input.epcNormalized,
                name = item.input.name,
                status = item.session.status,
                updatedAt = item.session.updatedAt,
            )
        }
    }

    private fun summaryFor(
        inputItems: List<BatchInputItem>,
        sessionMap: Map<String, BatchSessionEntry>,
        extras: Map<String, BatchExtraEntry>,
    ): BatchSummary {
        var found = 0
        var notFound = 0
        var unknown = 0
        inputItems.forEach { item ->
            val status = sessionMap[item.epcNormalized]?.status ?: BatchStatus.UNKNOWN
            when (status) {
                BatchStatus.FOUND -> found += 1
                BatchStatus.NOT_FOUND -> notFound += 1
                else -> unknown += 1
            }
        }
        return BatchSummary(
            total = inputItems.size,
            found = found,
            notFound = notFound,
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
        val notFound = inputItems.firstOrNull { sessionMap[it.epcNormalized]?.status == BatchStatus.NOT_FOUND }
        return notFound?.epcNormalized ?: inputItems.first().epcNormalized
    }

    private fun findNextTarget(
        inputItems: List<BatchInputItem>,
        sessionMap: Map<String, BatchSessionEntry>,
        currentEpc: String,
    ): String? {
        if (inputItems.isEmpty()) {
            return null
        }
        val statuses = setOf(BatchStatus.UNKNOWN, BatchStatus.NOT_FOUND)
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

    override fun onCleared() {
        sessionFlagsStore.setBatchRunning(false)
        super.onCleared()
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

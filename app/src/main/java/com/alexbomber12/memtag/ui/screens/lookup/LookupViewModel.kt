package com.alexbomber12.memtag.ui.screens.lookup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexbomber12.memtag.data.repository.MementoRepository
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.alexbomber12.memtag.domain.InventoryItem
import com.alexbomber12.memtag.domain.SyncState
import com.alexbomber12.memtag.integrations.scan2d.Scan2dError
import com.alexbomber12.memtag.integrations.scan2d.Scan2dException
import com.alexbomber12.memtag.integrations.scan2d.Scan2dLogger
import com.alexbomber12.memtag.integrations.scan2d.Scan2dScanner
import com.alexbomber12.memtag.integrations.scan2d.asException
import com.alexbomber12.memtag.integrations.uhf.UhfError
import com.alexbomber12.memtag.integrations.uhf.UhfException
import com.alexbomber12.memtag.integrations.uhf.UhfLogger
import com.alexbomber12.memtag.integrations.uhf.UhfReader
import com.alexbomber12.memtag.integrations.uhf.asException
import com.alexbomber12.memtag.integrations.uhf.ensureConfiguredWithRecovery
import com.alexbomber12.memtag.integrations.uhf.toErrorMessage
import com.alexbomber12.memtag.util.epc.EpcNormalizer
import com.alexbomber12.memtag.util.epc.EpcValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class ScanQrStatus {
    data object Idle : ScanQrStatus()

    data object Scanning : ScanQrStatus()

    data class Error(
        val message: String,
    ) : ScanQrStatus()
}

sealed class ScanUhfStatus {
    data object Idle : ScanUhfStatus()

    data object Scanning : ScanUhfStatus()

    data class Error(
        val message: String,
    ) : ScanUhfStatus()
}

data class LookupUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<InventoryItem> = emptyList(),
    val selectedEpc: String? = null,
    val searchError: String? = null,
    val isQrBusy: Boolean = false,
    val isUhfBusy: Boolean = false,
    val scanStatus: ScanQrStatus = ScanQrStatus.Idle,
    val uhfScanStatus: ScanUhfStatus = ScanUhfStatus.Idle,
    val lastSyncState: SyncState? = null,
)

// Lookup v2: search-first; scan fills query; selection persists to settings.
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LookupViewModel(
    private val settingsStore: SettingsStore,
    private val repository: MementoRepository,
    private val scan2dScanner: Scan2dScanner,
    private val uhfReader: UhfReader,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LookupUiState())
    val uiState: StateFlow<LookupUiState> = mutableState

    private val queryFlow = MutableStateFlow("")

    private var scanJob: Job? = null
    private var scanUhfJob: Job? = null

    init {
        viewModelScope.launch {
            settingsStore.settingsFlow
                .map { it.mementoLibraryId }
                .distinctUntilChanged()
                .flatMapLatest { libraryId ->
                    if (libraryId.isBlank()) {
                        flowOf(null)
                    } else {
                        repository.observeSyncState(libraryId)
                    }
                }
                .collect { state ->
                    mutableState.update { it.copy(lastSyncState = state) }
                }
        }
        viewModelScope.launch {
            queryFlow
                .debounce(SEARCH_DEBOUNCE_MS)
                .map { it.trim() }
                .distinctUntilChanged()
                .collectLatest { trimmed ->
                    handleSearch(trimmed)
                }
        }
    }

    fun updateQuery(value: String) {
        mutableState.update { it.copy(query = value) }
        queryFlow.value = value
    }

    fun selectItem(item: InventoryItem) {
        val epc = item.epcNormalized
        mutableState.update { it.copy(selectedEpc = epc) }
        persistSelection(item)
    }

    fun scanQr() {
        if (scanJob != null || uiState.value.isUhfBusy) {
            Scan2dLogger.i("Scan QR ignored: already scanning (screen=$SCAN_SOURCE)")
            return
        }
        mutableState.update {
            it.copy(
                isQrBusy = true,
                isUhfBusy = false,
                scanStatus = ScanQrStatus.Scanning,
                uhfScanStatus = ScanUhfStatus.Idle,
            )
        }
        scanJob =
            viewModelScope.launch {
                try {
                    Scan2dLogger.i("Scan QR clicked: calling scanOnce (screen=$SCAN_SOURCE)")
                    val result =
                        runCatching { scan2dScanner.scanOnce(source = SCAN_SOURCE) }
                            .getOrElse { error ->
                                val mapped =
                                    if (error is CancellationException) {
                                        Scan2dError.Cancelled.asException(message = "QR scan cancelled.", cause = error)
                                    } else {
                                        error
                                    }
                                Result.failure(mapped)
                            }
                    Scan2dLogger.i("Scan QR finished: result=${scanResultLabel(result)} (screen=$SCAN_SOURCE)")
                    result
                        .onSuccess { epc ->
                            handleScanSuccess(epc, ScanSource.QR)
                        }
                        .onFailure { error ->
                            logScanFailure(error)
                            mutableState.update {
                                it.copy(scanStatus = ScanQrStatus.Error(scanErrorMessage(error)))
                            }
                        }
                } finally {
                    mutableState.update { state ->
                        val nextStatus =
                            if (state.scanStatus is ScanQrStatus.Scanning) {
                                ScanQrStatus.Idle
                            } else {
                                state.scanStatus
                            }
                        state.copy(isQrBusy = false, scanStatus = nextStatus)
                    }
                    scanJob = null
                }
            }
    }

    fun scanUhf() {
        if (scanUhfJob != null || uiState.value.isQrBusy) {
            return
        }
        mutableState.update {
            it.copy(
                isUhfBusy = true,
                isQrBusy = false,
                uhfScanStatus = ScanUhfStatus.Scanning,
                scanStatus = ScanQrStatus.Idle,
            )
        }
        val job =
            viewModelScope.launch {
                try {
                    val startMs = System.currentTimeMillis()
                    UhfLogger.i("ScanRFID start (screen=lookup source=button usedMethod=single)")
                    uhfReader.stopInventory()
                    val applyResult = uhfReader.ensureConfiguredWithRecovery("lookup-scan")
                    if (applyResult.isFailure) {
                        updateUhfError(applyResult.exceptionOrNull())
                        UhfLogger.i(
                            "ScanRFID end (screen=lookup result=config_error durationMs=${System.currentTimeMillis() - startMs})",
                        )
                        return@launch
                    }
                    val applied = applyResult.getOrNull()
                    if (applied != null && !applied.success) {
                        updateUhfError(UhfError.VendorError(applied.toErrorMessage()).asException())
                        UhfLogger.i(
                            "ScanRFID end (screen=lookup result=config_failed durationMs=${System.currentTimeMillis() - startMs})",
                        )
                        return@launch
                    }
                    val readResult = uhfReader.readSingle(UHF_READ_TIMEOUT_MS)
                    if (readResult.isFailure) {
                        updateUhfError(readResult.exceptionOrNull())
                        UhfLogger.i(
                            "ScanRFID end (screen=lookup result=read_failed durationMs=${System.currentTimeMillis() - startMs})",
                        )
                        return@launch
                    }
                    handleScanSuccess(readResult.getOrNull().orEmpty(), ScanSource.RFID)
                    UhfLogger.i("ScanRFID end (screen=lookup result=ok durationMs=${System.currentTimeMillis() - startMs})")
                } finally {
                    mutableState.update { state ->
                        val nextStatus =
                            if (state.uhfScanStatus is ScanUhfStatus.Scanning) {
                                ScanUhfStatus.Idle
                            } else {
                                state.uhfScanStatus
                            }
                        state.copy(isUhfBusy = false, uhfScanStatus = nextStatus)
                    }
                    scanUhfJob = null
                }
            }
        scanUhfJob = job
    }

    override fun onCleared() {
        cancelUhfScan()
        super.onCleared()
    }

    fun cancelUhfScan() {
        scanUhfJob?.cancel()
        scanUhfJob = null
        mutableState.update { it.copy(isUhfBusy = false, uhfScanStatus = ScanUhfStatus.Idle) }
    }

    private suspend fun handleSearch(trimmed: String) {
        if (trimmed.isBlank()) {
            mutableState.update {
                it.copy(
                    isSearching = false,
                    results = emptyList(),
                    searchError = null,
                    selectedEpc = null,
                )
            }
            return
        }
        mutableState.update { it.copy(isSearching = true, searchError = null) }
        val searchResult =
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.searchInventory(trimmed, limit = SEARCH_LIMIT)
                }
            }
        searchResult
            .onSuccess { items ->
                val autoSelected = resolveAutoSelection(trimmed, items)
                val previousSelected = mutableState.value.selectedEpc
                val resolvedSelection =
                    autoSelected
                        ?: previousSelected?.takeIf { selected -> items.any { it.epcNormalized == selected } }
                mutableState.update { state ->
                    state.copy(
                        isSearching = false,
                        results = items,
                        selectedEpc = resolvedSelection,
                    )
                }
                if (autoSelected != null && autoSelected != previousSelected) {
                    val selectedItem = items.firstOrNull { it.epcNormalized == autoSelected }
                    if (selectedItem != null) {
                        persistSelection(selectedItem)
                    }
                }
            }
            .onFailure { error ->
                mutableState.update {
                    it.copy(
                        isSearching = false,
                        results = emptyList(),
                        searchError = error.message ?: "Search failed.",
                        selectedEpc = null,
                    )
                }
            }
    }

    private fun resolveAutoSelection(
        query: String,
        items: List<InventoryItem>,
    ): String? {
        val normalized =
            if (EpcValidator.isValidEpcHex(query)) {
                runCatching { EpcNormalizer.normalize(query) }.getOrNull()
            } else {
                null
            }
        if (normalized.isNullOrBlank()) {
            return null
        }
        return items.firstOrNull { it.epcNormalized == normalized }?.epcNormalized
    }

    private fun persistSelection(item: InventoryItem) {
        viewModelScope.launch {
            val timestamp = System.currentTimeMillis()
            settingsStore.update { settings ->
                settings.copy(
                    selectedLookupEpc = item.epcNormalized,
                    selectedLookupName = item.name?.trim().orEmpty(),
                    selectedLookupStatus = item.status?.trim().orEmpty(),
                    selectedLookupLocation = item.locationPath?.trim().orEmpty(),
                    selectedLookupAt = timestamp,
                    lastScannedEpc = item.epcNormalized,
                    lastScannedEpcAt = timestamp,
                )
            }
        }
    }

    private fun persistLastScannedEpc(epc: String) {
        viewModelScope.launch {
            val timestamp = System.currentTimeMillis()
            settingsStore.update { settings ->
                settings.copy(
                    lastScannedEpc = epc,
                    lastScannedEpcAt = timestamp,
                )
            }
        }
    }

    private fun scanErrorMessage(error: Throwable?): String {
        return when (val scanError = (error as? Scan2dException)?.error) {
            Scan2dError.Timeout -> "QR scan timed out."
            Scan2dError.Cancelled -> "QR scan cancelled."
            Scan2dError.OperationInProgress -> "Scanner is busy."
            Scan2dError.HardwareUnavailable -> "QR scanner unavailable."
            is Scan2dError.InvalidPayload -> scanError.message
            is Scan2dError.VendorError -> scanError.message
            null -> error?.message ?: "Unknown QR scan error."
        }
    }

    private fun scanResultLabel(result: Result<String>): String {
        return result.fold(
            onSuccess = { "ok" },
            onFailure = { error -> "error:${error::class.java.simpleName}" },
        )
    }

    private fun logScanFailure(error: Throwable?) {
        val scanError = (error as? Scan2dException)?.error ?: return
        Scan2dLogger.w(
            "Scan2D error source=$SCAN_SOURCE message=${scanErrorMessage(scanError)}",
        )
    }

    private fun scanErrorMessage(error: Scan2dError): String {
        return when (error) {
            Scan2dError.Timeout -> "QR scan timed out."
            Scan2dError.Cancelled -> "QR scan cancelled."
            Scan2dError.OperationInProgress -> "Scanner is busy."
            Scan2dError.HardwareUnavailable -> "QR scanner unavailable."
            is Scan2dError.InvalidPayload -> error.message
            is Scan2dError.VendorError -> error.message
        }
    }

    private fun updateUhfError(error: Throwable?) {
        mutableState.update { it.copy(uhfScanStatus = ScanUhfStatus.Error(mapUhfError(error))) }
    }

    private fun mapUhfError(error: Throwable?): String {
        return when (val uhfError = (error as? UhfException)?.error) {
            UhfError.NotInitialized -> "UHF not initialized."
            UhfError.HardwareUnavailable -> "UHF hardware unavailable."
            UhfError.Timeout -> "UHF operation timed out."
            UhfError.OperationInProgress -> "Another UHF operation is already running."
            is UhfError.VendorError -> uhfError.message
            null -> error?.message ?: "Unknown UHF error."
        }
    }

    private fun handleScanSuccess(
        rawEpc: String,
        source: ScanSource,
    ) {
        val normalized =
            runCatching { EpcNormalizer.normalize(rawEpc) }.getOrElse { error ->
                val message = error.message ?: "Invalid EPC. Use 8-64 hex characters."
                when (source) {
                    ScanSource.QR ->
                        mutableState.update {
                            it.copy(scanStatus = ScanQrStatus.Error(message), uhfScanStatus = ScanUhfStatus.Idle)
                        }
                    ScanSource.RFID ->
                        mutableState.update {
                            it.copy(uhfScanStatus = ScanUhfStatus.Error(message), scanStatus = ScanQrStatus.Idle)
                        }
                }
                return
            }
        mutableState.update {
            it.copy(
                scanStatus = ScanQrStatus.Idle,
                uhfScanStatus = ScanUhfStatus.Idle,
                searchError = null,
            )
        }
        persistLastScannedEpc(normalized)
        updateQuery(normalized)
    }

    private companion object {
        const val SCAN_SOURCE = "lookup"
        const val UHF_READ_TIMEOUT_MS = 4_000L
        const val SEARCH_LIMIT = 20
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}

private enum class ScanSource {
    RFID,
    QR,
}

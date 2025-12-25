package com.alexbomber12.memtag.ui.screens.lookup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexbomber12.memtag.data.repository.MementoRepository
import com.alexbomber12.memtag.data.settings.AppSettings
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.alexbomber12.memtag.domain.InventoryItem
import com.alexbomber12.memtag.domain.LookupByEpcUseCase
import com.alexbomber12.memtag.domain.LookupResult
import com.alexbomber12.memtag.domain.SyncMementoLibraryUseCase
import com.alexbomber12.memtag.domain.SyncProgress
import com.alexbomber12.memtag.domain.SyncProgressEvent
import com.alexbomber12.memtag.domain.SyncResult
import com.alexbomber12.memtag.domain.SyncState
import com.alexbomber12.memtag.domain.SyncStatus
import com.alexbomber12.memtag.integrations.memento.MementoSettingsValidation
import com.alexbomber12.memtag.integrations.memento.MementoSettingsValidator
import com.alexbomber12.memtag.integrations.scan2d.Scan2dError
import com.alexbomber12.memtag.integrations.scan2d.Scan2dException
import com.alexbomber12.memtag.integrations.scan2d.Scan2dScanner
import com.alexbomber12.memtag.integrations.scan2d.asException
import com.alexbomber12.memtag.util.epc.EpcNormalizer
import com.alexbomber12.memtag.util.epc.EpcValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class LookupStatus {
    data object Idle : LookupStatus()

    data object Loading : LookupStatus()

    data class Found(
        val item: InventoryItem,
    ) : LookupStatus()

    data object NotFound : LookupStatus()

    data class Error(
        val message: String,
    ) : LookupStatus()
}

sealed class ScanQrStatus {
    data object Idle : ScanQrStatus()

    data object Scanning : ScanQrStatus()

    data class Error(
        val message: String,
    ) : ScanQrStatus()
}

sealed class SyncStatusState {
    data object Idle : SyncStatusState()

    data class Running(
        val progress: SyncProgress,
    ) : SyncStatusState()

    data class Completed(
        val result: SyncResult,
    ) : SyncStatusState()

    data class Error(
        val message: String,
    ) : SyncStatusState()
}

data class LookupUiState(
    val epcInput: String = "",
    val lookupStatus: LookupStatus = LookupStatus.Idle,
    val scanStatus: ScanQrStatus = ScanQrStatus.Idle,
    val syncStatus: SyncStatusState = SyncStatusState.Idle,
    val lastSyncState: SyncState? = null,
    val lastSyncResult: SyncResult? = null,
    val currentSettings: AppSettings = AppSettings(),
)

class LookupViewModel(
    private val settingsStore: SettingsStore,
    private val syncUseCase: SyncMementoLibraryUseCase,
    private val lookupUseCase: LookupByEpcUseCase,
    private val repository: MementoRepository,
    private val scan2dScanner: Scan2dScanner,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LookupUiState())
    val uiState: StateFlow<LookupUiState> = mutableState

    private var syncJob: Job? = null
    private var lookupJob: Job? = null
    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                mutableState.update { it.copy(currentSettings = settings) }
            }
        }
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
    }

    fun onEpcInputChange(value: String) {
        mutableState.update { it.copy(epcInput = value, scanStatus = ScanQrStatus.Idle) }
    }

    fun scanQr() {
        if (scanJob != null) {
            return
        }
        mutableState.update { it.copy(scanStatus = ScanQrStatus.Scanning) }
        val job =
            viewModelScope.launch {
                val result =
                    runCatching { withContext(Dispatchers.IO) { scan2dScanner.scanOnce() } }
                        .getOrElse { error ->
                            val mapped =
                                if (error is CancellationException) {
                                    Scan2dError.Cancelled.asException(message = "QR scan cancelled.", cause = error)
                                } else {
                                    error
                                }
                            Result.failure(mapped)
                        }
                result
                    .onSuccess { epc ->
                        mutableState.update { it.copy(epcInput = epc, scanStatus = ScanQrStatus.Idle) }
                        lookup()
                    }
                    .onFailure { error ->
                        mutableState.update {
                            it.copy(scanStatus = ScanQrStatus.Error(scanErrorMessage(error)))
                        }
                    }
            }
        scanJob = job
        job.invokeOnCompletion { scanJob = null }
    }

    fun lookup() {
        if (lookupJob != null) {
            return
        }
        val settings = uiState.value.currentSettings
        val validation =
            MementoSettingsValidator.validate(
                baseUrl = settings.mementoBaseUrl,
                token = settings.mementoToken,
                libraryId = settings.mementoLibraryId,
            )
        if (validation is MementoSettingsValidation.Error) {
            mutableState.update { it.copy(lookupStatus = LookupStatus.Error(validation.message)) }
            return
        }
        val epcRaw = uiState.value.epcInput
        if (!EpcValidator.isValidEpcHex(epcRaw)) {
            mutableState.update {
                it.copy(lookupStatus = LookupStatus.Error("Invalid EPC. Use 8-64 hex characters."))
            }
            return
        }
        val normalized =
            runCatching { EpcNormalizer.normalize(epcRaw) }.getOrElse {
                mutableState.update {
                    it.copy(lookupStatus = LookupStatus.Error("Invalid EPC. Use 8-64 hex characters."))
                }
                return
            }
        mutableState.update { it.copy(lookupStatus = LookupStatus.Loading) }
        viewModelScope.launch {
            settingsStore.update { settings -> settings.copy(lastLookupEpc = normalized) }
        }
        val job =
            viewModelScope.launch {
                val result = lookupUseCase.execute(normalized)
                mutableState.update {
                    when (result) {
                        is LookupResult.Found -> it.copy(lookupStatus = LookupStatus.Found(result.item))
                        is LookupResult.NotFound -> it.copy(lookupStatus = LookupStatus.NotFound)
                        is LookupResult.Error -> it.copy(lookupStatus = LookupStatus.Error(result.message))
                    }
                }
            }
        lookupJob = job
        job.invokeOnCompletion { lookupJob = null }
    }

    fun syncNow() {
        if (syncJob != null) {
            return
        }
        val settings = uiState.value.currentSettings
        val validation =
            MementoSettingsValidator.validate(
                baseUrl = settings.mementoBaseUrl,
                token = settings.mementoToken,
                libraryId = settings.mementoLibraryId,
            )
        if (validation is MementoSettingsValidation.Error) {
            mutableState.update { it.copy(syncStatus = SyncStatusState.Error(validation.message)) }
            return
        }
        val libraryId = (validation as MementoSettingsValidation.Valid).config.libraryId
        val job =
            viewModelScope.launch {
                syncUseCase.execute(libraryId).collect { event ->
                    when (event) {
                        is SyncProgressEvent.Progress -> {
                            mutableState.update {
                                it.copy(syncStatus = SyncStatusState.Running(event.progress))
                            }
                        }

                        is SyncProgressEvent.Finished -> {
                            mutableState.update {
                                val status =
                                    if (event.result.status == SyncStatus.ERROR) {
                                        SyncStatusState.Error(event.result.errorMessage ?: "Sync failed.")
                                    } else {
                                        SyncStatusState.Completed(event.result)
                                    }
                                it.copy(
                                    syncStatus = status,
                                    lastSyncResult = event.result,
                                )
                            }
                        }
                    }
                }
            }
        syncJob = job
        job.invokeOnCompletion { syncJob = null }
    }

    private fun scanErrorMessage(error: Throwable?): String {
        val scanError = (error as? Scan2dException)?.error
        return when (scanError) {
            Scan2dError.Timeout -> "QR scan timed out."
            Scan2dError.Cancelled -> "QR scan cancelled."
            Scan2dError.OperationInProgress -> "Scanner is busy."
            Scan2dError.HardwareUnavailable -> "QR scanner unavailable."
            is Scan2dError.InvalidPayload -> scanError.message
            is Scan2dError.VendorError -> scanError.message
            null -> error?.message ?: "Unknown QR scan error."
        }
    }
}

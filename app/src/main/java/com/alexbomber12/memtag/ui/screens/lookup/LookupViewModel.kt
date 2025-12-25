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
import com.alexbomber12.memtag.util.epc.EpcValidator
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
) : ViewModel() {
    private val mutableState = MutableStateFlow(LookupUiState())
    val uiState: StateFlow<LookupUiState> = mutableState

    private var syncJob: Job? = null
    private var lookupJob: Job? = null

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
        mutableState.update { it.copy(epcInput = value) }
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
                it.copy(lookupStatus = LookupStatus.Error("Invalid EPC. Use hex characters only."))
            }
            return
        }
        mutableState.update { it.copy(lookupStatus = LookupStatus.Loading) }
        val job =
            viewModelScope.launch {
                val result = lookupUseCase.execute(epcRaw)
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
}

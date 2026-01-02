package com.alexbomber12.memtag.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexbomber12.memtag.app.SyncCoordinator
import com.alexbomber12.memtag.app.SyncStatusState
import com.alexbomber12.memtag.data.repository.MementoRepository
import com.alexbomber12.memtag.data.settings.AppSettings
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.alexbomber12.memtag.domain.SyncState
import com.alexbomber12.memtag.integrations.uhf.UhfRegion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val repository: MementoRepository,
    private val syncCoordinator: SyncCoordinator,
) : ViewModel() {
    private var pendingSaveJob: Job? = null
    private var pendingSettings: AppSettings? = null

    val settingsState: StateFlow<AppSettings> =
        settingsStore.settingsFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = AppSettings(),
            )

    val syncStatusState: StateFlow<SyncStatusState> =
        syncCoordinator.status
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SyncStatusState.Idle,
            )

    val lastSyncState: StateFlow<SyncState?> =
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
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )

    val localItemCount: StateFlow<Int?> =
        repository.observeLocalItemCount()
            .distinctUntilChanged()
            .map<Int, Int?> { count -> count }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )

    fun queueSettingsUpdate(settings: AppSettings) {
        pendingSettings = settings
        pendingSaveJob?.cancel()
        pendingSaveJob =
            viewModelScope.launch {
                delay(SETTINGS_AUTO_SAVE_DEBOUNCE_MS)
                val latest = pendingSettings ?: return@launch
                persistSettings(latest)
            }
    }

    fun setMemento(
        baseUrl: String,
        token: String,
        libraryId: String,
    ) {
        viewModelScope.launch {
            settingsStore.setMemento(baseUrl, token, libraryId)
        }
    }

    fun setUhf(
        region: String,
        power: Int,
    ) {
        viewModelScope.launch {
            settingsStore.setUhf(region, power)
        }
    }

    fun setScan2d(
        action: String,
        extraKey: String,
    ) {
        viewModelScope.launch {
            settingsStore.setScan2d(action, extraKey)
        }
    }

    fun toggleShowDiagnosticsTab(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.update { current -> current.copy(showDiagnosticsTab = enabled) }
        }
    }

    fun toggleFindSound(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.update { current -> current.copy(findSoundEnabled = enabled) }
        }
    }

    fun toggleFindDebugOverlay(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.update { current -> current.copy(showFindDebugOverlay = enabled) }
        }
    }

    fun syncNow() {
        syncCoordinator.requestManualSync()
    }

    private suspend fun persistSettings(settings: AppSettings) {
        settingsStore.update { current ->
            val frequencyMode = UhfRegion.fromSettings(settings.uhfRegion).toFrequencyMode()
            val updated =
                current.copy(
                    mementoBaseUrl = settings.mementoBaseUrl,
                    mementoToken = settings.mementoToken,
                    mementoLibraryId = settings.mementoLibraryId,
                    uhfRegion = settings.uhfRegion,
                    uhfPower = settings.uhfPower,
                    uhfFrequencyMode = frequencyMode,
                    scan2dAction = settings.scan2dAction,
                    scan2dExtraKey = settings.scan2dExtraKey,
                    rfidKeyCodes = settings.rfidKeyCodes,
                    scanKeyCodes = settings.scanKeyCodes,
                )
            if (updated == current) current else updated
        }
    }
}

internal const val SETTINGS_AUTO_SAVE_DEBOUNCE_MS = 400L

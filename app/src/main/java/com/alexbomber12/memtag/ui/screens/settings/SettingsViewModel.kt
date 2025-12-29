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

    fun saveSettings(settings: AppSettings) {
        viewModelScope.launch {
            settingsStore.update { current ->
                val frequencyMode = UhfRegion.fromSettings(settings.uhfRegion).toFrequencyMode()
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
            }
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

    fun toggleFindHaptic(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.update { current -> current.copy(findHapticEnabled = enabled) }
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
}

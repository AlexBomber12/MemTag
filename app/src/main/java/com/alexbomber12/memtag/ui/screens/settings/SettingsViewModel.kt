package com.alexbomber12.memtag.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexbomber12.memtag.data.settings.AppSettings
import com.alexbomber12.memtag.data.settings.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsStore: SettingsStore,
) : ViewModel() {
    val settingsState: StateFlow<AppSettings> =
        settingsStore.settingsFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = AppSettings(),
            )

    fun saveSettings(settings: AppSettings) {
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(
                    mementoBaseUrl = settings.mementoBaseUrl,
                    mementoToken = settings.mementoToken,
                    mementoLibraryId = settings.mementoLibraryId,
                    uhfRegion = settings.uhfRegion,
                    uhfPower = settings.uhfPower,
                    scan2dAction = settings.scan2dAction,
                    scan2dExtraKey = settings.scan2dExtraKey,
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
}

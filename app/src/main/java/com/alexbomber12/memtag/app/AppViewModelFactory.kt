package com.alexbomber12.memtag.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.alexbomber12.memtag.ui.screens.diagnostics.DiagnosticsViewModel
import com.alexbomber12.memtag.ui.screens.lookup.LookupViewModel
import com.alexbomber12.memtag.ui.screens.settings.SettingsViewModel

class AppViewModelFactory(
    private val appContainer: AppContainer,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(appContainer.settingsStore) as T
        }
        if (modelClass.isAssignableFrom(DiagnosticsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DiagnosticsViewModel(
                settingsStore = appContainer.settingsStore,
                uhfReader = appContainer.uhfReader,
            ) as T
        }
        if (modelClass.isAssignableFrom(LookupViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LookupViewModel(
                settingsStore = appContainer.settingsStore,
                syncUseCase = appContainer.syncMementoLibraryUseCase,
                lookupUseCase = appContainer.lookupByEpcUseCase,
                repository = appContainer.mementoRepository,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

package com.alexbomber12.memtag.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.alexbomber12.memtag.ui.screens.diagnostics.DiagnosticsViewModel
import com.alexbomber12.memtag.ui.screens.find.FindViewModel
import com.alexbomber12.memtag.ui.screens.lookup.LookupViewModel
import com.alexbomber12.memtag.ui.screens.queue.QueueViewModel
import com.alexbomber12.memtag.ui.screens.repair.RepairViewModel
import com.alexbomber12.memtag.ui.screens.settings.SettingsViewModel

class AppViewModelFactory(
    private val appContainer: AppContainer,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(
                settingsStore = appContainer.settingsStore,
                repository = appContainer.mementoRepository,
                syncCoordinator = appContainer.syncCoordinator,
            ) as T
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
                lookupUseCase = appContainer.lookupByEpcUseCase,
                repository = appContainer.mementoRepository,
                scan2dScanner = appContainer.scan2dScanner,
                uhfReader = appContainer.uhfReader,
            ) as T
        }
        if (modelClass.isAssignableFrom(FindViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return FindViewModel(
                settingsStore = appContainer.settingsStore,
                uhfReader = appContainer.uhfReader,
                feedbackController = appContainer.findFeedbackController,
            ) as T
        }
        if (modelClass.isAssignableFrom(RepairViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RepairViewModel(
                uhfReader = appContainer.uhfReader,
                scan2dScanner = appContainer.scan2dScanner,
                actionsLogDao = appContainer.actionsLogDao,
                settingsStore = appContainer.settingsStore,
            ) as T
        }
        if (modelClass.isAssignableFrom(QueueViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return QueueViewModel(
                repository = appContainer.queueRepository,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

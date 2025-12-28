package com.alexbomber12.memtag.app

import com.alexbomber12.memtag.data.repository.MementoRepository
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.alexbomber12.memtag.domain.SyncMementoLibraryUseCase
import com.alexbomber12.memtag.domain.SyncProgress
import com.alexbomber12.memtag.domain.SyncProgressEvent
import com.alexbomber12.memtag.domain.SyncResult
import com.alexbomber12.memtag.domain.SyncStatus
import com.alexbomber12.memtag.integrations.memento.MementoSettingsValidation
import com.alexbomber12.memtag.integrations.memento.MementoSettingsValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

class SyncCoordinator(
    private val settingsStore: SettingsStore,
    private val repository: MementoRepository,
    private val syncUseCase: SyncMementoLibraryUseCase,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val minIntervalMs: Long = AUTO_SYNC_MIN_INTERVAL_MS,
) {
    private val syncMutex = Mutex()
    private val mutableStatus = MutableStateFlow<SyncStatusState>(SyncStatusState.Idle)
    val status: StateFlow<SyncStatusState> = mutableStatus

    private var syncJob: Job? = null

    fun requestAutoSync(hasNetwork: Boolean) {
        if (!hasNetwork) {
            return
        }
        scope.launch {
            runCatching { maybeSync(force = false) }
                .onFailure { error ->
                    mutableStatus.value = SyncStatusState.Error(error.message ?: "Sync failed.")
                }
        }
    }

    fun requestManualSync() {
        scope.launch {
            runCatching { maybeSync(force = true) }
                .onFailure { error ->
                    mutableStatus.value = SyncStatusState.Error(error.message ?: "Sync failed.")
                }
        }
    }

    private suspend fun maybeSync(force: Boolean): Boolean =
        syncMutex.withLock {
            if (syncJob?.isActive == true) {
                return false
            }
            val settings = settingsStore.settingsFlow.first()
            val validation =
                MementoSettingsValidator.validate(
                    baseUrl = settings.mementoBaseUrl,
                    token = settings.mementoToken,
                    libraryId = settings.mementoLibraryId,
                )
            if (validation is MementoSettingsValidation.Error) {
                if (force) {
                    mutableStatus.value = SyncStatusState.Error(validation.message)
                }
                return false
            }
            val libraryId = (validation as MementoSettingsValidation.Valid).config.libraryId
            if (!force) {
                val lastSync = repository.getSyncState(libraryId)
                val lastSyncAt = lastSync?.lastSyncAt ?: 0L
                if (clock() - lastSyncAt < minIntervalMs) {
                    return false
                }
            }
            startSync(libraryId)
            true
        }

    private fun startSync(libraryId: String) {
        val job =
            scope.launch {
                runCatching {
                    syncUseCase.execute(libraryId).collect { event ->
                        when (event) {
                            is SyncProgressEvent.Progress -> {
                                mutableStatus.value = SyncStatusState.Running(event.progress)
                            }

                            is SyncProgressEvent.Finished -> {
                                mutableStatus.value =
                                    if (event.result.status == SyncStatus.ERROR) {
                                        SyncStatusState.Error(event.result.errorMessage ?: "Sync failed.")
                                    } else {
                                        SyncStatusState.Completed(event.result)
                                    }
                            }
                        }
                    }
                }.onFailure { error ->
                    mutableStatus.value = SyncStatusState.Error(error.message ?: "Sync failed.")
                }
            }
        syncJob = job
        job.invokeOnCompletion {
            if (syncJob == job) {
                syncJob = null
            }
        }
    }

    private companion object {
        const val AUTO_SYNC_MIN_INTERVAL_MS = 10 * 60 * 1_000L
    }
}

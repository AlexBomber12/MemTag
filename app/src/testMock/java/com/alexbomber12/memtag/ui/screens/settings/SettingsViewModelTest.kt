package com.alexbomber12.memtag.ui.screens.settings

import com.alexbomber12.memtag.app.SyncCoordinator
import com.alexbomber12.memtag.data.repository.MementoRepository
import com.alexbomber12.memtag.data.settings.AppSettings
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.alexbomber12.memtag.domain.InventoryItem
import com.alexbomber12.memtag.domain.LookupResult
import com.alexbomber12.memtag.domain.SyncMementoLibraryUseCase
import com.alexbomber12.memtag.domain.SyncProgress
import com.alexbomber12.memtag.domain.SyncResult
import com.alexbomber12.memtag.domain.SyncState
import com.alexbomber12.memtag.domain.SyncStatus
import com.alexbomber12.memtag.integrations.memento.PagingStrategy
import com.alexbomber12.memtag.testing.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun queueSettingsUpdatePersistsAfterDebounce() =
        runTest(mainDispatcherRule.dispatcher) {
            val settingsStore = FakeSettingsStore()
            val repository = FakeMementoRepository()
            val scope = CoroutineScope(SupervisorJob() + mainDispatcherRule.dispatcher)
            val syncCoordinator =
                SyncCoordinator(
                    settingsStore = settingsStore,
                    repository = repository,
                    syncUseCase = SyncMementoLibraryUseCase(repository),
                    scope = scope,
                )
            val viewModel =
                SettingsViewModel(
                    settingsStore = settingsStore,
                    repository = repository,
                    syncCoordinator = syncCoordinator,
                )

            val updated =
                AppSettings(
                    mementoBaseUrl = "https://example.com",
                    mementoToken = "token-123",
                    mementoLibraryId = "lib-01",
                    uhfRegion = "US",
                    uhfPower = 25,
                    scan2dAction = "com.example.SCAN",
                    scan2dExtraKey = "payload",
                    rfidKeyCodes = "131,132",
                    scanKeyCodes = "133",
                )

            viewModel.queueSettingsUpdate(updated)
            advanceTimeBy(SETTINGS_AUTO_SAVE_DEBOUNCE_MS)
            advanceUntilIdle()

            val saved = settingsStore.settingsFlow.first()
            assertEquals("https://example.com", saved.mementoBaseUrl)
            assertEquals("token-123", saved.mementoToken)
            assertEquals("lib-01", saved.mementoLibraryId)
            assertEquals("US", saved.uhfRegion)
            assertEquals(25, saved.uhfPower)
            assertEquals("131,132", saved.rfidKeyCodes)
            assertEquals("133", saved.scanKeyCodes)
        }
}

private class FakeSettingsStore(
    initial: AppSettings = AppSettings(),
) : SettingsStore {
    private val state = MutableStateFlow(initial)

    override val settingsFlow = state

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.update { transform(it).sanitized() }
    }

    override suspend fun setMemento(
        baseUrl: String,
        token: String,
        libraryId: String,
    ) {
        state.update { it.copy(mementoBaseUrl = baseUrl, mementoToken = token, mementoLibraryId = libraryId).sanitized() }
    }

    override suspend fun setUhf(
        region: String,
        power: Int,
    ) {
        state.update { it.copy(uhfRegion = region, uhfPower = power).sanitized() }
    }

    override suspend fun setScan2d(
        action: String,
        extraKey: String,
    ) {
        state.update { it.copy(scan2dAction = action, scan2dExtraKey = extraKey).sanitized() }
    }
}

private class FakeMementoRepository : MementoRepository {
    override fun observeSyncState(libraryId: String): Flow<SyncState?> = flowOf(null)

    override suspend fun getSyncState(libraryId: String): SyncState? = null

    override fun observeLocalItemCount(libraryId: String): Flow<Int> = flowOf(0)

    override suspend fun syncLibrary(
        libraryId: String,
        onProgress: (SyncProgress) -> Unit,
    ): SyncResult {
        return SyncResult(
            status = SyncStatus.SUCCESS,
            fetchedCount = 0,
            storedCount = 0,
            skippedCount = 0,
            deletedTombstones = 0,
            durationMs = 0L,
            pagingStrategy = PagingStrategy.SINGLE_PAGE,
            errorMessage = null,
        )
    }

    override suspend fun lookupByEpc(epcRaw: String): LookupResult = LookupResult.NotFound

    override suspend fun searchInventory(
        query: String,
        limit: Int,
    ): List<InventoryItem> = emptyList()
}

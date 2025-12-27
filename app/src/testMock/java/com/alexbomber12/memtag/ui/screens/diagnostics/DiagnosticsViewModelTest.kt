package com.alexbomber12.memtag.ui.screens.diagnostics

import com.alexbomber12.memtag.data.settings.AppSettings
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.alexbomber12.memtag.integrations.uhf.FakeUhfReader
import com.alexbomber12.memtag.integrations.uhf.UhfRegion
import com.alexbomber12.memtag.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun startInventoryTwiceDoesNotDoubleEmit() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            viewModel.initialize()
            advanceUntilIdle()

            viewModel.startInventory()
            viewModel.startInventory()
            runCurrent()
            advanceTimeBy(310)
            runCurrent()

            val count = viewModel.uiState.value.readings.size
            assertEquals(3, count)

            viewModel.stopInventory()
            advanceUntilIdle()
        }

    @Test
    fun stopInventoryIsIdempotent() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            viewModel.initialize()
            advanceUntilIdle()

            viewModel.startInventory()
            runCurrent()

            viewModel.stopInventory()
            advanceUntilIdle()

            viewModel.stopInventory()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isInventoryRunning)
            assertNull(viewModel.uiState.value.lastErrorMessage)
        }

    @Test
    fun readSingleWhileInventoryRunningStopsInventory() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            viewModel.initialize()
            advanceUntilIdle()

            viewModel.startInventory()
            runCurrent()

            viewModel.readSingle()
            runCurrent()
            advanceTimeBy(200)
            runCurrent()

            assertNull(viewModel.uiState.value.lastErrorMessage)
            assertNotNull(viewModel.uiState.value.lastReadEpc)
            assertFalse(viewModel.uiState.value.isInventoryRunning)

            viewModel.stopInventory()
            advanceUntilIdle()
        }

    @Test
    fun readingsAreCapped() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            viewModel.initialize()
            advanceUntilIdle()

            viewModel.startInventory()
            runCurrent()
            advanceTimeBy(8_000)
            runCurrent()

            val count = viewModel.uiState.value.readings.size
            assertEquals(50, count)

            viewModel.stopInventory()
            advanceUntilIdle()
        }

    private fun createViewModel(): DiagnosticsViewModel {
        val settingsStore = FakeSettingsStore()
        val reader = FakeUhfReader(dispatcher = mainDispatcherRule.dispatcher)
        return DiagnosticsViewModel(
            settingsStore = settingsStore,
            uhfReader = reader,
        )
    }
}

private class FakeSettingsStore(
    initial: AppSettings = AppSettings(),
) : SettingsStore {
    private val state = MutableStateFlow(initial)

    override val settingsFlow: Flow<AppSettings> = state

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
        val frequencyMode = UhfRegion.fromSettings(region).toFrequencyMode()
        state.update {
            it.copy(
                uhfRegion = region,
                uhfPower = power,
                uhfFrequencyMode = frequencyMode,
            ).sanitized()
        }
    }

    override suspend fun setScan2d(
        action: String,
        extraKey: String,
    ) {
        state.update { it.copy(scan2dAction = action, scan2dExtraKey = extraKey).sanitized() }
    }
}

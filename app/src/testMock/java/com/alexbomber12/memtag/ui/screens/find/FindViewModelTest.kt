package com.alexbomber12.memtag.ui.screens.find

import com.alexbomber12.memtag.app.SessionFlagsStore
import com.alexbomber12.memtag.data.settings.AppSettings
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.alexbomber12.memtag.integrations.feedback.FindFeedbackController
import com.alexbomber12.memtag.integrations.uhf.FakeUhfReader
import com.alexbomber12.memtag.integrations.uhf.MatrixProbeResult
import com.alexbomber12.memtag.integrations.uhf.TagReading
import com.alexbomber12.memtag.integrations.uhf.UhfApplyResult
import com.alexbomber12.memtag.integrations.uhf.UhfError
import com.alexbomber12.memtag.integrations.uhf.UhfReader
import com.alexbomber12.memtag.integrations.uhf.UhfRegion
import com.alexbomber12.memtag.integrations.uhf.asException
import com.alexbomber12.memtag.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FindViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun startStopTransitionsToIdle() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel(FakeUhfReader(dispatcher = mainDispatcherRule.dispatcher))

            viewModel.onEpcInputChange("E2000017221101441890ABCD")
            viewModel.startFind()
            runCurrent()

            assertTrue(viewModel.uiState.value.isRunning)

            viewModel.stopFind()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isRunning)
            assertTrue(viewModel.uiState.value.status is FindStatus.Idle)
        }

    @Test
    fun invalidEpcSetsErrorState() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel(FakeUhfReader(dispatcher = mainDispatcherRule.dispatcher))

            viewModel.onEpcInputChange("BAD EPC")
            viewModel.startFind()
            runCurrent()

            assertTrue(viewModel.uiState.value.status is FindStatus.Error)
        }

    @Test
    fun uhfInitializationFailureReportsError() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel(FailingUhfReader())

            viewModel.onEpcInputChange("E2000017221101441890ABCD")
            viewModel.startFind()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.status is FindStatus.Error)
        }

    private fun createViewModel(reader: UhfReader): FindViewModel {
        return FindViewModel(
            settingsStore = FakeSettingsStore(),
            uhfReader = reader,
            feedbackController = FakeFeedbackController(),
            sessionFlagsStore = SessionFlagsStore(),
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

private class FakeFeedbackController : FindFeedbackController {
    override fun playSound() = Unit

    override fun vibrate(durationMs: Long) = Unit

    override fun release() = Unit
}

private class FailingUhfReader : UhfReader {
    override suspend fun initialize(): Result<Unit> = Result.failure(UhfError.HardwareUnavailable.asException())

    override suspend fun close(): Result<Unit> = Result.success(Unit)

    override suspend fun readSingle(timeoutMs: Long): Result<String> = Result.failure(UhfError.NotInitialized.asException())

    override suspend fun writeEpc(
        epcHex: String,
        targetEpcHex: String?,
        timeoutMs: Long,
    ): Result<Unit> = Result.failure(UhfError.NotInitialized.asException())

    override suspend fun verifyEpc(
        expectedEpcHex: String,
        timeoutMs: Long,
    ): Result<Boolean> = Result.failure(UhfError.NotInitialized.asException())

    override suspend fun startInventory(filterEpcHex: String?): Flow<TagReading> =
        flow {
            throw UhfError.NotInitialized.asException()
        }

    override suspend fun stopInventory(): Result<Unit> = Result.success(Unit)

    override suspend fun setPower(dbm: Int): Result<Unit> = Result.success(Unit)

    override suspend fun getPower(reason: String): Result<Int> = Result.success(0)

    override suspend fun getFrequencyMode(reason: String): Result<Int> = Result.success(0)

    override suspend fun getProtocol(reason: String): Result<Int> = Result.success(0)

    override suspend fun getRfLink(reason: String): Result<Int> = Result.success(0)

    override suspend fun setRegion(region: UhfRegion): Result<Unit> = Result.success(Unit)

    override suspend fun getRegion(reason: String): Result<UhfRegion> = Result.success(UhfRegion.OTHER)

    override suspend fun applyDesiredConfigBestEffort(reason: String): Result<UhfApplyResult> =
        Result.failure(UhfError.NotInitialized.asException())

    override suspend fun applyDesiredConfigWithReadback(reason: String): Result<UhfApplyResult> =
        Result.failure(UhfError.NotInitialized.asException())

    override suspend fun applyFindProfile(
        targetEpcHex: String?,
        useHardwareFilter: Boolean,
    ): Result<Unit> = Result.failure(UhfError.NotInitialized.asException())

    override suspend fun clearFindProfile(): Result<Unit> = Result.failure(UhfError.NotInitialized.asException())

    override suspend fun runMatrixProbe(): List<MatrixProbeResult> = emptyList()
}

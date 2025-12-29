package com.alexbomber12.memtag.ui.screens.repair

import com.alexbomber12.memtag.data.settings.AppSettings
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.alexbomber12.memtag.db.ActionsLogDao
import com.alexbomber12.memtag.db.ActionsLogEntity
import com.alexbomber12.memtag.domain.repair.RepairActionResult
import com.alexbomber12.memtag.domain.repair.RepairActionType
import com.alexbomber12.memtag.integrations.scan2d.Scan2dError
import com.alexbomber12.memtag.integrations.scan2d.Scan2dScanner
import com.alexbomber12.memtag.integrations.scan2d.asException
import com.alexbomber12.memtag.integrations.uhf.FakeUhfReader
import com.alexbomber12.memtag.integrations.uhf.UhfError
import com.alexbomber12.memtag.integrations.uhf.UhfRegion
import com.alexbomber12.memtag.integrations.uhf.asException
import com.alexbomber12.memtag.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepairViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun scanRfidMatchingExpectedSetsOkStatus() =
        runTest(mainDispatcherRule.dispatcher) {
            val reader = FakeUhfReader(dispatcher = mainDispatcherRule.dispatcher)
            reader.nextReadResult = Result.success("E2000017221101441890ABCD")
            val logs = FakeActionsLogDao()
            val settingsStore = FakeSettingsStore(AppSettings(selectedLookupEpc = "E2000017221101441890ABCD"))
            val viewModel = createViewModel(reader, logs, settingsStore = settingsStore)

            advanceUntilIdle()
            viewModel.scanRfid()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("E2000017221101441890ABCD", state.scannedEpc)
            assertTrue(state.status is VerifyWriteStatus.Ok)
        }

    @Test
    fun mismatchWriteConfirmsAndVerifies() =
        runTest(mainDispatcherRule.dispatcher) {
            val reader = FakeUhfReader(dispatcher = mainDispatcherRule.dispatcher)
            reader.nextReadResult = Result.success("E2000017221101441890ABCE")
            val logs = FakeActionsLogDao()
            val settingsStore = FakeSettingsStore(AppSettings(selectedLookupEpc = "E2000017221101441890ABCD"))
            val viewModel = createViewModel(reader, logs, settingsStore = settingsStore)

            advanceUntilIdle()
            viewModel.scanRfid()
            advanceUntilIdle()

            viewModel.startWriteConfirmation()
            viewModel.confirmWrite()
            advanceUntilIdle()

            assertEquals(1, reader.writeCalls)
            assertEquals(1, reader.verifyCalls)
            assertTrue(viewModel.uiState.value.status is VerifyWriteStatus.Ok)
            assertEquals("Write verified.", viewModel.uiState.value.message)
            val entries = logs.recentLogs(20)
            assertTrue(entries.any { it.actionType == RepairActionType.REPAIR_WRITE_SUCCESS.name })
        }

    @Test
    fun writeWithoutScanDoesNotConfirm() =
        runTest(mainDispatcherRule.dispatcher) {
            val reader = FakeUhfReader(dispatcher = mainDispatcherRule.dispatcher)
            val logs = FakeActionsLogDao()
            val settingsStore = FakeSettingsStore(AppSettings(selectedLookupEpc = "E2000017221101441890ABCD"))
            val viewModel = createViewModel(reader, logs, settingsStore = settingsStore)

            advanceUntilIdle()
            viewModel.startWriteConfirmation()
            advanceUntilIdle()

            val confirmation = viewModel.uiState.value.confirmation
            assertEquals(null, confirmation)
        }

    @Test
    fun writeFailureLogsAndShowsError() =
        runTest(mainDispatcherRule.dispatcher) {
            val reader = FakeUhfReader(dispatcher = mainDispatcherRule.dispatcher)
            reader.nextReadResult = Result.success("E2000017221101441890ABCE")
            reader.writeResultOverride = Result.failure(UhfError.VendorError("Locked").asException())
            val logs = FakeActionsLogDao()
            val settingsStore = FakeSettingsStore(AppSettings(selectedLookupEpc = "E2000017221101441890ABCD"))
            val viewModel = createViewModel(reader, logs, settingsStore = settingsStore)

            advanceUntilIdle()
            viewModel.scanRfid()
            advanceUntilIdle()

            viewModel.startWriteConfirmation()
            viewModel.confirmWrite()
            advanceUntilIdle()

            val error = viewModel.uiState.value.errorMessage
            assertNotNull(error)
            val entries = logs.recentLogs(20)
            val lastLog = entries.first { it.actionType == RepairActionType.REPAIR_WRITE_FAILED.name }
            assertEquals(RepairActionResult.FAILURE.name, lastLog.result)
            assertEquals("Locked", lastLog.message)
        }

    @Test
    fun verifyFailureDoesNotMarkSuccess() =
        runTest(mainDispatcherRule.dispatcher) {
            val reader = FakeUhfReader(dispatcher = mainDispatcherRule.dispatcher)
            reader.nextReadResult = Result.success("E2000017221101441890ABCE")
            reader.verifyResultOverride = Result.success(false)
            val logs = FakeActionsLogDao()
            val settingsStore = FakeSettingsStore(AppSettings(selectedLookupEpc = "E2000017221101441890ABCD"))
            val viewModel = createViewModel(reader, logs, settingsStore = settingsStore)

            advanceUntilIdle()
            viewModel.scanRfid()
            advanceUntilIdle()

            viewModel.startWriteConfirmation()
            viewModel.confirmWrite()
            advanceUntilIdle()

            val error = viewModel.uiState.value.errorMessage
            assertNotNull(error)
            val entries = logs.recentLogs(20)
            assertTrue(entries.any { it.actionType == RepairActionType.REPAIR_WRITE_FAILED.name })
            val message = viewModel.uiState.value.message
            assertTrue(message == null || !message.contains("Write verified"))
        }

    @Test
    fun expectedEpcComesFromLookup() =
        runTest(mainDispatcherRule.dispatcher) {
            val reader = FakeUhfReader(dispatcher = mainDispatcherRule.dispatcher)
            val logs = FakeActionsLogDao()
            val settingsStore =
                FakeSettingsStore(
                    AppSettings(
                        selectedLookupEpc = "E2000017221101441890ABCE",
                        selectedLookupName = "Tray 12",
                        selectedLookupStatus = "In stock",
                        selectedLookupLocation = "Aisle 3",
                        selectedLookupAt = 1_700_000_000_000L,
                    ),
                )
            val viewModel = createViewModel(reader, logs, settingsStore = settingsStore)

            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("E2000017221101441890ABCE", state.expectedEpc)
        }

    private fun createViewModel(
        reader: FakeUhfReader,
        logs: ActionsLogDao,
        settingsStore: SettingsStore = FakeSettingsStore(),
        scan2dScanner: Scan2dScanner = FakeScan2dScanner(),
    ): RepairViewModel {
        return RepairViewModel(
            uhfReader = reader,
            scan2dScanner = scan2dScanner,
            actionsLogDao = logs,
            settingsStore = settingsStore,
            clock = { 1_700_000_000_000L },
        )
    }
}

private class FakeScan2dScanner : Scan2dScanner {
    var nextResult: Result<String> = Result.failure(Scan2dError.Timeout.asException())

    override suspend fun scanOnce(timeoutMs: Long): Result<String> = nextResult
}

private class FakeActionsLogDao : ActionsLogDao {
    private var nextId = 1L
    private val stored = mutableListOf<ActionsLogEntity>()

    override suspend fun insert(log: ActionsLogEntity) {
        stored += log.copy(id = nextId++)
    }

    override suspend fun recentLogs(limit: Int): List<ActionsLogEntity> {
        return stored.sortedByDescending { it.createdAtEpochMs }.take(limit)
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

package com.alexbomber12.memtag.ui.screens.repair

import com.alexbomber12.memtag.data.repository.MementoRepository
import com.alexbomber12.memtag.db.ActionsLogDao
import com.alexbomber12.memtag.db.ActionsLogEntity
import com.alexbomber12.memtag.domain.InventoryItem
import com.alexbomber12.memtag.domain.LookupByEpcUseCase
import com.alexbomber12.memtag.domain.LookupResult
import com.alexbomber12.memtag.domain.SyncProgress
import com.alexbomber12.memtag.domain.SyncResult
import com.alexbomber12.memtag.domain.SyncState
import com.alexbomber12.memtag.domain.SyncStatus
import com.alexbomber12.memtag.domain.repair.RepairActionResult
import com.alexbomber12.memtag.domain.repair.RepairActionType
import com.alexbomber12.memtag.integrations.uhf.FakeUhfReader
import com.alexbomber12.memtag.integrations.uhf.UhfError
import com.alexbomber12.memtag.integrations.uhf.asException
import com.alexbomber12.memtag.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
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
    fun matchDoesNotInvokeWrite() =
        runTest(mainDispatcherRule.dispatcher) {
            val item = item(epc = "E2000017221101441890ABCD")
            val repository = FakeMementoRepository(listOf(item))
            val reader = FakeUhfReader(dispatcher = mainDispatcherRule.dispatcher)
            reader.nextReadResult = Result.success(item.epcNormalized)
            val logs = FakeActionsLogDao()
            val viewModel = createViewModel(repository, reader, logs)

            viewModel.selectItem(item)
            viewModel.readTag()
            advanceUntilIdle()

            viewModel.startRepairConfirmation()
            viewModel.confirmRepair()
            advanceUntilIdle()

            assertEquals(0, reader.writeCalls)
            assertTrue(viewModel.uiState.value.logs.any { it.actionType == RepairActionType.VERIFY_MATCH })
        }

    @Test
    fun mismatchRequiresConfirmationDelayBeforeWrite() =
        runTest(mainDispatcherRule.dispatcher) {
            val item = item(epc = "E2000017221101441890ABCD")
            val repository = FakeMementoRepository(listOf(item))
            val reader = FakeUhfReader(dispatcher = mainDispatcherRule.dispatcher)
            reader.nextReadResult = Result.success("E2000017221101441890ABCE")
            val logs = FakeActionsLogDao()
            val viewModel = createViewModel(repository, reader, logs)

            viewModel.selectItem(item)
            viewModel.readTag()
            advanceUntilIdle()

            viewModel.startRepairConfirmation()
            assertTrue(viewModel.uiState.value.showConfirmation)
            assertTrue(!viewModel.uiState.value.confirmEnabled)

            viewModel.confirmRepair()
            advanceUntilIdle()
            assertEquals(0, reader.writeCalls)

            advanceTimeBy(2_000L)
            advanceUntilIdle()
            viewModel.confirmRepair()
            advanceUntilIdle()

            assertEquals(1, reader.writeCalls)
        }

    @Test
    fun writeFailureLogsAndShowsError() =
        runTest(mainDispatcherRule.dispatcher) {
            val item = item(epc = "E2000017221101441890ABCD")
            val repository = FakeMementoRepository(listOf(item))
            val reader = FakeUhfReader(dispatcher = mainDispatcherRule.dispatcher)
            reader.nextReadResult = Result.success("E2000017221101441890ABCE")
            reader.writeResultOverride = Result.failure(UhfError.VendorError("Locked").asException())
            val logs = FakeActionsLogDao()
            val viewModel = createViewModel(repository, reader, logs)

            viewModel.selectItem(item)
            viewModel.readTag()
            advanceUntilIdle()

            viewModel.startRepairConfirmation()
            advanceTimeBy(2_000L)
            advanceUntilIdle()
            viewModel.confirmRepair()
            advanceUntilIdle()

            val error = viewModel.uiState.value.errorMessage
            assertNotNull(error)
            val lastLog = viewModel.uiState.value.logs.first { it.actionType == RepairActionType.REPAIR_WRITE_FAILED }
            assertEquals(RepairActionResult.FAILURE, lastLog.result)
            assertEquals("Locked", lastLog.message)
        }

    @Test
    fun verifyFailureDoesNotMarkSuccess() =
        runTest(mainDispatcherRule.dispatcher) {
            val item = item(epc = "E2000017221101441890ABCD")
            val repository = FakeMementoRepository(listOf(item))
            val reader = FakeUhfReader(dispatcher = mainDispatcherRule.dispatcher)
            reader.nextReadResult = Result.success("E2000017221101441890ABCE")
            reader.verifyResultOverride = Result.success(false)
            val logs = FakeActionsLogDao()
            val viewModel = createViewModel(repository, reader, logs)

            viewModel.selectItem(item)
            viewModel.readTag()
            advanceUntilIdle()

            viewModel.startRepairConfirmation()
            advanceTimeBy(2_000L)
            advanceUntilIdle()
            viewModel.confirmRepair()
            advanceUntilIdle()

            val error = viewModel.uiState.value.errorMessage
            assertNotNull(error)
            assertTrue(viewModel.uiState.value.logs.any { it.actionType == RepairActionType.REPAIR_WRITE_FAILED })
            val message = viewModel.uiState.value.message
            assertTrue(message == null || !message.contains("Write verified"))
        }

    @Test
    fun cancelLogsCancelled() =
        runTest(mainDispatcherRule.dispatcher) {
            val item = item(epc = "E2000017221101441890ABCD")
            val repository = FakeMementoRepository(listOf(item))
            val reader = FakeUhfReader(dispatcher = mainDispatcherRule.dispatcher)
            reader.nextReadResult = Result.success("E2000017221101441890ABCE")
            val logs = FakeActionsLogDao()
            val viewModel = createViewModel(repository, reader, logs)

            viewModel.selectItem(item)
            viewModel.readTag()
            advanceUntilIdle()

            viewModel.startRepairConfirmation()
            viewModel.cancelOperations()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.logs.any { it.actionType == RepairActionType.REPAIR_WRITE_CANCELLED })
            assertEquals(0, reader.writeCalls)
        }

    private fun createViewModel(
        repository: MementoRepository,
        reader: FakeUhfReader,
        logs: ActionsLogDao,
    ): RepairViewModel {
        return RepairViewModel(
            repository = repository,
            lookupByEpcUseCase = LookupByEpcUseCase(repository),
            uhfReader = reader,
            actionsLogDao = logs,
            clock = { 1_700_000_000_000L },
        )
    }

    private fun item(epc: String): InventoryItem {
        return InventoryItem(
            entryId = "entry-$epc",
            epcNormalized = epc,
            name = "Item $epc",
            content = null,
            locationPath = null,
            status = null,
            category = null,
            comment = null,
            labelRev = null,
            toPrint = null,
            um = "UM-$epc",
            qrRaw = null,
            photoThumbUrlOrRef = null,
            updatedAt = null,
        )
    }
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

private class FakeMementoRepository(
    private val items: List<InventoryItem>,
) : MementoRepository {
    override suspend fun syncLibrary(
        libraryId: String,
        onProgress: (SyncProgress) -> Unit,
    ): SyncResult {
        return SyncResult(
            status = SyncStatus.SUCCESS,
            fetchedCount = 0,
            storedCount = 0,
            skippedCount = 0,
            durationMs = 0L,
            pagingStrategy = null,
            errorMessage = null,
        )
    }

    override suspend fun lookupByEpc(
        epcRaw: String,
        allowNetwork: Boolean,
    ): LookupResult {
        val found = items.firstOrNull { it.epcNormalized == epcRaw }
        return if (found != null) {
            LookupResult.Found(found)
        } else {
            LookupResult.NotFound
        }
    }

    override suspend fun searchInventory(
        query: String,
        limit: Int,
    ): List<InventoryItem> {
        val needle = query.trim().lowercase()
        return items.filter { item ->
            val candidates =
                listOfNotNull(item.name, item.um, item.epcNormalized)
            candidates.any { it.lowercase().contains(needle) }
        }.take(limit)
    }

    override fun observeSyncState(libraryId: String): Flow<SyncState?> = flowOf(null)
}

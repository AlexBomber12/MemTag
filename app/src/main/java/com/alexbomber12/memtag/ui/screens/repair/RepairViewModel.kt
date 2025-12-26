package com.alexbomber12.memtag.ui.screens.repair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexbomber12.memtag.data.repository.MementoRepository
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.alexbomber12.memtag.db.ActionsLogDao
import com.alexbomber12.memtag.db.ActionsLogEntity
import com.alexbomber12.memtag.domain.InventoryItem
import com.alexbomber12.memtag.domain.LookupByEpcUseCase
import com.alexbomber12.memtag.domain.LookupResult
import com.alexbomber12.memtag.domain.repair.RepairActionLog
import com.alexbomber12.memtag.domain.repair.RepairActionResult
import com.alexbomber12.memtag.domain.repair.RepairActionType
import com.alexbomber12.memtag.domain.repair.RepairComparison
import com.alexbomber12.memtag.domain.repair.RepairDecisionUseCase
import com.alexbomber12.memtag.integrations.uhf.UhfError
import com.alexbomber12.memtag.integrations.uhf.UhfException
import com.alexbomber12.memtag.integrations.uhf.UhfReader
import com.alexbomber12.memtag.util.epc.EpcNormalizer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class RepairLookupState {
    data object Idle : RepairLookupState()

    data class Found(
        val item: InventoryItem,
    ) : RepairLookupState()

    data object NotFound : RepairLookupState()
}

data class RepairUiState(
    val searchQuery: String = "",
    val searchResults: List<InventoryItem> = emptyList(),
    val selectedItem: InventoryItem? = null,
    val expectedEpc: String? = null,
    val currentEpc: String? = null,
    val lookupState: RepairLookupState = RepairLookupState.Idle,
    val comparison: RepairComparison = RepairComparison.NotReady,
    val isReading: Boolean = false,
    val isWriting: Boolean = false,
    val isVerifying: Boolean = false,
    val showConfirmation: Boolean = false,
    val confirmEnabled: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
    val logs: List<RepairActionLog> = emptyList(),
)

class RepairViewModel(
    private val repository: MementoRepository,
    private val lookupByEpcUseCase: LookupByEpcUseCase,
    private val uhfReader: UhfReader,
    private val actionsLogDao: ActionsLogDao,
    private val settingsStore: SettingsStore,
    private val decisionUseCase: RepairDecisionUseCase = RepairDecisionUseCase(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(RepairUiState())
    val uiState: StateFlow<RepairUiState> = mutableState

    private var operationJob: Job? = null
    private var confirmationJob: Job? = null

    init {
        viewModelScope.launch {
            refreshLogs()
        }
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                mutableState.update { state ->
                    val prefillCurrent =
                        state.currentEpc ?: settings.lastScannedEpc.takeIf { it.isNotBlank() }
                    val prefillExpected =
                        if (state.selectedItem == null) {
                            state.expectedEpc ?: settings.lastFindTargetEpc.takeIf { it.isNotBlank() }
                        } else {
                            state.selectedItem.epcNormalized
                        }
                    val updated =
                        state.copy(
                            currentEpc = prefillCurrent,
                            expectedEpc = prefillExpected,
                        )
                    updated.copy(comparison = decisionUseCase.evaluate(resolveExpectedEpc(updated), updated.currentEpc))
                }
            }
        }
    }

    fun onSearchQueryChange(value: String) {
        mutableState.update { it.copy(searchQuery = value) }
    }

    fun searchInventory() {
        val query = uiState.value.searchQuery
        if (query.isBlank()) {
            mutableState.update { it.copy(searchResults = emptyList()) }
            return
        }
        viewModelScope.launch {
            val results = repository.searchInventory(query, SEARCH_LIMIT)
            mutableState.update { it.copy(searchResults = results) }
        }
    }

    fun selectItem(item: InventoryItem) {
        mutableState.update {
            it.copy(
                selectedItem = item,
                expectedEpc = item.epcNormalized,
                lookupState = RepairLookupState.Idle,
                message = null,
                errorMessage = null,
            )
        }
        updateComparison()
    }

    fun clearSelection() {
        mutableState.update {
            it.copy(
                selectedItem = null,
                comparison = RepairComparison.NotReady,
                message = null,
                errorMessage = null,
            )
        }
        updateComparison()
    }

    fun onExpectedEpcChange(value: String) {
        mutableState.update { it.copy(expectedEpc = value) }
        updateComparison()
    }

    fun readTag() {
        if (operationJob != null) {
            return
        }
        mutableState.update { it.copy(isReading = true, message = null, errorMessage = null) }
        val job =
            viewModelScope.launch {
                val initResult = uhfReader.initialize()
                if (initResult.isFailure) {
                    updateError(mapUhfError(initResult.exceptionOrNull()))
                    return@launch
                }
                val readResult = uhfReader.readSingle(READ_TIMEOUT_MS)
                if (readResult.isFailure) {
                    updateError(mapUhfError(readResult.exceptionOrNull()))
                    return@launch
                }
                val normalized =
                    runCatching { EpcNormalizer.normalize(readResult.getOrNull().orEmpty()) }.getOrElse { error ->
                        updateError(error.message ?: "Invalid EPC read from tag.")
                        return@launch
                    }
                val selected = uiState.value.selectedItem
                mutableState.update { state ->
                    state.copy(
                        isReading = false,
                        currentEpc = normalized,
                        lookupState = if (selected != null) RepairLookupState.Idle else state.lookupState,
                        message = null,
                        errorMessage = null,
                    )
                }
                if (selected != null) {
                    val decision = decisionUseCase.evaluate(selected.epcNormalized, normalized)
                    applyComparison(decision)
                    when (decision) {
                        is RepairComparison.Match -> {
                            logAction(
                                actionType = RepairActionType.VERIFY_MATCH,
                                expectedEpc = decision.expectedEpc,
                                currentEpc = decision.currentEpc,
                                result = RepairActionResult.SUCCESS,
                                message = null,
                            )
                            mutableState.update { it.copy(message = "Already correct.") }
                        }

                        is RepairComparison.Mismatch -> {
                            logAction(
                                actionType = RepairActionType.VERIFY_MISMATCH,
                                expectedEpc = decision.expectedEpc,
                                currentEpc = decision.currentEpc,
                                result = RepairActionResult.FAILURE,
                                message = "EPC mismatch.",
                            )
                        }

                        is RepairComparison.Invalid -> {
                            updateError(decision.message)
                        }

                        RepairComparison.NotReady -> Unit
                    }
                } else {
                    when (val lookupResult = lookupByEpcUseCase.execute(normalized)) {
                        is LookupResult.Found -> {
                            mutableState.update { it.copy(lookupState = RepairLookupState.Found(lookupResult.item)) }
                            logAction(
                                actionType = RepairActionType.VERIFY_LOOKUP_FOUND,
                                expectedEpc = lookupResult.item.epcNormalized,
                                currentEpc = normalized,
                                result = RepairActionResult.SUCCESS,
                                message = null,
                            )
                        }

                        is LookupResult.NotFound -> {
                            mutableState.update { it.copy(lookupState = RepairLookupState.NotFound) }
                            logAction(
                                actionType = RepairActionType.VERIFY_LOOKUP_NOT_FOUND,
                                expectedEpc = null,
                                currentEpc = normalized,
                                result = RepairActionResult.FAILURE,
                                message = "No matching item found.",
                            )
                        }

                        is LookupResult.Error -> {
                            updateError(lookupResult.message)
                        }
                    }
                }
                updateComparison()
            }
        operationJob = job
        job.invokeOnCompletion { operationJob = null }
    }

    fun startRepairConfirmation() {
        if (uiState.value.showConfirmation) {
            return
        }
        val decision = decisionUseCase.evaluate(resolveExpectedEpc(), uiState.value.currentEpc)
        if (decision !is RepairComparison.Mismatch) {
            updateError("Repair is only available when the tag EPC does not match the expected EPC.")
            return
        }
        mutableState.update { it.copy(showConfirmation = true, confirmEnabled = false, message = null, errorMessage = null) }
        confirmationJob?.cancel()
        confirmationJob =
            viewModelScope.launch {
                delay(CONFIRM_DELAY_MS)
                mutableState.update { it.copy(confirmEnabled = true) }
            }
    }

    fun confirmRepair() {
        val state = uiState.value
        if (!state.showConfirmation || !state.confirmEnabled) {
            return
        }
        mutableState.update { it.copy(showConfirmation = false, confirmEnabled = false) }
        confirmationJob?.cancel()
        confirmationJob = null
        performRepair()
    }

    fun cancelOperations() {
        val shouldLogCancel =
            uiState.value.showConfirmation || uiState.value.isWriting || uiState.value.isVerifying
        confirmationJob?.cancel()
        confirmationJob = null
        operationJob?.cancel()
        operationJob = null
        viewModelScope.launch {
            uhfReader.stopInventory()
        }
        mutableState.update {
            it.copy(
                isReading = false,
                isWriting = false,
                isVerifying = false,
                showConfirmation = false,
                confirmEnabled = false,
            )
        }
        if (shouldLogCancel) {
            val expected = resolveExpectedEpc()
            val current = uiState.value.currentEpc
            viewModelScope.launch {
                logAction(
                    actionType = RepairActionType.REPAIR_WRITE_CANCELLED,
                    expectedEpc = expected,
                    currentEpc = current,
                    result = RepairActionResult.CANCELLED,
                    message = null,
                )
            }
        }
    }

    override fun onCleared() {
        cancelOperations()
        viewModelScope.launch {
            uhfReader.close()
        }
        super.onCleared()
    }

    private fun performRepair() {
        if (operationJob != null) {
            return
        }
        val expected = resolveExpectedEpc()
        val current = uiState.value.currentEpc
        val decision = decisionUseCase.evaluate(expected, current)
        if (decision !is RepairComparison.Mismatch) {
            updateError("Repair is only available when the tag EPC does not match the expected EPC.")
            return
        }
        val job =
            viewModelScope.launch {
                mutableState.update { it.copy(isWriting = true, isVerifying = false, message = null, errorMessage = null) }
                logAction(
                    actionType = RepairActionType.REPAIR_WRITE_ATTEMPT,
                    expectedEpc = decision.expectedEpc,
                    currentEpc = decision.currentEpc,
                    result = RepairActionResult.SUCCESS,
                    message = null,
                )
                val initResult = uhfReader.initialize()
                if (initResult.isFailure) {
                    handleRepairFailure(initResult.exceptionOrNull())
                    return@launch
                }
                val writeResult = uhfReader.writeEpc(decision.expectedEpc, WRITE_TIMEOUT_MS)
                if (writeResult.isFailure) {
                    handleRepairFailure(writeResult.exceptionOrNull())
                    return@launch
                }
                mutableState.update { it.copy(isWriting = false, isVerifying = true) }
                val verifyResult = uhfReader.verifyEpc(decision.expectedEpc, VERIFY_TIMEOUT_MS)
                val verified = verifyResult.getOrNull()
                if (verifyResult.isFailure || verified != true) {
                    val message =
                        if (verifyResult.isFailure) {
                            verifyResult.exceptionOrNull()?.message ?: "Verify failed."
                        } else {
                            "Verify mismatch after write."
                        }
                    handleRepairFailure(verifyResult.exceptionOrNull(), message)
                    return@launch
                }
                mutableState.update {
                    it.copy(
                        isWriting = false,
                        isVerifying = false,
                        currentEpc = decision.expectedEpc,
                        comparison = RepairComparison.Match(decision.expectedEpc, decision.expectedEpc),
                        message = "Write verified.",
                    )
                }
                logAction(
                    actionType = RepairActionType.REPAIR_WRITE_SUCCESS,
                    expectedEpc = decision.expectedEpc,
                    currentEpc = decision.expectedEpc,
                    result = RepairActionResult.SUCCESS,
                    message = null,
                )
            }
        operationJob = job
        job.invokeOnCompletion { operationJob = null }
    }

    private suspend fun logAction(
        actionType: RepairActionType,
        expectedEpc: String?,
        currentEpc: String?,
        result: RepairActionResult,
        message: String?,
    ) {
        actionsLogDao.insert(
            ActionsLogEntity(
                createdAtEpochMs = clock(),
                actionType = actionType.name,
                expectedEpc = expectedEpc,
                currentEpc = currentEpc,
                result = result.name,
                message = message,
            ),
        )
        refreshLogs()
    }

    private suspend fun refreshLogs() {
        val entries = actionsLogDao.recentLogs(LOG_LIMIT)
        mutableState.update { it.copy(logs = entries.map { entry -> entry.toDomain() }) }
    }

    private fun updateComparison() {
        val state = uiState.value
        val decision = decisionUseCase.evaluate(resolveExpectedEpc(state), state.currentEpc)
        applyComparison(decision)
    }

    private fun resolveExpectedEpc(state: RepairUiState = uiState.value): String? {
        return state.selectedItem?.epcNormalized ?: state.expectedEpc
    }

    private fun applyComparison(decision: RepairComparison) {
        mutableState.update {
            it.copy(
                comparison = decision,
                errorMessage = if (decision is RepairComparison.Invalid) decision.message else it.errorMessage,
            )
        }
    }

    private fun handleRepairFailure(
        error: Throwable?,
        overrideMessage: String? = null,
    ) {
        val friendly = overrideMessage ?: mapWriteError(error)
        mutableState.update { it.copy(isWriting = false, isVerifying = false, errorMessage = friendly) }
        viewModelScope.launch {
            val expected = resolveExpectedEpc()
            val current = uiState.value.currentEpc
            logAction(
                actionType = RepairActionType.REPAIR_WRITE_FAILED,
                expectedEpc = expected,
                currentEpc = current,
                result = RepairActionResult.FAILURE,
                message = error?.message ?: overrideMessage,
            )
        }
    }

    private fun updateError(message: String) {
        mutableState.update { it.copy(isReading = false, isWriting = false, isVerifying = false, errorMessage = message) }
    }

    private fun mapUhfError(error: Throwable?): String {
        val uhfError = (error as? UhfException)?.error
        return when (uhfError) {
            UhfError.NotInitialized -> "UHF not initialized."
            UhfError.HardwareUnavailable -> "UHF hardware unavailable."
            UhfError.Timeout -> "UHF operation timed out."
            UhfError.OperationInProgress -> "Another UHF operation is already running."
            is UhfError.VendorError -> uhfError.message
            null -> error?.message ?: "Unknown UHF error."
        }
    }

    private fun mapWriteError(error: Throwable?): String {
        val uhfError = (error as? UhfException)?.error
        return when (uhfError) {
            UhfError.NotInitialized -> "UHF not initialized."
            UhfError.HardwareUnavailable -> "UHF hardware unavailable."
            UhfError.Timeout -> "Write timed out. Try again."
            UhfError.OperationInProgress -> "Another UHF operation is already running."
            is UhfError.VendorError -> "Write failed. Tag may be locked or not writable."
            null -> error?.message ?: "Write failed."
        }
    }

    private fun ActionsLogEntity.toDomain(): RepairActionLog {
        val action = RepairActionType.values().firstOrNull { it.name == actionType } ?: RepairActionType.REPAIR_WRITE_FAILED
        val resultValue = RepairActionResult.values().firstOrNull { it.name == result } ?: RepairActionResult.FAILURE
        return RepairActionLog(
            id = id,
            createdAtEpochMs = createdAtEpochMs,
            actionType = action,
            expectedEpc = expectedEpc,
            currentEpc = currentEpc,
            result = resultValue,
            message = message,
        )
    }

    private companion object {
        const val READ_TIMEOUT_MS = 4_000L
        const val WRITE_TIMEOUT_MS = 7_000L
        const val VERIFY_TIMEOUT_MS = 7_000L
        const val CONFIRM_DELAY_MS = 2_000L
        const val SEARCH_LIMIT = 20
        const val LOG_LIMIT = 20
    }
}

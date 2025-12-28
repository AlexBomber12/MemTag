package com.alexbomber12.memtag.ui.screens.repair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.alexbomber12.memtag.db.ActionsLogDao
import com.alexbomber12.memtag.db.ActionsLogEntity
import com.alexbomber12.memtag.domain.repair.RepairActionLog
import com.alexbomber12.memtag.domain.repair.RepairActionResult
import com.alexbomber12.memtag.domain.repair.RepairActionType
import com.alexbomber12.memtag.integrations.scan2d.Scan2dError
import com.alexbomber12.memtag.integrations.scan2d.Scan2dException
import com.alexbomber12.memtag.integrations.scan2d.Scan2dScanner
import com.alexbomber12.memtag.integrations.scan2d.asException
import com.alexbomber12.memtag.integrations.uhf.UhfError
import com.alexbomber12.memtag.integrations.uhf.UhfException
import com.alexbomber12.memtag.integrations.uhf.UhfReader
import com.alexbomber12.memtag.integrations.uhf.toErrorMessage
import com.alexbomber12.memtag.util.epc.EpcNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class VerifyWriteStatus {
    data class NotScanned(
        val expectedEpc: String,
    ) : VerifyWriteStatus()

    data class Ok(
        val expectedEpc: String,
    ) : VerifyWriteStatus()

    data class Mismatch(
        val expectedEpc: String,
        val scannedEpc: String,
    ) : VerifyWriteStatus()

    data class Invalid(
        val message: String,
    ) : VerifyWriteStatus()
}

enum class WriteWarning {
    NOT_SCANNED,
    MISMATCH,
}

data class WriteConfirmation(
    val expectedEpc: String,
    val scannedEpc: String?,
    val warning: WriteWarning?,
)

data class RepairUiState(
    val expectedEpc: String = "",
    val scannedEpc: String? = null,
    val status: VerifyWriteStatus = VerifyWriteStatus.Invalid("Expected EPC is required."),
    val isReading: Boolean = false,
    val isScanningQr: Boolean = false,
    val isWriting: Boolean = false,
    val isVerifying: Boolean = false,
    val confirmation: WriteConfirmation? = null,
    val message: String? = null,
    val errorMessage: String? = null,
    val logs: List<RepairActionLog> = emptyList(),
    val lastFindTargetEpc: String = "",
    val lastLookupEpc: String = "",
)

class RepairViewModel(
    private val uhfReader: UhfReader,
    private val scan2dScanner: Scan2dScanner,
    private val actionsLogDao: ActionsLogDao,
    private val settingsStore: SettingsStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(RepairUiState())
    val uiState: StateFlow<RepairUiState> = mutableState

    private var operationJob: Job? = null
    private var expectedEdited = false

    init {
        viewModelScope.launch {
            refreshLogs()
        }
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                mutableState.update { state ->
                    val prefillExpected =
                        if (!expectedEdited && state.expectedEpc.isBlank()) {
                            settings.lastFindTargetEpc.takeIf { it.isNotBlank() }
                                ?: settings.lastScannedEpc.takeIf { it.isNotBlank() }
                        } else {
                            null
                        }
                    val updated =
                        state.copy(
                            expectedEpc = prefillExpected ?: state.expectedEpc,
                            lastFindTargetEpc = settings.lastFindTargetEpc,
                            lastLookupEpc = settings.lastScannedEpc,
                        )
                    updated.copy(status = evaluateStatus(updated.expectedEpc, updated.scannedEpc))
                }
            }
        }
    }

    fun onScreenOpened() {
        mutableState.update { state ->
            val updated =
                state.copy(
                    scannedEpc = null,
                    confirmation = null,
                    message = null,
                    errorMessage = null,
                )
            updated.copy(status = evaluateStatus(updated.expectedEpc, updated.scannedEpc))
        }
    }

    fun onExpectedEpcChange(value: String) {
        expectedEdited = true
        mutableState.update { state ->
            val updated =
                state.copy(
                    expectedEpc = value,
                    confirmation = null,
                    message = null,
                    errorMessage = null,
                )
            updated.copy(status = evaluateStatus(updated.expectedEpc, updated.scannedEpc))
        }
    }

    fun useExpectedFromFind() {
        val value = uiState.value.lastFindTargetEpc
        if (value.isBlank()) {
            return
        }
        expectedEdited = true
        mutableState.update { state ->
            val updated =
                state.copy(
                    expectedEpc = value,
                    confirmation = null,
                    message = null,
                    errorMessage = null,
                )
            updated.copy(status = evaluateStatus(updated.expectedEpc, updated.scannedEpc))
        }
    }

    fun useExpectedFromLookup() {
        val value = uiState.value.lastLookupEpc
        if (value.isBlank()) {
            return
        }
        expectedEdited = true
        mutableState.update { state ->
            val updated =
                state.copy(
                    expectedEpc = value,
                    confirmation = null,
                    message = null,
                    errorMessage = null,
                )
            updated.copy(status = evaluateStatus(updated.expectedEpc, updated.scannedEpc))
        }
    }

    fun clearScanned() {
        mutableState.update { state ->
            val updated =
                state.copy(
                    scannedEpc = null,
                    confirmation = null,
                    message = null,
                    errorMessage = null,
                )
            updated.copy(status = evaluateStatus(updated.expectedEpc, updated.scannedEpc))
        }
    }

    fun scanRfid() {
        val state = uiState.value
        if (operationJob != null || state.confirmation != null) {
            return
        }
        mutableState.update { it.copy(isReading = true, isScanningQr = false, message = null, errorMessage = null) }
        val job =
            viewModelScope.launch {
                val initResult = uhfReader.initialize()
                if (initResult.isFailure) {
                    handleScanFailure(mapUhfError(initResult.exceptionOrNull()))
                    return@launch
                }
                uhfReader.stopInventory()
                val applyResult = uhfReader.applyDesiredConfigBestEffort("verify-write-scan")
                if (applyResult.isFailure) {
                    handleScanFailure(mapUhfError(applyResult.exceptionOrNull()))
                    return@launch
                }
                val applied = applyResult.getOrNull()
                if (applied != null && !applied.success) {
                    handleScanFailure(applied.toErrorMessage())
                    return@launch
                }
                val readResult = uhfReader.readSingle(READ_TIMEOUT_MS)
                if (readResult.isFailure) {
                    handleScanFailure(mapUhfError(readResult.exceptionOrNull()))
                    return@launch
                }
                val normalized =
                    runCatching { EpcNormalizer.normalize(readResult.getOrNull().orEmpty()) }.getOrElse { error ->
                        handleScanFailure(error.message ?: "Invalid EPC read from tag.")
                        return@launch
                    }
                applyScannedEpc(normalized)
            }
        operationJob = job
        job.invokeOnCompletion { error ->
            if (error is CancellationException) {
                mutableState.update { it.copy(isReading = false) }
            }
            operationJob = null
        }
    }

    fun scanQr() {
        val state = uiState.value
        if (operationJob != null || state.confirmation != null) {
            return
        }
        mutableState.update { it.copy(isScanningQr = true, isReading = false, message = null, errorMessage = null) }
        val job =
            viewModelScope.launch {
                val result =
                    runCatching { withContext(Dispatchers.IO) { scan2dScanner.scanOnce() } }
                        .getOrElse { error ->
                            val mapped =
                                if (error is CancellationException) {
                                    Scan2dError.Cancelled.asException(message = "QR scan cancelled.", cause = error)
                                } else {
                                    error
                                }
                            Result.failure(mapped)
                        }
                result
                    .onSuccess { epc ->
                        val normalized =
                            runCatching { EpcNormalizer.normalize(epc) }.getOrElse { error ->
                                handleScanFailure(error.message ?: "Invalid EPC read from QR.")
                                return@onSuccess
                            }
                        applyScannedEpc(normalized)
                    }
                    .onFailure { error ->
                        handleScanFailure(mapScanError(error))
                    }
            }
        operationJob = job
        job.invokeOnCompletion { error ->
            if (error is CancellationException) {
                mutableState.update { it.copy(isScanningQr = false) }
            }
            operationJob = null
        }
    }

    fun startWriteConfirmation() {
        val state = uiState.value
        if (state.confirmation != null || isBusy(state)) {
            return
        }
        val status = evaluateStatus(state.expectedEpc, state.scannedEpc)
        mutableState.update { it.copy(status = status) }
        when (status) {
            is VerifyWriteStatus.Invalid -> return
            is VerifyWriteStatus.Ok -> {
                mutableState.update { it.copy(message = "Tag already matches expected EPC.") }
                return
            }
            is VerifyWriteStatus.NotScanned -> {
                mutableState.update {
                    it.copy(
                        confirmation =
                            WriteConfirmation(
                                expectedEpc = status.expectedEpc,
                                scannedEpc = null,
                                warning = WriteWarning.NOT_SCANNED,
                            ),
                        message = null,
                        errorMessage = null,
                    )
                }
            }
            is VerifyWriteStatus.Mismatch -> {
                mutableState.update {
                    it.copy(
                        confirmation =
                            WriteConfirmation(
                                expectedEpc = status.expectedEpc,
                                scannedEpc = status.scannedEpc,
                                warning = WriteWarning.MISMATCH,
                            ),
                        message = null,
                        errorMessage = null,
                    )
                }
            }
        }
    }

    fun dismissConfirmation() {
        mutableState.update { it.copy(confirmation = null) }
    }

    fun confirmWrite() {
        val confirmation = uiState.value.confirmation ?: return
        if (operationJob != null) {
            return
        }
        mutableState.update { it.copy(confirmation = null) }
        performWrite(confirmation.expectedEpc)
    }

    fun cancelOperations() {
        operationJob?.cancel()
        operationJob = null
        viewModelScope.launch {
            uhfReader.stopInventory()
        }
        mutableState.update {
            it.copy(
                isReading = false,
                isScanningQr = false,
                isWriting = false,
                isVerifying = false,
                confirmation = null,
            )
        }
    }

    override fun onCleared() {
        cancelOperations()
        viewModelScope.launch {
            uhfReader.close()
        }
        super.onCleared()
    }

    private fun performWrite(expectedEpc: String) {
        val job =
            viewModelScope.launch {
                mutableState.update { it.copy(isWriting = true, isVerifying = false, message = null, errorMessage = null) }
                logAction(
                    actionType = RepairActionType.REPAIR_WRITE_ATTEMPT,
                    expectedEpc = expectedEpc,
                    currentEpc = uiState.value.scannedEpc,
                    result = RepairActionResult.SUCCESS,
                    message = null,
                )
                val initResult = uhfReader.initialize()
                if (initResult.isFailure) {
                    handleWriteFailure(initResult.exceptionOrNull())
                    return@launch
                }
                uhfReader.stopInventory()
                val applyResult = uhfReader.applyDesiredConfigBestEffort("verify-write")
                if (applyResult.isFailure) {
                    val error = applyResult.exceptionOrNull()
                    handleWriteFailure(error, mapUhfError(error))
                    return@launch
                }
                val applied = applyResult.getOrNull()
                if (applied != null && !applied.success) {
                    handleWriteFailure(null, applied.toErrorMessage())
                    return@launch
                }
                val writeResult = uhfReader.writeEpc(expectedEpc, WRITE_TIMEOUT_MS)
                if (writeResult.isFailure) {
                    handleWriteFailure(writeResult.exceptionOrNull())
                    return@launch
                }
                mutableState.update { it.copy(isWriting = false, isVerifying = true) }
                val verifyResult = uhfReader.verifyEpc(expectedEpc, VERIFY_TIMEOUT_MS)
                val verified = verifyResult.getOrNull()
                if (verifyResult.isFailure || verified != true) {
                    val message =
                        if (verifyResult.isFailure) {
                            verifyResult.exceptionOrNull()?.message ?: "Verify failed."
                        } else {
                            "Verify mismatch after write."
                        }
                    handleWriteFailure(verifyResult.exceptionOrNull(), message)
                    return@launch
                }
                mutableState.update { state ->
                    val updated =
                        state.copy(
                            isWriting = false,
                            isVerifying = false,
                            scannedEpc = expectedEpc,
                            message = "Write verified.",
                            errorMessage = null,
                        )
                    updated.copy(status = VerifyWriteStatus.Ok(expectedEpc))
                }
                logAction(
                    actionType = RepairActionType.REPAIR_WRITE_SUCCESS,
                    expectedEpc = expectedEpc,
                    currentEpc = expectedEpc,
                    result = RepairActionResult.SUCCESS,
                    message = null,
                )
            }
        operationJob = job
        job.invokeOnCompletion { error ->
            if (error is CancellationException) {
                mutableState.update { it.copy(isWriting = false, isVerifying = false) }
            }
            operationJob = null
        }
    }

    private fun applyScannedEpc(normalized: String) {
        mutableState.update { state ->
            val updated =
                state.copy(
                    isReading = false,
                    isScanningQr = false,
                    scannedEpc = normalized,
                    confirmation = null,
                    message = null,
                    errorMessage = null,
                )
            updated.copy(status = evaluateStatus(updated.expectedEpc, updated.scannedEpc))
        }
        viewModelScope.launch {
            logAction(
                actionType = RepairActionType.VERIFY_WRITE_SCAN,
                expectedEpc = normalizedExpectedOrNull(uiState.value.expectedEpc),
                currentEpc = normalized,
                result = RepairActionResult.SUCCESS,
                message = null,
            )
        }
    }

    private fun handleScanFailure(message: String) {
        mutableState.update { it.copy(isReading = false, isScanningQr = false, message = null, errorMessage = message) }
        viewModelScope.launch {
            logAction(
                actionType = RepairActionType.VERIFY_WRITE_SCAN,
                expectedEpc = normalizedExpectedOrNull(uiState.value.expectedEpc),
                currentEpc = null,
                result = RepairActionResult.FAILURE,
                message = message,
            )
        }
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

    private fun evaluateStatus(
        expectedEpc: String,
        scannedEpc: String?,
    ): VerifyWriteStatus {
        if (expectedEpc.isBlank()) {
            return VerifyWriteStatus.Invalid("Expected EPC is required.")
        }
        val normalizedExpected =
            runCatching { EpcNormalizer.normalize(expectedEpc) }.getOrElse { error ->
                return VerifyWriteStatus.Invalid(error.message ?: "Expected EPC is invalid.")
            }
        if (scannedEpc.isNullOrBlank()) {
            return VerifyWriteStatus.NotScanned(expectedEpc = normalizedExpected)
        }
        val normalizedScanned =
            runCatching { EpcNormalizer.normalize(scannedEpc) }.getOrElse { error ->
                return VerifyWriteStatus.Invalid(error.message ?: "Scanned EPC is invalid.")
            }
        return if (normalizedExpected == normalizedScanned) {
            VerifyWriteStatus.Ok(expectedEpc = normalizedExpected)
        } else {
            VerifyWriteStatus.Mismatch(expectedEpc = normalizedExpected, scannedEpc = normalizedScanned)
        }
    }

    private fun normalizedExpectedOrNull(expectedEpc: String): String? {
        return runCatching { EpcNormalizer.normalize(expectedEpc) }.getOrNull()
    }

    private fun handleWriteFailure(
        error: Throwable?,
        overrideMessage: String? = null,
    ) {
        val friendly = overrideMessage ?: mapWriteError(error)
        mutableState.update { it.copy(isWriting = false, isVerifying = false, errorMessage = friendly) }
        viewModelScope.launch {
            logAction(
                actionType = RepairActionType.REPAIR_WRITE_FAILED,
                expectedEpc = normalizedExpectedOrNull(uiState.value.expectedEpc),
                currentEpc = uiState.value.scannedEpc,
                result = RepairActionResult.FAILURE,
                message = error?.message ?: overrideMessage,
            )
        }
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

    private fun mapScanError(error: Throwable?): String {
        val scanError = (error as? Scan2dException)?.error
        return when (scanError) {
            Scan2dError.Timeout -> "QR scan timed out."
            Scan2dError.Cancelled -> "QR scan cancelled."
            Scan2dError.OperationInProgress -> "Scanner is busy."
            Scan2dError.HardwareUnavailable -> "QR scanner unavailable."
            is Scan2dError.InvalidPayload -> scanError.message
            is Scan2dError.VendorError -> scanError.message
            null -> error?.message ?: "Unknown QR scan error."
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

    private fun isBusy(state: RepairUiState): Boolean {
        return state.isReading || state.isScanningQr || state.isWriting || state.isVerifying
    }

    private companion object {
        const val READ_TIMEOUT_MS = 4_000L
        const val WRITE_TIMEOUT_MS = 7_000L
        const val VERIFY_TIMEOUT_MS = 7_000L
        const val LOG_LIMIT = 20
    }
}

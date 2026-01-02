package com.alexbomber12.memtag.ui.screens.repair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexbomber12.memtag.app.SessionFlagsStore
import com.alexbomber12.memtag.data.settings.SelectedLookupCard
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.alexbomber12.memtag.db.ActionsLogDao
import com.alexbomber12.memtag.db.ActionsLogEntity
import com.alexbomber12.memtag.domain.repair.RepairActionResult
import com.alexbomber12.memtag.domain.repair.RepairActionType
import com.alexbomber12.memtag.integrations.scan2d.Scan2dError
import com.alexbomber12.memtag.integrations.scan2d.Scan2dException
import com.alexbomber12.memtag.integrations.scan2d.Scan2dScanner
import com.alexbomber12.memtag.integrations.scan2d.asException
import com.alexbomber12.memtag.integrations.uhf.UhfApplyResult
import com.alexbomber12.memtag.integrations.uhf.UhfError
import com.alexbomber12.memtag.integrations.uhf.UhfException
import com.alexbomber12.memtag.integrations.uhf.UhfLogger
import com.alexbomber12.memtag.integrations.uhf.UhfReader
import com.alexbomber12.memtag.integrations.uhf.asException
import com.alexbomber12.memtag.integrations.uhf.toErrorMessage
import com.alexbomber12.memtag.util.epc.EpcNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val EXPECTED_MISSING_MESSAGE = "Select a card in Lookup or paste an EPC."

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

data class WriteConfirmation(
    val expectedEpc: String,
    val scannedEpc: String,
)

data class RepairUiState(
    val expectedEpc: String = "",
    val scannedEpc: String? = null,
    val selectedLookup: SelectedLookupCard? = null,
    val status: VerifyWriteStatus = VerifyWriteStatus.Invalid(EXPECTED_MISSING_MESSAGE),
    val isReading: Boolean = false,
    val isScanningQr: Boolean = false,
    val isWriting: Boolean = false,
    val isVerifying: Boolean = false,
    val confirmation: WriteConfirmation? = null,
    val message: String? = null,
    val errorMessage: String? = null,
)

class RepairViewModel(
    private val uhfReader: UhfReader,
    private val scan2dScanner: Scan2dScanner,
    private val actionsLogDao: ActionsLogDao,
    private val settingsStore: SettingsStore,
    private val sessionFlagsStore: SessionFlagsStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(RepairUiState())
    val uiState: StateFlow<RepairUiState> = mutableState

    private var operationJob: Job? = null

    init {
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                updateStateWithStatus { state ->
                    val selectedLookup = settings.selectedLookupCardOrNull()
                    val updated = applySelectionIfNeeded(state, selectedLookup)
                    val shouldClearFeedback = updated.expectedEpc != state.expectedEpc
                    updated.copy(
                        confirmation = if (shouldClearFeedback) null else state.confirmation,
                        message = if (shouldClearFeedback) null else state.message,
                        errorMessage = if (shouldClearFeedback) null else state.errorMessage,
                    )
                }
            }
        }
    }

    fun onScreenOpened() {
        updateStateWithStatus { state ->
            val updated = applySelectionIfNeeded(state, state.selectedLookup)
            updated.copy(
                scannedEpc = null,
                confirmation = null,
                message = null,
                errorMessage = null,
            )
        }
    }

    fun onExpectedEpcChange(value: String) {
        updateStateWithStatus { state ->
            state.copy(
                expectedEpc = value,
                confirmation = null,
                message = null,
                errorMessage = null,
            )
        }
    }

    fun pasteExpectedEpc(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return
        }
        val normalized = runCatching { EpcNormalizer.normalize(trimmed) }.getOrElse { trimmed }
        updateStateWithStatus { state ->
            state.copy(
                expectedEpc = normalized,
                confirmation = null,
                message = null,
                errorMessage = null,
            )
        }
    }

    fun scanRfid() {
        val state = uiState.value
        if (operationJob != null || state.confirmation != null || isBusy(state)) {
            if (state.isReading || state.isScanningQr || state.isWriting || state.isVerifying) {
                UhfLogger.i("verifyWrite scan ignored: already busy")
            }
            return
        }
        mutableState.update { it.copy(isReading = true, isScanningQr = false, message = null, errorMessage = null) }
        sessionFlagsStore.setVerifyRunning(true)
        val job =
            viewModelScope.launch {
                try {
                    logDebug("scan start")
                    val readyResult = ensureUhfReady("verify-write-scan")
                    if (readyResult.isFailure) {
                        logDebug(
                            "scan init/config failed: " +
                                (readyResult.exceptionOrNull()?.message ?: "unknown"),
                        )
                        handleScanFailure(mapUhfError(readyResult.exceptionOrNull()))
                        return@launch
                    }
                    val readResult =
                        readSingleWithRetries(READ_TIMEOUT_MS, READ_RETRY_ATTEMPTS, "verify-write-scan")
                    if (readResult.isFailure) {
                        logDebug(
                            "scan read failed: " +
                                (readResult.exceptionOrNull()?.message ?: "unknown"),
                        )
                        handleScanFailure(mapUhfError(readResult.exceptionOrNull()))
                        return@launch
                    }
                    val normalized =
                        runCatching { EpcNormalizer.normalize(readResult.getOrNull().orEmpty()) }.getOrElse { error ->
                            handleScanFailure(error.message ?: "Invalid EPC read from tag.")
                            return@launch
                        }
                    logDebug("scan success epc=$normalized")
                    applyScannedEpc(normalized)
                } finally {
                    clearScanFlags()
                    sessionFlagsStore.setVerifyRunning(false)
                }
            }
        operationJob = job
        job.invokeOnCompletion {
            operationJob = null
        }
    }

    fun scanQr() {
        val state = uiState.value
        if (operationJob != null || state.confirmation != null || isBusy(state)) {
            if (state.isReading || state.isScanningQr || state.isWriting || state.isVerifying) {
                UhfLogger.i("verifyWrite scan ignored: already busy")
            }
            return
        }
        mutableState.update { it.copy(isScanningQr = true, isReading = false, message = null, errorMessage = null) }
        sessionFlagsStore.setVerifyRunning(true)
        val job =
            viewModelScope.launch {
                try {
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
                } finally {
                    clearScanFlags()
                    sessionFlagsStore.setVerifyRunning(false)
                }
            }
        operationJob = job
        job.invokeOnCompletion {
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
            is VerifyWriteStatus.Mismatch -> {
                mutableState.update {
                    it.copy(
                        confirmation =
                            WriteConfirmation(
                                expectedEpc = status.expectedEpc,
                                scannedEpc = status.scannedEpc,
                            ),
                        message = null,
                        errorMessage = null,
                    )
                }
            }
            is VerifyWriteStatus.NotScanned -> {
                performWrite(status.expectedEpc)
            }
            else -> Unit
        }
    }

    fun dismissConfirmation() {
        mutableState.update { it.copy(confirmation = null) }
    }

    fun confirmWrite() {
        val confirmation = uiState.value.confirmation ?: return
        if (operationJob != null || isBusy(uiState.value)) {
            return
        }
        mutableState.update { it.copy(confirmation = null) }
        performWrite(confirmation.expectedEpc)
    }

    fun cancelOperations() {
        sessionFlagsStore.setVerifyRunning(false)
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
        sessionFlagsStore.setVerifyRunning(true)
        val job =
            viewModelScope.launch {
                try {
                    mutableState.update {
                        it.copy(isWriting = true, isVerifying = false, message = null, errorMessage = null)
                    }
                    logAction(
                        actionType = RepairActionType.REPAIR_WRITE_ATTEMPT,
                        expectedEpc = expectedEpc,
                        currentEpc = uiState.value.scannedEpc,
                        result = RepairActionResult.SUCCESS,
                        message = null,
                    )
                    logDebug(
                        "write start expected=$expectedEpc scanned=${uiState.value.scannedEpc ?: "--"}",
                    )
                    val readyResult = ensureUhfReady("verify-write")
                    if (readyResult.isFailure) {
                        handleWriteFailure(readyResult.exceptionOrNull())
                        return@launch
                    }
                    val resolvedTarget =
                        uiState.value.scannedEpc?.takeIf { it.isNotBlank() }
                            ?: run {
                                val readResult =
                                    readSingleWithRetries(READ_TIMEOUT_MS, READ_RETRY_ATTEMPTS, "verify-write-read")
                                if (readResult.isFailure) {
                                    handleWriteFailure(readResult.exceptionOrNull())
                                    return@launch
                                }
                                val readEpc = readResult.getOrNull().orEmpty()
                                if (readEpc.isBlank()) {
                                    handleWriteFailure(IllegalStateException("Unable to resolve target EPC."))
                                    return@launch
                                }
                                updateStateWithStatus { state ->
                                    state.copy(scannedEpc = readEpc)
                                }
                                readEpc
                            }
                    val writeResult = uhfReader.writeEpc(expectedEpc, resolvedTarget, WRITE_TIMEOUT_MS)
                    if (writeResult.isFailure) {
                        handleWriteFailure(writeResult.exceptionOrNull())
                        return@launch
                    }
                    mutableState.update { it.copy(isWriting = false, isVerifying = true) }
                    val verifyResult =
                        verifyEpcWithRetries(expectedEpc, VERIFY_TIMEOUT_MS, VERIFY_RETRY_ATTEMPTS, "verify-write-verify")
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
                    logDebug("write verified expected=$expectedEpc")
                    updateStateWithStatus { state ->
                        state.copy(
                            isWriting = false,
                            isVerifying = false,
                            scannedEpc = expectedEpc,
                            message = "Write verified.",
                            errorMessage = null,
                        )
                    }
                    logAction(
                        actionType = RepairActionType.REPAIR_WRITE_SUCCESS,
                        expectedEpc = expectedEpc,
                        currentEpc = expectedEpc,
                        result = RepairActionResult.SUCCESS,
                        message = null,
                    )
                } finally {
                    sessionFlagsStore.setVerifyRunning(false)
                }
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
        updateStateWithStatus { state ->
            state.copy(
                isReading = false,
                isScanningQr = false,
                scannedEpc = normalized,
                confirmation = null,
                message = null,
                errorMessage = null,
            )
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
    }

    private fun updateStateWithStatus(transform: (RepairUiState) -> RepairUiState) {
        var snapshot: RepairUiState? = null
        mutableState.update { state ->
            val updated = transform(state)
            val withStatus = updated.copy(status = evaluateStatus(updated.expectedEpc, updated.scannedEpc))
            snapshot = withStatus
            withStatus
        }
        snapshot?.let { logComparison(it) }
    }

    private fun clearScanFlags() {
        mutableState.update { state ->
            if (!state.isReading && !state.isScanningQr) {
                state
            } else {
                state.copy(isReading = false, isScanningQr = false)
            }
        }
    }

    private fun logComparison(state: RepairUiState) {
        val expectedLabel = state.expectedEpc.ifBlank { "--" }
        val scannedLabel = state.scannedEpc?.ifBlank { "--" } ?: "--"
        val isMatch = state.status is VerifyWriteStatus.Ok
        UhfLogger.i(
            "verifyWrite expected=$expectedLabel scanned=$scannedLabel match=$isMatch",
        )
    }

    private fun evaluateStatus(
        expectedEpc: String,
        scannedEpc: String?,
    ): VerifyWriteStatus {
        if (expectedEpc.isBlank()) {
            return VerifyWriteStatus.Invalid(EXPECTED_MISSING_MESSAGE)
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

    private suspend fun ensureUhfReady(reason: String): Result<Unit> {
        val initResult = uhfReader.initialize()
        if (initResult.isFailure) {
            logDebug("$reason init failed: ${initResult.exceptionOrNull()?.message ?: "unknown"}")
            return Result.failure(initResult.exceptionOrNull() ?: IllegalStateException("UHF init failed."))
        }
        stopInventoryBestEffort("$reason-stop")
        val applyResult = applyConfigWithRetry(reason)
        if (applyResult.isFailure) {
            logDebug("$reason apply failed: ${applyResult.exceptionOrNull()?.message ?: "unknown"}")
            return Result.failure(
                applyResult.exceptionOrNull() ?: IllegalStateException("UHF config apply failed."),
            )
        }
        val applied = applyResult.getOrNull()
        if (applied != null && !applied.success) {
            UhfLogger.w("verifyWrite config apply incomplete: ${applied.toErrorMessage()}")
        }
        return Result.success(Unit)
    }

    private suspend fun applyConfigWithRetry(reason: String): Result<UhfApplyResult> {
        var result = uhfReader.applyDesiredConfigBestEffort(reason)
        if (result.isSuccess || !isUhfBusy(result.exceptionOrNull())) {
            return result
        }
        logDebug("$reason apply retry after busy")
        stopInventoryBestEffort("$reason-apply-retry")
        delay(CONFIG_RETRY_DELAY_MS)
        result = uhfReader.applyDesiredConfigBestEffort("$reason-retry")
        return result
    }

    private suspend fun readSingleWithRetries(
        timeoutMs: Long,
        attempts: Int,
        reason: String,
    ): Result<String> {
        var lastError: Throwable? = null
        repeat(attempts) { attempt ->
            logDebug("$reason read attempt=${attempt + 1} timeoutMs=$timeoutMs")
            if (attempt > 0) {
                stopInventoryBestEffort("$reason-read-retry")
                delay(READ_RETRY_DELAY_MS)
            }
            val result = uhfReader.readSingle(timeoutMs)
            if (result.isSuccess) {
                return result
            }
            lastError = result.exceptionOrNull()
            if (!isRetryableReadError(lastError)) {
                return result
            }
            logDebug("$reason read retry failed: ${lastError?.message ?: "unknown"}")
        }
        return Result.failure(lastError ?: UhfError.Timeout.asException())
    }

    private suspend fun verifyEpcWithRetries(
        expectedEpc: String,
        timeoutMs: Long,
        attempts: Int,
        reason: String,
    ): Result<Boolean> {
        var lastError: Throwable? = null
        repeat(attempts) { attempt ->
            logDebug("$reason verify attempt=${attempt + 1} timeoutMs=$timeoutMs")
            if (attempt > 0) {
                stopInventoryBestEffort("$reason-verify-retry")
                delay(VERIFY_RETRY_DELAY_MS)
            }
            val result = uhfReader.verifyEpc(expectedEpc, timeoutMs)
            if (result.isSuccess) {
                return result
            }
            lastError = result.exceptionOrNull()
            if (!isRetryableReadError(lastError)) {
                return result
            }
            logDebug("$reason verify retry failed: ${lastError?.message ?: "unknown"}")
        }
        return Result.failure(lastError ?: UhfError.Timeout.asException())
    }

    private suspend fun stopInventoryBestEffort(reason: String) {
        val stopResult = uhfReader.stopInventory()
        if (stopResult.isFailure) {
            logDebug("$reason stopInventory failed: ${stopResult.exceptionOrNull()?.message ?: "unknown"}")
        }
    }

    private fun isRetryableReadError(error: Throwable?): Boolean {
        val uhfError = (error as? UhfException)?.error
        return uhfError == UhfError.Timeout || uhfError == UhfError.OperationInProgress
    }

    private fun isUhfBusy(error: Throwable?): Boolean {
        return (error as? UhfException)?.error == UhfError.OperationInProgress
    }

    private fun logDebug(message: String) {
        UhfLogger.debugInfo("verifyWrite $message")
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

    private fun applySelectionIfNeeded(
        state: RepairUiState,
        selectedLookup: SelectedLookupCard?,
    ): RepairUiState {
        val selectedEpc = selectedLookup?.epc?.takeIf { it.isNotBlank() }
        val previousEpc = state.selectedLookup?.epc?.takeIf { it.isNotBlank() }
        val selectionChanged = selectedEpc != null && selectedEpc != previousEpc
        return if (selectionChanged) {
            state.copy(
                expectedEpc = selectedEpc,
                selectedLookup = selectedLookup,
            )
        } else {
            state.copy(selectedLookup = selectedLookup)
        }
    }

    private fun isBusy(state: RepairUiState): Boolean {
        return state.isReading || state.isScanningQr || state.isWriting || state.isVerifying
    }

    private companion object {
        const val READ_TIMEOUT_MS = 4_000L
        const val WRITE_TIMEOUT_MS = 7_000L
        const val VERIFY_TIMEOUT_MS = 7_000L
        const val READ_RETRY_ATTEMPTS = 2
        const val VERIFY_RETRY_ATTEMPTS = 2
        const val READ_RETRY_DELAY_MS = 150L
        const val VERIFY_RETRY_DELAY_MS = 150L
        const val CONFIG_RETRY_DELAY_MS = 150L
    }
}

internal fun canWriteExpectedEpc(
    expectedEpc: String,
    scannedEpc: String?,
    isWriting: Boolean,
): Boolean {
    if (isWriting || expectedEpc.isBlank()) {
        return false
    }
    return scannedEpc.isNullOrBlank() || scannedEpc != expectedEpc
}

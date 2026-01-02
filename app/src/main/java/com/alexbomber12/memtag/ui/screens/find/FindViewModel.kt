package com.alexbomber12.memtag.ui.screens.find

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexbomber12.memtag.app.SessionFlagsStore
import com.alexbomber12.memtag.data.AppDefaults
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.alexbomber12.memtag.domain.find.ProximityCalculator
import com.alexbomber12.memtag.domain.find.ProximitySnapshot
import com.alexbomber12.memtag.integrations.feedback.FindFeedbackController
import com.alexbomber12.memtag.integrations.uhf.TagReading
import com.alexbomber12.memtag.integrations.uhf.UhfError
import com.alexbomber12.memtag.integrations.uhf.UhfException
import com.alexbomber12.memtag.integrations.uhf.UhfLogger
import com.alexbomber12.memtag.integrations.uhf.UhfReader
import com.alexbomber12.memtag.integrations.uhf.asException
import com.alexbomber12.memtag.integrations.uhf.toErrorMessage
import com.alexbomber12.memtag.util.epc.EpcNormalizer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class FindStatus {
    data object Idle : FindStatus()

    data object Running : FindStatus()

    data object NoSignal : FindStatus()

    data class Error(
        val message: String,
    ) : FindStatus()
}

enum class MatchStatus {
    NoTarget,
    Matched,
    NotMatchedYet,
}

data class FindUiState(
    val epcInput: String = "",
    val targetEpcNormalized: String? = null,
    val lastScannedEpc: String = "",
    val isRunning: Boolean = false,
    val status: FindStatus = FindStatus.Idle,
    val proximity: Int = 0,
    val rawProximity: Float = 0f,
    val smoothedProximity: Float = 0f,
    val hitsPerWindow: Int = 0,
    val lastRssi: Int? = null,
    val seenRecently: Boolean = false,
    val lastWakeUpAt: Long? = null,
    val lastWakeUpIdleMs: Long? = null,
    val lastSeenAnyEpc: String? = null,
    val lastSeenAnyRssi: Int? = null,
    val lastSeenAnyAt: Long? = null,
    val tagsSeenAny: Int = 0,
    val lastSeenMatchedEpc: String? = null,
    val lastSeenMatchedRssi: Int? = null,
    val tagsSeenMatched: Int = 0,
    val matchStatus: MatchStatus = MatchStatus.NoTarget,
    val soundEnabled: Boolean = AppDefaults.FIND_SOUND_ENABLED,
    val debugOverlayEnabled: Boolean = AppDefaults.FIND_DEBUG_OVERLAY_ENABLED,
    val uhfPower: Int = AppDefaults.UHF_POWER,
    val debugDisableFilter: Boolean = false,
    val lastErrorMessage: String? = null,
)

class FindViewModel(
    private val settingsStore: SettingsStore,
    private val uhfReader: UhfReader,
    private val feedbackController: FindFeedbackController,
    private val sessionFlagsStore: SessionFlagsStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(FindUiState())
    val uiState: StateFlow<FindUiState> = mutableState

    private var inventoryJob: Job? = null
    private var tickerJob: Job? = null
    private var feedbackJob: Job? = null
    private var reapplyProfileJob: Job? = null
    private var calculator: ProximityCalculator? = null
    private var autoStartConsumedForEpc: String? = null
    private var scanStartMs: Long? = null
    private var geigerLogCount: Int = 0

    init {
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                val wasDebugDisableFilter = uiState.value.debugDisableFilter
                val wasRunning = uiState.value.isRunning
                mutableState.update { state ->
                    val updatedTarget =
                        if (state.epcInput.isBlank() && settings.lastFindTargetEpc.isNotBlank()) {
                            settings.lastFindTargetEpc
                        } else {
                            state.epcInput
                        }
                    val targetNormalized = normalizeTargetEpc(updatedTarget)
                    val nextDebugDisableFilter =
                        if (settings.showFindDebugOverlay) {
                            state.debugDisableFilter
                        } else {
                            false
                        }
                    val updated =
                        state.copy(
                            epcInput = updatedTarget,
                            targetEpcNormalized = targetNormalized,
                            lastScannedEpc = settings.lastScannedEpc,
                            soundEnabled = settings.findSoundEnabled,
                            debugOverlayEnabled = settings.showFindDebugOverlay,
                            uhfPower = settings.uhfPower,
                            debugDisableFilter = nextDebugDisableFilter,
                        )
                    updated.copy(
                        status = computeStatus(updated),
                        matchStatus = computeMatchStatus(updated),
                    )
                }
                if (wasRunning && wasDebugDisableFilter && !settings.showFindDebugOverlay) {
                    refreshCalculator()
                    reapplyFindProfile()
                }
            }
        }
    }

    fun onEpcInputChange(value: String) {
        mutableState.update { state ->
            val targetNormalized = normalizeTargetEpc(value)
            val updated =
                state.copy(
                    epcInput = value,
                    targetEpcNormalized = targetNormalized,
                    lastErrorMessage = null,
                    lastSeenMatchedEpc = null,
                    lastSeenMatchedRssi = null,
                    tagsSeenMatched = 0,
                )
            updated.copy(
                status = computeStatus(updated),
                matchStatus = computeMatchStatus(updated),
            )
        }
        persistFindTarget(value)
    }

    fun applyExternalEpc(
        epcRaw: String,
        autoStart: Boolean,
    ) {
        if (epcRaw.isBlank()) {
            return
        }
        val wasRunning = uiState.value.isRunning
        val normalized = canonicalizeEpc(epcRaw) ?: return
        val shouldUpdate = uiState.value.epcInput != normalized
        if (shouldUpdate) {
            mutableState.update { state ->
                val updated =
                    state.copy(
                        epcInput = normalized,
                        targetEpcNormalized = normalized,
                        lastErrorMessage = null,
                        lastSeenMatchedEpc = null,
                        lastSeenMatchedRssi = null,
                        tagsSeenMatched = 0,
                    )
                updated.copy(
                    status = computeStatus(updated),
                    matchStatus = computeMatchStatus(updated),
                )
            }
            persistFindTarget(normalized)
        }
        if (shouldUpdate && wasRunning) {
            refreshCalculator()
            reapplyFindProfile()
        }
        if (autoStart && autoStartConsumedForEpc != normalized && !uiState.value.isRunning) {
            autoStartConsumedForEpc = normalized
            startFind()
        }
    }

    fun useLastScannedEpc() {
        val last = uiState.value.lastScannedEpc
        if (last.isBlank()) {
            return
        }
        val normalized = normalizeTargetEpc(last) ?: return
        mutableState.update { state ->
            val updated =
                state.copy(
                    epcInput = normalized,
                    targetEpcNormalized = normalized,
                    lastErrorMessage = null,
                    lastSeenMatchedEpc = null,
                    lastSeenMatchedRssi = null,
                    tagsSeenMatched = 0,
                )
            updated.copy(
                status = computeStatus(updated),
                matchStatus = computeMatchStatus(updated),
            )
        }
        persistFindTarget(normalized)
    }

    fun setDebugDisableFilter(enabled: Boolean) {
        mutableState.update { state ->
            val updated = state.copy(debugDisableFilter = enabled)
            updated.copy(matchStatus = computeMatchStatus(updated))
        }
        if (uiState.value.isRunning) {
            refreshCalculator()
            reapplyFindProfile()
        }
    }

    fun setTargetFromLastSeenAny() {
        val candidate = uiState.value.lastSeenAnyEpc ?: return
        val normalized = normalizeTargetEpc(candidate) ?: return
        mutableState.update { state ->
            val updated =
                state.copy(
                    epcInput = normalized,
                    targetEpcNormalized = normalized,
                    lastErrorMessage = null,
                    lastSeenMatchedEpc = null,
                    lastSeenMatchedRssi = null,
                    tagsSeenMatched = 0,
                )
            updated.copy(
                status = computeStatus(updated),
                matchStatus = computeMatchStatus(updated),
            )
        }
        persistFindTarget(normalized)
        if (uiState.value.isRunning) {
            refreshCalculator()
            reapplyFindProfile()
        }
    }

    fun clearError() {
        mutableState.update { state ->
            val updated = state.copy(lastErrorMessage = null)
            updated.copy(status = computeStatus(updated))
        }
    }

    fun startFind() {
        if (inventoryJob != null) {
            return
        }
        val epcRaw = uiState.value.epcInput
        val targetNormalized = normalizeTargetEpc(epcRaw)
        if (epcRaw.isNotBlank() && targetNormalized == null) {
            setError("Invalid EPC. Use 8-64 hex characters.")
            return
        }
        val matchAll = targetNormalized == null || uiState.value.debugDisableFilter
        calculator = ProximityCalculator(targetNormalized.orEmpty(), matchAll = matchAll)
        geigerLogCount = 0
        mutableState.update { state ->
            val updated =
                state.copy(
                    epcInput = targetNormalized ?: epcRaw,
                    targetEpcNormalized = targetNormalized,
                    isRunning = true,
                    lastErrorMessage = null,
                    proximity = 0,
                    rawProximity = 0f,
                    smoothedProximity = 0f,
                    hitsPerWindow = 0,
                    lastRssi = null,
                    seenRecently = false,
                    lastWakeUpAt = null,
                    lastWakeUpIdleMs = null,
                    lastSeenAnyEpc = null,
                    lastSeenAnyRssi = null,
                    lastSeenAnyAt = null,
                    tagsSeenAny = 0,
                    lastSeenMatchedEpc = null,
                    lastSeenMatchedRssi = null,
                    tagsSeenMatched = 0,
                    status = FindStatus.Running,
                )
            updated.copy(matchStatus = computeMatchStatus(updated))
        }
        sessionFlagsStore.setFindRunning(true)
        scanStartMs = System.currentTimeMillis()
        UhfLogger.i("ScanRFID start (screen=find source=inventory usedMethod=inventory)")
        startTicker()
        startFeedback()
        val job =
            viewModelScope.launch {
                try {
                    val initResult = uhfReader.initialize()
                    if (initResult.isFailure) {
                        handleInventoryError(initResult.exceptionOrNull())
                        return@launch
                    }
                    uhfReader.stopInventory()
                    val applyResult = uhfReader.applyDesiredConfigBestEffort("find-inventory")
                    if (applyResult.isFailure) {
                        handleInventoryError(applyResult.exceptionOrNull())
                        return@launch
                    }
                    val applied = applyResult.getOrNull()
                    if (applied != null && !applied.success) {
                        handleInventoryError(UhfError.VendorError(applied.toErrorMessage()).asException())
                        return@launch
                    }
                    val targetEpc = targetNormalized
                    val useHardwareFilter = targetEpc != null && !uiState.value.debugDisableFilter
                    val findProfileResult = uhfReader.applyFindProfile(targetEpc, useHardwareFilter)
                    if (findProfileResult.isFailure) {
                        handleInventoryError(findProfileResult.exceptionOrNull())
                        return@launch
                    }
                    uhfReader
                        .startInventory(filterEpcHex = null)
                        .catch { error -> handleInventoryError(error) }
                        .collect { reading ->
                            val rawEpc = reading.rawEpc ?: reading.epcHex
                            val normalizedEpc = canonicalizeEpc(rawEpc) ?: canonicalizeEpc(reading.epcHex)
                            val rssi = reading.rssi
                            val timestampMs = reading.timestampMs
                            val stateSnapshot = uiState.value
                            val target = stateSnapshot.targetEpcNormalized
                            val matched = normalizedEpc != null && target != null && normalizedEpc == target
                            if (normalizedEpc != null) {
                                mutableState.update { state ->
                                    val updated =
                                        state.copy(
                                            lastSeenAnyEpc = normalizedEpc,
                                            lastSeenAnyRssi = rssi,
                                            lastSeenAnyAt = timestampMs,
                                            tagsSeenAny = state.tagsSeenAny + 1,
                                            lastSeenMatchedEpc =
                                                if (matched) normalizedEpc else state.lastSeenMatchedEpc,
                                            lastSeenMatchedRssi =
                                                if (matched) rssi else state.lastSeenMatchedRssi,
                                            tagsSeenMatched =
                                                if (matched) state.tagsSeenMatched + 1 else state.tagsSeenMatched,
                                        )
                                    updated.copy(matchStatus = computeMatchStatus(updated))
                                }
                            }
                            val matchAllNow = stateSnapshot.debugDisableFilter || target == null
                            if (normalizedEpc != null && (matchAllNow || matched)) {
                                calculator?.onReading(
                                    TagReading(
                                        epcHex = normalizedEpc,
                                        rssi = rssi,
                                        timestampMs = timestampMs,
                                        rawEpc = rawEpc,
                                    ),
                                )
                            }
                            if (geigerLogCount < GEIGER_LOG_LIMIT) {
                                val normalizedLabel = normalizedEpc ?: "--"
                                val rssiLabel = rssi?.toString() ?: "--"
                                UhfLogger.i(
                                    "geiger tag raw=${rawEpc.ifBlank { "--" }} normalized=$normalizedLabel " +
                                        "rssi=$rssiLabel matched=$matched",
                                )
                                geigerLogCount += 1
                            }
                        }
                } finally {
                    uhfReader.stopInventory()
                    uhfReader.clearFindProfile()
                    sessionFlagsStore.setFindRunning(false)
                }
            }
        inventoryJob = job
        job.invokeOnCompletion {
            inventoryJob = null
        }
    }

    fun toggleFind() {
        if (uiState.value.isRunning) {
            stopFind()
        } else {
            startFind()
        }
    }

    fun stopFind() {
        sessionFlagsStore.setFindRunning(false)
        inventoryJob?.cancel()
        inventoryJob = null
        reapplyProfileJob?.cancel()
        reapplyProfileJob = null
        stopTickerAndFeedback()
        viewModelScope.launch {
            uhfReader.stopInventory()
            uhfReader.clearFindProfile()
        }
        calculator?.reset()
        mutableState.update { state ->
            val updated =
                state.copy(
                    isRunning = false,
                    status = FindStatus.Idle,
                    lastErrorMessage = null,
                    proximity = 0,
                    rawProximity = 0f,
                    smoothedProximity = 0f,
                    hitsPerWindow = 0,
                    lastRssi = null,
                    seenRecently = false,
                    lastWakeUpAt = null,
                    lastWakeUpIdleMs = null,
                    lastSeenAnyEpc = null,
                    lastSeenAnyRssi = null,
                    lastSeenAnyAt = null,
                    tagsSeenAny = 0,
                    lastSeenMatchedEpc = null,
                    lastSeenMatchedRssi = null,
                    tagsSeenMatched = 0,
                )
            updated.copy(matchStatus = computeMatchStatus(updated))
        }
        logScanEnd("stopped")
    }

    override fun onCleared() {
        stopFind()
        feedbackController.release()
        super.onCleared()
    }

    private fun startTicker() {
        if (tickerJob != null) {
            return
        }
        tickerJob =
            viewModelScope.launch {
                while (true) {
                    val snapshot = calculator?.onTick(clock()) ?: break
                    applySnapshot(snapshot)
                    delay(UI_UPDATE_INTERVAL_MS)
                }
            }
    }

    private fun startFeedback() {
        if (feedbackJob != null) {
            return
        }
        feedbackJob =
            viewModelScope.launch {
                while (true) {
                    val state = uiState.value
                    if (!state.isRunning) {
                        break
                    }
                    val interval = feedbackIntervalMs(state.proximity)
                    if (interval == null || !state.soundEnabled) {
                        delay(FEEDBACK_IDLE_POLL_MS)
                        continue
                    }
                    if (state.soundEnabled) {
                        feedbackController.playSound()
                    }
                    delay(interval)
                }
            }
    }

    private fun stopTickerAndFeedback() {
        tickerJob?.cancel()
        tickerJob = null
        feedbackJob?.cancel()
        feedbackJob = null
    }

    private fun applySnapshot(snapshot: ProximitySnapshot) {
        mutableState.update { state ->
            val status = computeStatus(state.copy(seenRecently = snapshot.seenRecently))
            state.copy(
                proximity = snapshot.proximity,
                rawProximity = snapshot.rawScore,
                smoothedProximity = snapshot.smoothedScore,
                hitsPerWindow = snapshot.hitsPerWindow,
                lastRssi = snapshot.rssi,
                seenRecently = snapshot.seenRecently,
                lastWakeUpAt = snapshot.lastWakeUpAt,
                lastWakeUpIdleMs = snapshot.lastWakeUpIdleMs,
                status = status,
            )
        }
    }

    private suspend fun handleInventoryError(error: Throwable?) {
        stopTickerAndFeedback()
        val message = mapError(error)
        mutableState.update {
            it.copy(
                isRunning = false,
                lastErrorMessage = message,
                status = FindStatus.Error(message),
            )
        }
        sessionFlagsStore.setFindRunning(false)
        uhfReader.stopInventory()
        logScanEnd("error")
    }

    private fun setError(message: String) {
        mutableState.update {
            it.copy(
                isRunning = false,
                lastErrorMessage = message,
                status = FindStatus.Error(message),
            )
        }
    }

    private fun normalizeTargetEpc(value: String): String? {
        return canonicalizeEpc(value)
    }

    private fun canonicalizeEpc(value: String?): String? {
        val candidate = value?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { EpcNormalizer.normalize(candidate) }.getOrNull()
    }

    private fun computeMatchStatus(state: FindUiState): MatchStatus {
        return when {
            state.targetEpcNormalized.isNullOrBlank() -> MatchStatus.NoTarget
            state.tagsSeenMatched > 0 -> MatchStatus.Matched
            else -> MatchStatus.NotMatchedYet
        }
    }

    private fun refreshCalculator() {
        val target = uiState.value.targetEpcNormalized
        val matchAll = target == null || uiState.value.debugDisableFilter
        calculator = ProximityCalculator(target.orEmpty(), matchAll = matchAll)
    }

    private fun reapplyFindProfile() {
        val snapshot = uiState.value
        if (!snapshot.isRunning) {
            return
        }
        reapplyProfileJob?.cancel()
        reapplyProfileJob =
            viewModelScope.launch {
                val state = uiState.value
                if (!state.isRunning) {
                    return@launch
                }
                val target = state.targetEpcNormalized
                val useHardwareFilter = target != null && !state.debugDisableFilter
                uhfReader.applyFindProfile(target, useHardwareFilter)
            }
    }

    private fun computeStatus(state: FindUiState): FindStatus {
        val error = state.lastErrorMessage
        return when {
            error != null -> FindStatus.Error(error)
            !state.isRunning -> FindStatus.Idle
            !state.seenRecently -> FindStatus.NoSignal
            else -> FindStatus.Running
        }
    }

    private fun mapError(error: Throwable?): String {
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

    private fun logScanEnd(result: String) {
        val startedAt = scanStartMs ?: return
        val durationMs = System.currentTimeMillis() - startedAt
        UhfLogger.i("ScanRFID end (screen=find result=$result durationMs=$durationMs)")
        scanStartMs = null
    }

    private fun feedbackIntervalMs(proximity: Int): Long? {
        return when {
            proximity < 10 -> null
            proximity < 40 -> 800L
            proximity < 70 -> 350L
            else -> 150L
        }
    }

    private fun persistFindTarget(value: String) {
        viewModelScope.launch {
            val normalized = runCatching { EpcNormalizer.normalize(value) }.getOrNull().orEmpty()
            settingsStore.update {
                it.copy(
                    lastFindTargetEpc = normalized,
                    lastFindTargetEpcAt = clock(),
                )
            }
        }
    }

    private companion object {
        const val UI_UPDATE_INTERVAL_MS = 100L
        const val FEEDBACK_IDLE_POLL_MS = 200L
        const val GEIGER_LOG_LIMIT = 5
    }
}

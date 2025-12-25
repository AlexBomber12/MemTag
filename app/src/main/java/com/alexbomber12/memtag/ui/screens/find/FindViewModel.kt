package com.alexbomber12.memtag.ui.screens.find

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexbomber12.memtag.data.AppDefaults
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.alexbomber12.memtag.domain.find.ProximityCalculator
import com.alexbomber12.memtag.domain.find.ProximitySnapshot
import com.alexbomber12.memtag.integrations.feedback.FindFeedbackController
import com.alexbomber12.memtag.integrations.uhf.UhfError
import com.alexbomber12.memtag.integrations.uhf.UhfException
import com.alexbomber12.memtag.integrations.uhf.UhfReader
import com.alexbomber12.memtag.integrations.uhf.UhfRegion
import com.alexbomber12.memtag.util.epc.EpcNormalizer
import com.alexbomber12.memtag.util.epc.EpcValidator
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

data class FindUiState(
    val epcInput: String = "",
    val lastLookupEpc: String = "",
    val isRunning: Boolean = false,
    val status: FindStatus = FindStatus.Idle,
    val proximity: Int = 0,
    val rawProximity: Float = 0f,
    val smoothedProximity: Float = 0f,
    val hitsPerWindow: Int = 0,
    val lastRssi: Int? = null,
    val seenRecently: Boolean = false,
    val soundEnabled: Boolean = AppDefaults.FIND_SOUND_ENABLED,
    val hapticEnabled: Boolean = AppDefaults.FIND_HAPTIC_ENABLED,
    val lastErrorMessage: String? = null,
)

class FindViewModel(
    private val settingsStore: SettingsStore,
    private val uhfReader: UhfReader,
    private val feedbackController: FindFeedbackController,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(FindUiState())
    val uiState: StateFlow<FindUiState> = mutableState

    private var inventoryJob: Job? = null
    private var tickerJob: Job? = null
    private var feedbackJob: Job? = null
    private var calculator: ProximityCalculator? = null
    private var currentPower = AppDefaults.UHF_POWER
    private var currentRegion = UhfRegion.fromSettings(AppDefaults.UHF_REGION)

    init {
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                currentPower = settings.uhfPower
                currentRegion = UhfRegion.fromSettings(settings.uhfRegion)
                mutableState.update {
                    it.copy(
                        lastLookupEpc = settings.lastLookupEpc,
                        soundEnabled = settings.findSoundEnabled,
                        hapticEnabled = settings.findHapticEnabled,
                    )
                }
            }
        }
    }

    fun onEpcInputChange(value: String) {
        mutableState.update { state ->
            val updated = state.copy(epcInput = value, lastErrorMessage = null)
            updated.copy(status = computeStatus(updated))
        }
    }

    fun useLastLookupEpc() {
        val last = uiState.value.lastLookupEpc
        if (last.isBlank()) {
            return
        }
        mutableState.update { state ->
            val updated = state.copy(epcInput = last, lastErrorMessage = null)
            updated.copy(status = computeStatus(updated))
        }
    }

    fun setSoundEnabled(enabled: Boolean) {
        mutableState.update { it.copy(soundEnabled = enabled) }
        viewModelScope.launch {
            settingsStore.update { it.copy(findSoundEnabled = enabled) }
        }
    }

    fun setHapticEnabled(enabled: Boolean) {
        mutableState.update { it.copy(hapticEnabled = enabled) }
        viewModelScope.launch {
            settingsStore.update { it.copy(findHapticEnabled = enabled) }
        }
    }

    fun startFind() {
        if (inventoryJob != null) {
            return
        }
        val epcRaw = uiState.value.epcInput
        if (!EpcValidator.isValidEpcHex(epcRaw)) {
            setError("Invalid EPC. Use hex characters only.")
            return
        }
        val normalized =
            runCatching { EpcNormalizer.normalize(epcRaw) }.getOrElse {
                setError("Invalid EPC. Use hex characters only.")
                return
            }
        calculator = ProximityCalculator(normalized)
        mutableState.update {
            it.copy(
                epcInput = normalized,
                isRunning = true,
                lastErrorMessage = null,
                proximity = 0,
                rawProximity = 0f,
                smoothedProximity = 0f,
                hitsPerWindow = 0,
                lastRssi = null,
                seenRecently = false,
                status = FindStatus.Running,
            )
        }
        startTicker()
        startFeedback()
        val job =
            viewModelScope.launch {
                val initResult = uhfReader.initialize()
                if (initResult.isFailure) {
                    handleInventoryError(initResult.exceptionOrNull())
                    return@launch
                }
                if (!applySettingsToReader()) {
                    return@launch
                }
                uhfReader
                    .startInventory(filterEpcHex = normalized)
                    .catch { error -> handleInventoryError(error) }
                    .collect { reading ->
                        val readingEpc =
                            runCatching { EpcNormalizer.normalize(reading.epcHex) }.getOrNull()
                        if (readingEpc == normalized) {
                            calculator?.onReading(reading.copy(epcHex = readingEpc))
                        }
                    }
            }
        inventoryJob = job
        job.invokeOnCompletion {
            inventoryJob = null
        }
    }

    fun stopFind() {
        inventoryJob?.cancel()
        inventoryJob = null
        stopTickerAndFeedback()
        viewModelScope.launch {
            uhfReader.stopInventory()
        }
        calculator?.reset()
        mutableState.update {
            it.copy(
                isRunning = false,
                status = FindStatus.Idle,
                lastErrorMessage = null,
                proximity = 0,
                rawProximity = 0f,
                smoothedProximity = 0f,
                hitsPerWindow = 0,
                lastRssi = null,
                seenRecently = false,
            )
        }
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
                    if (interval == null || (!state.soundEnabled && !state.hapticEnabled)) {
                        delay(FEEDBACK_IDLE_POLL_MS)
                        continue
                    }
                    if (state.soundEnabled) {
                        feedbackController.playSound()
                    }
                    if (state.hapticEnabled) {
                        feedbackController.vibrate(HAPTIC_DURATION_MS)
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
        uhfReader.stopInventory()
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

    private suspend fun applySettingsToReader(): Boolean {
        val powerResult = uhfReader.setPower(currentPower)
        if (powerResult.isFailure) {
            handleInventoryError(powerResult.exceptionOrNull())
            return false
        }
        val regionResult = uhfReader.setRegion(currentRegion)
        if (regionResult.isFailure) {
            handleInventoryError(regionResult.exceptionOrNull())
            return false
        }
        return true
    }

    private fun feedbackIntervalMs(proximity: Int): Long? {
        return when {
            proximity < 10 -> null
            proximity < 40 -> 800L
            proximity < 70 -> 350L
            else -> 150L
        }
    }

    private companion object {
        const val UI_UPDATE_INTERVAL_MS = 100L
        const val FEEDBACK_IDLE_POLL_MS = 200L
        const val HAPTIC_DURATION_MS = 40L
    }
}

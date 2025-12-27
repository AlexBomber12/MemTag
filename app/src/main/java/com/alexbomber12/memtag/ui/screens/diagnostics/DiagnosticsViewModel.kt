package com.alexbomber12.memtag.ui.screens.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexbomber12.memtag.data.AppDefaults
import com.alexbomber12.memtag.data.settings.AppSettings
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.alexbomber12.memtag.integrations.uhf.MatrixProbeResult
import com.alexbomber12.memtag.integrations.uhf.TagReading
import com.alexbomber12.memtag.integrations.uhf.UhfApplyResult
import com.alexbomber12.memtag.integrations.uhf.UhfConfig
import com.alexbomber12.memtag.integrations.uhf.UhfDiagnosticsSource
import com.alexbomber12.memtag.integrations.uhf.UhfError
import com.alexbomber12.memtag.integrations.uhf.UhfException
import com.alexbomber12.memtag.integrations.uhf.UhfLogger
import com.alexbomber12.memtag.integrations.uhf.UhfReader
import com.alexbomber12.memtag.integrations.uhf.UhfRegion
import com.alexbomber12.memtag.integrations.uhf.asException
import com.alexbomber12.memtag.integrations.uhf.toErrorMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiagnosticsUiState(
    val isInitialized: Boolean = false,
    val isInventoryRunning: Boolean = false,
    val isInitializing: Boolean = false,
    val isReadingSingle: Boolean = false,
    val isMatrixProbeRunning: Boolean = false,
    val matrixProbeCurrent: String? = null,
    val matrixProbeResults: List<MatrixProbeResult> = emptyList(),
    val startInventoryOk: Boolean? = null,
    val stopInventoryOk: Boolean? = null,
    val bufferReadsCount: Long = 0,
    val tagsSeenCount: Long = 0,
    val lastRaw0: String? = null,
    val lastRaw1: String? = null,
    val lastRssi: Int? = null,
    val currentPower: Int = AppDefaults.UHF_POWER,
    val currentRegion: UhfRegion = UhfRegion.fromSettings(AppDefaults.UHF_REGION),
    val desiredConfig: UhfConfig =
        UhfConfig(
            frequencyMode = UhfRegion.fromSettings(AppDefaults.UHF_REGION).toFrequencyMode() ?: 2,
            power = AppDefaults.UHF_POWER,
        ),
    val currentConfig: UhfConfig? = null,
    val lastApplyResult: UhfApplyResult? = null,
    val isApplyingConfig: Boolean = false,
    val isReadingConfig: Boolean = false,
    val configStatusMessage: String? = null,
    val lastReadEpc: String? = null,
    val lastErrorMessage: String? = null,
    val readings: List<TagReading> = emptyList(),
)

class DiagnosticsViewModel(
    private val settingsStore: SettingsStore,
    private val uhfReader: UhfReader,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = mutableState

    private var inventoryJob: Job? = null
    private val diagnosticsSource = uhfReader as? UhfDiagnosticsSource

    init {
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                mutableState.update {
                    it.copy(
                        currentPower = settings.uhfPower,
                        currentRegion = UhfRegion.fromSettings(settings.uhfRegion),
                        desiredConfig = desiredConfigFromSettings(settings),
                    )
                }
            }
        }
        diagnosticsSource?.let { source ->
            viewModelScope.launch {
                source.diagnosticsFlow.collect { diagnostics ->
                    mutableState.update { state ->
                        state.copy(
                            isInventoryRunning = diagnostics.inventoryRunning,
                            isMatrixProbeRunning = diagnostics.matrixProbeRunning,
                            matrixProbeCurrent = diagnostics.matrixProbeCurrent,
                            startInventoryOk = diagnostics.startInventoryOk,
                            stopInventoryOk = diagnostics.stopInventoryOk,
                            bufferReadsCount = diagnostics.bufferReadsCount,
                            tagsSeenCount = diagnostics.tagsSeenCount,
                            lastRaw0 = diagnostics.lastRaw0,
                            lastRaw1 = diagnostics.lastRaw1,
                            lastRssi = diagnostics.lastRssi,
                            lastReadEpc = diagnostics.lastReadEpc ?: state.lastReadEpc,
                        )
                    }
                }
            }
        }
        initialize()
    }

    fun initialize() {
        if (uiState.value.isMatrixProbeRunning) {
            return
        }
        if (uiState.value.isInitializing) {
            return
        }
        mutableState.update { it.copy(isInitializing = true, lastErrorMessage = null) }
        viewModelScope.launch {
            val result = uhfReader.initialize()
            if (result.isSuccess) {
                mutableState.update { it.copy(isInitialized = true, isInitializing = false) }
                readCurrentConfig()
            } else {
                mutableState.update { it.copy(isInitializing = false, isInitialized = false) }
                updateError(result.exceptionOrNull())
            }
        }
    }

    fun close() {
        if (uiState.value.isMatrixProbeRunning) {
            return
        }
        viewModelScope.launch {
            val result = uhfReader.close()
            if (result.isSuccess) {
                stopInventory()
                mutableState.update {
                    it.copy(
                        isInitialized = false,
                        isInventoryRunning = false,
                        isInitializing = false,
                        isReadingSingle = false,
                        isMatrixProbeRunning = false,
                        matrixProbeCurrent = null,
                        matrixProbeResults = emptyList(),
                        startInventoryOk = null,
                        stopInventoryOk = null,
                        bufferReadsCount = 0,
                        tagsSeenCount = 0,
                        lastRaw0 = null,
                        lastRaw1 = null,
                        lastRssi = null,
                        lastReadEpc = null,
                        isApplyingConfig = false,
                        isReadingConfig = false,
                        configStatusMessage = null,
                        currentConfig = null,
                    )
                }
            } else {
                updateError(result.exceptionOrNull())
            }
        }
    }

    fun readCurrentConfig() {
        if (uiState.value.isMatrixProbeRunning) {
            return
        }
        if (uiState.value.isReadingConfig) {
            return
        }
        mutableState.update { it.copy(isReadingConfig = true, lastErrorMessage = null, configStatusMessage = null) }
        viewModelScope.launch {
            if (!uiState.value.isInitialized) {
                mutableState.update { it.copy(isReadingConfig = false) }
                updateError(UhfError.NotInitialized.asException())
                return@launch
            }
            val modeResult = uhfReader.getFrequencyMode()
            val powerResult = uhfReader.getPower()
            val modeError = modeResult.exceptionOrNull()
            val powerError = powerResult.exceptionOrNull()
            if (isInventoryBusy(modeError) || isInventoryBusy(powerError)) {
                UhfLogger.i("configOp skipped (busy): inventoryRunning=true op=read reason=diag-read")
                mutableState.update { it.copy(isReadingConfig = false, configStatusMessage = BUSY_CONFIG_MESSAGE) }
                return@launch
            }
            if (modeResult.isFailure || powerResult.isFailure) {
                mutableState.update { it.copy(isReadingConfig = false) }
                updateError(modeError ?: powerError)
                return@launch
            }
            val config =
                UhfConfig(
                    frequencyMode = modeResult.getOrNull() ?: 0,
                    power = powerResult.getOrNull() ?: 0,
                )
            mutableState.update { it.copy(isReadingConfig = false, currentConfig = config, configStatusMessage = null) }
            UhfLogger.i("UHF config read (mode=${config.frequencyMode} power=${config.power})")
        }
    }

    fun applyDesiredConfig() {
        if (uiState.value.isMatrixProbeRunning) {
            return
        }
        if (uiState.value.isApplyingConfig) {
            return
        }
        mutableState.update { it.copy(isApplyingConfig = true, lastErrorMessage = null, configStatusMessage = null) }
        viewModelScope.launch {
            val result = uhfReader.applyUhfConfig("diag-apply")
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                if (isInventoryBusy(error)) {
                    mutableState.update {
                        it.copy(isApplyingConfig = false, configStatusMessage = BUSY_CONFIG_MESSAGE)
                    }
                    return@launch
                }
                mutableState.update { it.copy(isApplyingConfig = false) }
                updateError(error)
                return@launch
            }
            val applyResult = result.getOrNull()
            val updatedConfig =
                applyResult?.let { UhfConfig(frequencyMode = it.afterMode ?: 0, power = it.afterPower ?: 0) }
            mutableState.update {
                it.copy(
                    isApplyingConfig = false,
                    lastApplyResult = applyResult,
                    currentConfig = updatedConfig ?: it.currentConfig,
                    configStatusMessage = null,
                )
            }
            if (applyResult != null && !applyResult.success) {
                updateError(UhfError.VendorError(applyResult.toErrorMessage()).asException())
            }
        }
    }

    fun readSingle(timeoutMs: Long = 2_000) {
        if (uiState.value.isMatrixProbeRunning) {
            return
        }
        if (uiState.value.isReadingSingle) {
            return
        }
        mutableState.update { it.copy(isReadingSingle = true, lastErrorMessage = null) }
        viewModelScope.launch {
            val startMs = System.currentTimeMillis()
            UhfLogger.i("ScanRFID start (screen=diagnostics source=button usedMethod=single)")
            val wasInventoryRunning = uiState.value.isInventoryRunning
            if (wasInventoryRunning) {
                inventoryJob?.cancel()
                inventoryJob = null
            }
            uhfReader.stopInventory()
            if (wasInventoryRunning) {
                setInventoryRunning(false)
            }
            val applyResult = uhfReader.applyUhfConfigIfNeeded("diag-scan")
            if (applyResult.isFailure) {
                mutableState.update { it.copy(isReadingSingle = false) }
                updateError(applyResult.exceptionOrNull())
                UhfLogger.i("ScanRFID end (screen=diagnostics result=error durationMs=${System.currentTimeMillis() - startMs})")
                return@launch
            }
            val applied = applyResult.getOrNull()
            if (applied != null) {
                mutableState.update { it.copy(lastApplyResult = applied) }
                if (!applied.success) {
                    mutableState.update { it.copy(isReadingSingle = false) }
                    updateError(UhfError.VendorError(applied.toErrorMessage()).asException())
                    UhfLogger.i("ScanRFID end (screen=diagnostics result=config_failed durationMs=${System.currentTimeMillis() - startMs})")
                    return@launch
                }
            }
            val result = uhfReader.readSingle(timeoutMs)
            if (result.isSuccess) {
                val epc = result.getOrNull().orEmpty()
                mutableState.update { it.copy(isReadingSingle = false, lastReadEpc = epc) }
                appendReading(
                    TagReading(
                        epcHex = epc,
                        rssi = null,
                        timestampMs = System.currentTimeMillis(),
                    ),
                )
                UhfLogger.i("ScanRFID end (screen=diagnostics result=$epc durationMs=${System.currentTimeMillis() - startMs})")
            } else {
                mutableState.update { it.copy(isReadingSingle = false) }
                updateError(result.exceptionOrNull())
                UhfLogger.i("ScanRFID end (screen=diagnostics result=error durationMs=${System.currentTimeMillis() - startMs})")
            }
        }
    }

    fun runMatrixProbe() {
        if (uiState.value.isMatrixProbeRunning) {
            return
        }
        mutableState.update {
            it.copy(
                isMatrixProbeRunning = true,
                matrixProbeCurrent = "Starting...",
                matrixProbeResults = emptyList(),
                lastErrorMessage = null,
            )
        }
        viewModelScope.launch {
            val wasInventoryRunning = uiState.value.isInventoryRunning
            if (wasInventoryRunning) {
                inventoryJob?.cancel()
                inventoryJob = null
            }
            if (wasInventoryRunning) {
                uhfReader.stopInventory()
                setInventoryRunning(false)
            }
            val result = runCatching { uhfReader.runMatrixProbe() }
            if (result.isSuccess) {
                mutableState.update {
                    it.copy(
                        isMatrixProbeRunning = false,
                        matrixProbeCurrent = null,
                        matrixProbeResults = result.getOrNull().orEmpty(),
                    )
                }
            } else {
                mutableState.update { it.copy(isMatrixProbeRunning = false, matrixProbeCurrent = null) }
                updateError(result.exceptionOrNull())
            }
        }
    }

    fun startInventory() {
        if (uiState.value.isMatrixProbeRunning) {
            return
        }
        if (inventoryJob != null) {
            return
        }
        mutableState.update { it.copy(lastErrorMessage = null) }
        val job =
            viewModelScope.launch {
                UhfLogger.i("ScanRFID start (screen=diagnostics source=inventory usedMethod=inventory)")
                uhfReader.stopInventory()
                val applyResult = uhfReader.applyUhfConfigIfNeeded("diag-inventory")
                if (applyResult.isFailure) {
                    updateError(applyResult.exceptionOrNull())
                    setInventoryRunning(false)
                    UhfLogger.i("ScanRFID end (screen=diagnostics result=error usedMethod=inventory)")
                    return@launch
                }
                val applied = applyResult.getOrNull()
                if (applied != null) {
                    mutableState.update { it.copy(lastApplyResult = applied) }
                    if (!applied.success) {
                        updateError(UhfError.VendorError(applied.toErrorMessage()).asException())
                        setInventoryRunning(false)
                        UhfLogger.i("ScanRFID end (screen=diagnostics result=config_failed usedMethod=inventory)")
                        return@launch
                    }
                }
                uhfReader
                    .startInventory()
                    .also { setInventoryRunning(true) }
                    .catch { error ->
                        updateError(error)
                        setInventoryRunning(false)
                        UhfLogger.i("ScanRFID end (screen=diagnostics result=error usedMethod=inventory)")
                    }
                    .collect { reading ->
                        appendReading(reading)
                    }
            }
        inventoryJob = job
        job.invokeOnCompletion {
            inventoryJob = null
            setInventoryRunning(false)
        }
    }

    fun stopInventory() {
        if (uiState.value.isMatrixProbeRunning) {
            return
        }
        inventoryJob?.cancel()
        inventoryJob = null
        viewModelScope.launch {
            val result = uhfReader.stopInventory()
            if (result.isFailure) {
                updateError(result.exceptionOrNull())
            }
            setInventoryRunning(false)
            UhfLogger.i("ScanRFID end (screen=diagnostics result=stopped usedMethod=inventory)")
        }
    }

    fun clearReadings() {
        mutableState.update { it.copy(readings = emptyList()) }
    }

    fun clearError() {
        mutableState.update { it.copy(lastErrorMessage = null) }
    }

    fun setPower(dbm: Int) {
        val region = uiState.value.currentRegion
        mutableState.update { it.copy(currentPower = dbm, lastErrorMessage = null) }
        viewModelScope.launch {
            settingsStore.setUhf(region.settingsValue, dbm)
            if (uiState.value.isInitialized) {
                val result = uhfReader.setPower(dbm)
                if (result.isFailure) {
                    updateError(result.exceptionOrNull())
                }
            }
        }
    }

    fun setRegion(region: UhfRegion) {
        val power = uiState.value.currentPower
        mutableState.update { it.copy(currentRegion = region, lastErrorMessage = null) }
        viewModelScope.launch {
            settingsStore.setUhf(region.settingsValue, power)
            if (uiState.value.isInitialized) {
                val result = uhfReader.setRegion(region)
                if (result.isFailure) {
                    updateError(result.exceptionOrNull())
                }
            }
        }
    }

    override fun onCleared() {
        stopInventory()
        super.onCleared()
    }

    private fun appendReading(reading: TagReading) {
        mutableState.update {
            val updated = (listOf(reading) + it.readings).take(MAX_READINGS)
            it.copy(
                readings = updated,
                lastReadEpc = reading.epcHex,
                lastRssi = reading.rssi,
            )
        }
    }

    private fun setInventoryRunning(running: Boolean) {
        if (diagnosticsSource == null) {
            mutableState.update { it.copy(isInventoryRunning = running) }
        }
    }

    private fun updateError(error: Throwable?) {
        val message =
            when (val uhfError = (error as? UhfException)?.error) {
                UhfError.NotInitialized -> "UHF not initialized."
                UhfError.HardwareUnavailable -> "UHF hardware unavailable."
                UhfError.Timeout -> "UHF operation timed out."
                UhfError.OperationInProgress -> "Another UHF operation is already running."
                is UhfError.VendorError -> uhfError.message
                null -> error?.message ?: "Unknown UHF error."
            }
        mutableState.update { it.copy(lastErrorMessage = message) }
    }

    private fun isInventoryBusy(error: Throwable?): Boolean {
        return (error as? UhfException)?.error == UhfError.OperationInProgress
    }

    private fun desiredConfigFromSettings(settings: AppSettings): UhfConfig {
        val frequencyMode =
            settings.uhfFrequencyMode
                ?: UhfRegion.fromSettings(settings.uhfRegion).toFrequencyMode()
                ?: (UhfRegion.fromSettings(AppDefaults.UHF_REGION).toFrequencyMode() ?: 2)
        return UhfConfig(frequencyMode = frequencyMode, power = settings.uhfPower)
    }

    private companion object {
        const val MAX_READINGS = 50
        const val BUSY_CONFIG_MESSAGE = "Busy: inventory is running. Stop inventory to read/apply config."
    }
}

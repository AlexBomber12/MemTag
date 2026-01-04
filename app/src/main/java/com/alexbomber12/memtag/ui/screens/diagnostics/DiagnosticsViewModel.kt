package com.alexbomber12.memtag.ui.screens.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexbomber12.memtag.app.SessionFlagsStore
import com.alexbomber12.memtag.data.AppDefaults
import com.alexbomber12.memtag.data.settings.AppSettings
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.alexbomber12.memtag.integrations.uhf.MatrixProbeResult
import com.alexbomber12.memtag.integrations.uhf.ProtocolAttempt
import com.alexbomber12.memtag.integrations.uhf.ProtocolSupport
import com.alexbomber12.memtag.integrations.uhf.TagReading
import com.alexbomber12.memtag.integrations.uhf.UHF_CONFIG_BUSY
import com.alexbomber12.memtag.integrations.uhf.UhfApplyResult
import com.alexbomber12.memtag.integrations.uhf.UhfConfig
import com.alexbomber12.memtag.integrations.uhf.UhfDesiredConfig
import com.alexbomber12.memtag.integrations.uhf.UhfDiagnosticsSource
import com.alexbomber12.memtag.integrations.uhf.UhfError
import com.alexbomber12.memtag.integrations.uhf.UhfException
import com.alexbomber12.memtag.integrations.uhf.UhfLogger
import com.alexbomber12.memtag.integrations.uhf.UhfReader
import com.alexbomber12.memtag.integrations.uhf.UhfRegion
import com.alexbomber12.memtag.integrations.uhf.asException
import com.alexbomber12.memtag.integrations.uhf.ensureConfiguredWithRecovery
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
    val isUhfBusy: Boolean = false,
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
    val desiredConfig: UhfDesiredConfig =
        UhfDesiredConfig(
            region = UhfRegion.fromSettings(AppDefaults.UHF_REGION),
            powerDbm = AppDefaults.UHF_POWER,
        ),
    val currentConfig: UhfConfig? = null,
    val lastApplyResult: UhfApplyResult? = null,
    val protocolSupport: ProtocolSupport = ProtocolSupport.Unknown,
    val lastProtocolAttempt: ProtocolAttempt? = null,
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
    private val sessionFlagsStore: SessionFlagsStore,
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
                            protocolSupport = diagnostics.protocolSupport,
                            lastProtocolAttempt = diagnostics.lastProtocolAttempt,
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
                        isUhfBusy = false,
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
        if (uiState.value.isInventoryRunning || uiState.value.isReadingSingle) {
            mutableState.update { it.copy(configStatusMessage = BUSY_CONFIG_MESSAGE) }
            return
        }
        mutableState.update { it.copy(isReadingConfig = true, lastErrorMessage = null, configStatusMessage = null) }
        viewModelScope.launch {
            if (!uiState.value.isInitialized) {
                mutableState.update { it.copy(isReadingConfig = false) }
                updateError(UhfError.NotInitialized.asException())
                return@launch
            }
            val modeResult = uhfReader.getFrequencyMode("diag-read")
            val protocolResult = uhfReader.getProtocol("diag-read")
            val rfLinkResult = uhfReader.getRfLink("diag-read")
            val powerResult = uhfReader.getPower("diag-read")
            val modeValue = modeResult.getOrNull()
            val protocolValue = protocolResult.getOrNull()
            val rfLinkValue = rfLinkResult.getOrNull()
            val powerValue = powerResult.getOrNull()
            val busy =
                isBusyValue(modeValue) ||
                    isBusyValue(protocolValue) ||
                    isBusyValue(rfLinkValue) ||
                    isBusyValue(powerValue)
            if (busy) {
                UhfLogger.i("configOp skipped (busy): uhfBusy=true op=read reason=diag-read")
                mutableState.update { it.copy(isReadingConfig = false, configStatusMessage = BUSY_CONFIG_MESSAGE) }
                return@launch
            }
            val modeError = modeResult.exceptionOrNull()
            val protocolError = protocolResult.exceptionOrNull()
            val rfLinkError = rfLinkResult.exceptionOrNull()
            val powerError = powerResult.exceptionOrNull()
            if (modeResult.isFailure || protocolResult.isFailure || rfLinkResult.isFailure || powerResult.isFailure) {
                mutableState.update { it.copy(isReadingConfig = false) }
                updateError(modeError ?: protocolError ?: rfLinkError ?: powerError)
                return@launch
            }
            val config =
                UhfConfig(
                    frequencyMode = modeValue ?: 0,
                    power = powerValue ?: 0,
                    protocol = protocolValue ?: 0,
                    rfLink = rfLinkValue ?: 0,
                )
            mutableState.update { it.copy(isReadingConfig = false, currentConfig = config, configStatusMessage = null) }
            UhfLogger.i(
                "UHF config read (mode=${config.frequencyMode} " +
                    "protocol=${config.protocol} rfLink=${config.rfLink} power=${config.power})",
            )
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
            val result = uhfReader.applyDesiredConfigWithReadback("diag-apply")
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
                applyResult?.let {
                    UhfConfig(
                        frequencyMode = it.afterMode ?: 0,
                        power = it.afterPower ?: 0,
                        protocol = it.afterProtocol ?: 0,
                        rfLink = it.afterRfLink ?: 0,
                    )
                }
            mutableState.update {
                it.copy(
                    isApplyingConfig = false,
                    lastApplyResult = applyResult,
                    currentConfig = updatedConfig ?: it.currentConfig,
                    protocolSupport = applyResult?.protocolSupport ?: it.protocolSupport,
                    lastProtocolAttempt = applyResult?.protocolAttempt ?: it.lastProtocolAttempt,
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
        mutableState.update { it.copy(isReadingSingle = true, isUhfBusy = true, lastErrorMessage = null) }
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
            val applyResult = uhfReader.ensureConfiguredWithRecovery("diag-scan")
            if (applyResult.isFailure) {
                mutableState.update { it.copy(isReadingSingle = false, isUhfBusy = false) }
                updateError(applyResult.exceptionOrNull())
                UhfLogger.i("ScanRFID end (screen=diagnostics result=error durationMs=${System.currentTimeMillis() - startMs})")
                return@launch
            }
            val applied = applyResult.getOrNull()
            if (applied != null) {
                mutableState.update {
                    it.copy(
                        lastApplyResult = applied,
                        protocolSupport = applied.protocolSupport,
                        lastProtocolAttempt = applied.protocolAttempt,
                    )
                }
                if (!applied.success) {
                    mutableState.update { it.copy(isReadingSingle = false, isUhfBusy = false) }
                    updateError(UhfError.VendorError(applied.toErrorMessage()).asException())
                    UhfLogger.i("ScanRFID end (screen=diagnostics result=config_failed durationMs=${System.currentTimeMillis() - startMs})")
                    return@launch
                }
            }
            val result = uhfReader.readSingle(timeoutMs)
            if (result.isSuccess) {
                val epc = result.getOrNull().orEmpty()
                mutableState.update { it.copy(isReadingSingle = false, isUhfBusy = false, lastReadEpc = epc) }
                appendReading(
                    TagReading(
                        epcHex = epc,
                        rssi = null,
                        timestampMs = System.currentTimeMillis(),
                    ),
                )
                UhfLogger.i("ScanRFID end (screen=diagnostics result=$epc durationMs=${System.currentTimeMillis() - startMs})")
            } else {
                mutableState.update { it.copy(isReadingSingle = false, isUhfBusy = false) }
                updateError(result.exceptionOrNull())
                UhfLogger.i("ScanRFID end (screen=diagnostics result=error durationMs=${System.currentTimeMillis() - startMs})")
            }
        }
    }

    fun runMatrixProbe() {
        if (uiState.value.isMatrixProbeRunning) {
            return
        }
        sessionFlagsStore.setDiagRunning(true)
        mutableState.update {
            it.copy(
                isMatrixProbeRunning = true,
                matrixProbeCurrent = "Starting...",
                matrixProbeResults = emptyList(),
                lastErrorMessage = null,
            )
        }
        viewModelScope.launch {
            try {
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
            } finally {
                sessionFlagsStore.setDiagRunning(false)
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
        sessionFlagsStore.setDiagRunning(true)
        val job =
            viewModelScope.launch {
                UhfLogger.i("ScanRFID start (screen=diagnostics source=inventory usedMethod=inventory)")
                uhfReader.stopInventory()
                val applyResult = uhfReader.ensureConfiguredWithRecovery("diag-inventory")
                if (applyResult.isFailure) {
                    updateError(applyResult.exceptionOrNull())
                    setInventoryRunning(false)
                    UhfLogger.i("ScanRFID end (screen=diagnostics result=error usedMethod=inventory)")
                    return@launch
                }
                val applied = applyResult.getOrNull()
                if (applied != null) {
                    mutableState.update {
                        it.copy(
                            lastApplyResult = applied,
                            protocolSupport = applied.protocolSupport,
                            lastProtocolAttempt = applied.protocolAttempt,
                        )
                    }
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
            sessionFlagsStore.setDiagRunning(false)
        }
    }

    fun stopInventory() {
        if (uiState.value.isMatrixProbeRunning) {
            return
        }
        sessionFlagsStore.setDiagRunning(false)
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

    private fun isBusyValue(value: Int?): Boolean = value == UHF_CONFIG_BUSY

    private fun desiredConfigFromSettings(settings: AppSettings): UhfDesiredConfig {
        val region = UhfRegion.fromSettings(settings.uhfRegion)
        return UhfDesiredConfig(region = region, powerDbm = settings.uhfPower)
    }

    private companion object {
        const val MAX_READINGS = 50
        const val BUSY_CONFIG_MESSAGE = "Busy: UHF is scanning or inventory is running. Stop scan to read/apply config."
    }
}

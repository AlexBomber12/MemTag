package com.alexbomber12.memtag.ui.screens.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexbomber12.memtag.data.AppDefaults
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.alexbomber12.memtag.integrations.uhf.TagReading
import com.alexbomber12.memtag.integrations.uhf.UhfError
import com.alexbomber12.memtag.integrations.uhf.UhfException
import com.alexbomber12.memtag.integrations.uhf.UhfReader
import com.alexbomber12.memtag.integrations.uhf.UhfRegion
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
    val currentPower: Int = AppDefaults.UHF_POWER,
    val currentRegion: UhfRegion = UhfRegion.fromSettings(AppDefaults.UHF_REGION),
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

    init {
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                mutableState.update {
                    it.copy(
                        currentPower = settings.uhfPower,
                        currentRegion = UhfRegion.fromSettings(settings.uhfRegion),
                    )
                }
            }
        }
    }

    fun initialize() {
        if (uiState.value.isInitializing) {
            return
        }
        mutableState.update { it.copy(isInitializing = true, lastErrorMessage = null) }
        viewModelScope.launch {
            val result = uhfReader.initialize()
            if (result.isSuccess) {
                mutableState.update { it.copy(isInitialized = true, isInitializing = false) }
                applySettingsToReader()
            } else {
                mutableState.update { it.copy(isInitializing = false, isInitialized = false) }
                updateError(result.exceptionOrNull())
            }
        }
    }

    fun close() {
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
                    )
                }
            } else {
                updateError(result.exceptionOrNull())
            }
        }
    }

    fun readSingle(timeoutMs: Long = 2_000) {
        if (uiState.value.isReadingSingle) {
            return
        }
        mutableState.update { it.copy(isReadingSingle = true, lastErrorMessage = null) }
        viewModelScope.launch {
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
            } else {
                mutableState.update { it.copy(isReadingSingle = false) }
                updateError(result.exceptionOrNull())
            }
        }
    }

    fun startInventory() {
        if (inventoryJob != null) {
            return
        }
        mutableState.update { it.copy(isInventoryRunning = true, lastErrorMessage = null) }
        val job =
            viewModelScope.launch {
                uhfReader
                    .startInventory()
                    .catch { error ->
                        updateError(error)
                        mutableState.update { it.copy(isInventoryRunning = false) }
                    }
                    .collect { reading ->
                        appendReading(reading)
                    }
            }
        inventoryJob = job
        job.invokeOnCompletion {
            inventoryJob = null
        }
    }

    fun stopInventory() {
        inventoryJob?.cancel()
        inventoryJob = null
        viewModelScope.launch {
            val result = uhfReader.stopInventory()
            if (result.isFailure) {
                updateError(result.exceptionOrNull())
            }
            mutableState.update { it.copy(isInventoryRunning = false) }
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

    private fun applySettingsToReader() {
        val power = uiState.value.currentPower
        val region = uiState.value.currentRegion
        viewModelScope.launch {
            val powerResult = uhfReader.setPower(power)
            if (powerResult.isFailure) {
                updateError(powerResult.exceptionOrNull())
            }
            val regionResult = uhfReader.setRegion(region)
            if (regionResult.isFailure) {
                updateError(regionResult.exceptionOrNull())
            }
        }
    }

    private fun appendReading(reading: TagReading) {
        mutableState.update {
            val updated = (listOf(reading) + it.readings).take(MAX_READINGS)
            it.copy(readings = updated)
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

    private companion object {
        const val MAX_READINGS = 50
    }
}

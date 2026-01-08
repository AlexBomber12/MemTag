@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.screens.diagnostics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexbomber12.memtag.data.AppDefaults
import com.alexbomber12.memtag.integrations.uhf.ProtocolSupport
import com.alexbomber12.memtag.integrations.uhf.UHF_CONFIG_BUSY
import com.alexbomber12.memtag.integrations.uhf.UHF_PROTOCOL_UNSUPPORTED
import com.alexbomber12.memtag.integrations.uhf.UhfRegion
import com.alexbomber12.memtag.ui.components.AppCard
import com.alexbomber12.memtag.ui.components.ErrorState
import com.alexbomber12.memtag.ui.components.PrimaryButton
import com.alexbomber12.memtag.ui.components.SecondaryButton
import kotlinx.coroutines.delay

@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }

    var regionExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingPower by
        rememberSaveable(
            saver =
                Saver(
                    save = { it.value },
                    restore = { mutableFloatStateOf(it) },
                ),
        ) {
            mutableFloatStateOf(state.currentPower.toFloat())
        }

    LaunchedEffect(state.currentPower) {
        pendingPower = state.currentPower.toFloat()
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopInventory() }
    }

    val matrixProbeBusy = state.isMatrixProbeRunning
    val canInitialize = !state.isInitialized && !state.isInitializing && !matrixProbeBusy
    val canClose = state.isInitialized && !state.isInitializing && !matrixProbeBusy
    val canReadSingle =
        state.isInitialized &&
            !state.isReadingSingle &&
            !state.isInventoryRunning &&
            !matrixProbeBusy
    val canStartInventory = state.isInitialized && !state.isInventoryRunning && !matrixProbeBusy
    val canStopInventory = state.isInventoryRunning && !matrixProbeBusy
    val configBusy = state.isInventoryRunning || state.isReadingSingle
    val canReadConfig = state.isInitialized && !state.isReadingConfig && !matrixProbeBusy && !configBusy
    val canApplyConfig = state.isInitialized && !state.isApplyingConfig && !matrixProbeBusy && !configBusy
    val canRunMatrixProbe = state.isInitialized && !matrixProbeBusy

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppCard(title = "Status") {
                Text(text = "Initialized: ${if (state.isInitialized) "Yes" else "No"}")
                Text(text = "Inventory running: ${if (state.isInventoryRunning) "Yes" else "No"}")
                Text(text = "Last read EPC: ${state.lastReadEpc ?: "(none)"}")
                Text(text = "Last error: ${state.lastErrorMessage ?: "(none)"}")
                if (state.isInitializing || state.isReadingSingle) {
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 4.dp))
                        Text(
                            text =
                                if (state.isInitializing) {
                                    "Initializing..."
                                } else {
                                    "Reading tag..."
                                },
                        )
                    }
                }
            }
        }

        item {
            AppCard(title = "Inventory diagnostics") {
                val startOk =
                    when (state.startInventoryOk) {
                        true -> "Yes"
                        false -> "No"
                        null -> "--"
                    }
                val stopOk =
                    when (state.stopInventoryOk) {
                        true -> "Yes"
                        false -> "No"
                        null -> "--"
                    }
                Text(text = "Start inventory ok: $startOk")
                Text(text = "Stop inventory ok: $stopOk")
                Text(text = "Buffer reads: ${state.bufferReadsCount}")
                Text(text = "Tags seen: ${state.tagsSeenCount}")
                Text(text = "Last raw0: ${state.lastRaw0 ?: "--"}")
                Text(text = "Last raw1: ${state.lastRaw1 ?: "--"}")
                Text(text = "Last RSSI: ${state.lastRssi?.toString() ?: "--"}")
            }
        }

        item {
            AppCard(title = "Matrix probe") {
                PrimaryButton(
                    text = "Run Matrix Probe (4 x 1s)",
                    onClick = viewModel::runMatrixProbe,
                    enabled = canRunMatrixProbe,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.isMatrixProbeRunning) {
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 4.dp))
                        Text(text = "Running ${state.matrixProbeCurrent ?: "matrix probe"}")
                    }
                }
                if (state.matrixProbeResults.isEmpty() && !state.isMatrixProbeRunning) {
                    Text(text = "No matrix probe results yet.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.matrixProbeResults.forEach { result ->
                            val startOk = if (result.startOk) "Yes" else "No"
                            val stopOk = if (result.stopOk) "Yes" else "No"
                            val first0 = trimProbeValue(result.firstRaw0)
                            val first1 = trimProbeValue(result.firstRaw1)
                            val rssi = result.firstRssi ?: "--"
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(text = result.name, style = MaterialTheme.typography.labelMedium)
                                Text(
                                    text =
                                        "Start: $startOk | Stop: $stopOk | " +
                                            "Reads: ${result.reads} | Non-null: ${result.nonNullReads}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = "First0: $first0 | First1: $first1 | RSSI: $rssi",
                                    style =
                                        MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                )
                                result.note?.let { note ->
                                    Text(text = "Note: $note", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.lastErrorMessage != null) {
            item {
                ErrorState(
                    message = state.lastErrorMessage.orEmpty(),
                    actionLabel = "Clear error",
                    onAction = { viewModel.clearError() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            AppCard(title = "Settings") {
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = state.currentRegion.settingsValue,
                        onValueChange = {},
                        readOnly = true,
                        enabled = !matrixProbeBusy,
                        label = { Text(text = "Region") },
                        trailingIcon = {
                            val icon =
                                if (regionExpanded) {
                                    Icons.Filled.KeyboardArrowUp
                                } else {
                                    Icons.Filled.KeyboardArrowDown
                                }
                            IconButton(
                                onClick = { regionExpanded = !regionExpanded },
                                enabled = !matrixProbeBusy,
                            ) {
                                Icon(imageVector = icon, contentDescription = null)
                            }
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !matrixProbeBusy) { regionExpanded = true },
                    )
                    DropdownMenu(
                        expanded = regionExpanded,
                        onDismissRequest = { regionExpanded = false },
                    ) {
                        UhfRegion.values().forEach { option ->
                            DropdownMenuItem(
                                text = { Text(text = option.settingsValue) },
                                onClick = {
                                    regionExpanded = false
                                    viewModel.setRegion(option)
                                },
                            )
                        }
                    }
                }
                Text(
                    text = "Power: ${pendingPower.toInt()} dBm",
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = pendingPower,
                    onValueChange = { pendingPower = it },
                    onValueChangeFinished = { viewModel.setPower(pendingPower.toInt()) },
                    valueRange = AppDefaults.UHF_POWER_MIN.toFloat()..AppDefaults.UHF_POWER_MAX.toFloat(),
                    steps = (AppDefaults.UHF_POWER_MAX - AppDefaults.UHF_POWER_MIN) - 1,
                    enabled = !matrixProbeBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            AppCard(title = "UHF Config") {
                val desired = state.desiredConfig
                Text(
                    text =
                        "Desired: region=${desired.region.settingsValue} " +
                            "mode=${formatMode(desired.frequencyMode)} " +
                            "power=${desired.powerDbm} " +
                            "protocol=${desired.protocol} " +
                            "rflink=${desired.rfLink}",
                )
                val current = state.currentConfig
                val currentProtocolLabel =
                    formatProtocolValue(
                        value = current?.protocol,
                        support = state.protocolSupport,
                    )
                Text(
                    text =
                        "Current: mode=${formatMode(current?.frequencyMode)} " +
                            "protocol=$currentProtocolLabel " +
                            "rflink=${formatConfigValue(current?.rfLink)} " +
                            "power=${formatConfigValue(current?.power)}",
                )
                val applyResult = state.lastApplyResult
                if (applyResult == null) {
                    Text(text = "Last apply: --")
                } else {
                    val appliedProtocolLabel =
                        formatProtocolValue(
                            value = applyResult.afterProtocol,
                            support = applyResult.protocolSupport,
                        )
                    val protocolVerifiedLabel = formatAppliedStatus(applyResult.protocolApplied)
                    Text(
                        text =
                            "Last apply: setModeOk=${applyResult.setModeOk} " +
                                "setRfLinkOk=${applyResult.setRfLinkOk} " +
                                "setPowerOk=${applyResult.setPowerOk}",
                    )
                    when {
                        applyResult.protocolAttempt != null -> {
                            val errorCode = applyResult.protocolAttempt.errorCode
                            val suffix = if (errorCode != null) " errCode=$errorCode" else ""
                            Text(text = "setProtocolOk=${applyResult.protocolAttempt.ok}$suffix")
                        }

                        applyResult.protocolSupport == ProtocolSupport.Unsupported -> {
                            Text(text = "setProtocol: skipped (unsupported)")
                        }
                    }
                    Text(
                        text =
                            "After: mode=${formatMode(applyResult.afterMode)} " +
                                "protocol=$appliedProtocolLabel " +
                                "rflink=${formatConfigValue(applyResult.afterRfLink)} " +
                                "power=${formatConfigValue(applyResult.afterPower)}",
                    )
                    Text(
                        text =
                            "Verified: modeApplied=${applyResult.modeApplied} " +
                                "protocol=$protocolVerifiedLabel " +
                                "rfLinkApplied=${applyResult.rfLinkApplied} " +
                                "powerApplied=${applyResult.powerApplied}",
                    )
                }
                state.configStatusMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (state.isApplyingConfig || state.isReadingConfig) {
                    Text(
                        text =
                            if (state.isApplyingConfig) {
                                "Applying config..."
                            } else {
                                "Reading config..."
                            },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PrimaryButton(
                        text = "Read current config",
                        onClick = viewModel::readCurrentConfig,
                        enabled = canReadConfig,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "Apply desired config",
                        onClick = viewModel::applyDesiredConfig,
                        enabled = canApplyConfig,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item {
            AppCard(title = "Actions") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PrimaryButton(
                        text = "Initialize",
                        onClick = viewModel::initialize,
                        enabled = canInitialize,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "Close",
                        onClick = viewModel::close,
                        enabled = canClose,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PrimaryButton(
                        text = "Scan RFID",
                        onClick = { viewModel.readSingle() },
                        enabled = canReadSingle,
                        modifier = Modifier.weight(1f),
                        loading = state.isUhfBusy,
                    )
                    SecondaryButton(
                        text = "Clear list",
                        onClick = viewModel::clearReadings,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PrimaryButton(
                        text = "Start inventory",
                        onClick = viewModel::startInventory,
                        enabled = canStartInventory,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "Stop inventory",
                        onClick = viewModel::stopInventory,
                        enabled = canStopInventory,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item {
            AppCard(title = "Readings (last ${state.readings.size})") {
                if (state.readings.isEmpty()) {
                    Text(text = "No readings yet.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.readings.forEach { reading ->
                            val ageSeconds = ((now - reading.timestampMs) / 1000).coerceAtLeast(0)
                            Column {
                                Text(
                                    text = reading.epcHex,
                                    style =
                                        MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                )
                                Text(
                                    text =
                                        "RSSI: ${reading.rssi?.toString() ?: "--"} | ${ageSeconds}s ago",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun trimProbeValue(
    value: String?,
    maxChars: Int = 24,
): String {
    if (value.isNullOrBlank()) {
        return "--"
    }
    return if (value.length <= maxChars) value else value.take(maxChars)
}

private fun formatProtocolValue(
    value: Int?,
    support: ProtocolSupport,
): String {
    return when {
        value == UHF_CONFIG_BUSY -> "busy"
        support == ProtocolSupport.Unsupported -> "unsupported"
        value == null -> "--"
        value == UHF_PROTOCOL_UNSUPPORTED -> "unavailable"
        else -> value.toString()
    }
}

private fun formatAppliedStatus(value: Boolean?): String {
    return value?.toString() ?: "N/A"
}

private fun formatMode(value: Int?): String {
    if (value == null) {
        return "--"
    }
    if (value == UHF_CONFIG_BUSY) {
        return "busy"
    }
    val hex = value.toString(16).uppercase().padStart(2, '0')
    return "0x$hex"
}

private fun formatConfigValue(value: Int?): String {
    return when (value) {
        null -> "--"
        UHF_CONFIG_BUSY -> "busy"
        else -> value.toString()
    }
}

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexbomber12.memtag.data.AppDefaults
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
    var pendingPower by rememberSaveable { mutableStateOf(state.currentPower.toFloat()) }

    LaunchedEffect(state.currentPower) {
        pendingPower = state.currentPower.toFloat()
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopInventory() }
    }

    val canInitialize = !state.isInitialized && !state.isInitializing
    val canClose = state.isInitialized && !state.isInitializing
    val canReadSingle = state.isInitialized && !state.isReadingSingle && !state.isInventoryRunning
    val canStartInventory = state.isInitialized && !state.isInventoryRunning
    val canStopInventory = state.isInventoryRunning

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
                        label = { Text(text = "Region") },
                        trailingIcon = {
                            val icon =
                                if (regionExpanded) {
                                    Icons.Filled.KeyboardArrowUp
                                } else {
                                    Icons.Filled.KeyboardArrowDown
                                }
                            IconButton(onClick = { regionExpanded = !regionExpanded }) {
                                Icon(imageVector = icon, contentDescription = null)
                            }
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { regionExpanded = true },
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
                    modifier = Modifier.fillMaxWidth(),
                )
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
                        text = "Read single",
                        onClick = { viewModel.readSingle() },
                        enabled = canReadSingle,
                        modifier = Modifier.weight(1f),
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

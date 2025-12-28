@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.screens.find

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.alexbomber12.memtag.app.HardwareAction
import com.alexbomber12.memtag.ui.components.AppCard
import com.alexbomber12.memtag.ui.components.PrimaryButton
import com.alexbomber12.memtag.ui.components.SecondaryButton
import com.alexbomber12.memtag.util.epc.EpcValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun FindScreen(
    viewModel: FindViewModel,
    initialEpc: String = "",
    autoStart: Boolean = false,
    showBackToBatch: Boolean = false,
    onBackToBatch: () -> Unit = {},
    hardwareActions: Flow<HardwareAction>,
) {
    val uiState by viewModel.uiState.collectAsState()
    var debugExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DisposableEffect(Unit) {
            onDispose { viewModel.stopFind() }
        }
        LaunchedEffect(initialEpc, autoStart) {
            if (initialEpc.isNotBlank()) {
                viewModel.applyExternalEpc(initialEpc, autoStart)
            }
        }
        LaunchedEffect(hardwareActions) {
            hardwareActions.collect { action ->
                if (action == HardwareAction.Rfid) {
                    viewModel.toggleFind()
                }
            }
        }
        val isValid = uiState.epcInput.isBlank() || EpcValidator.isValidEpcHex(uiState.epcInput)
        val showInputError = uiState.epcInput.isNotBlank() && !isValid
        val statusLabel =
            when (uiState.status) {
                FindStatus.Idle -> "Idle"
                FindStatus.Running -> "Running"
                FindStatus.NoSignal -> "No signal"
                is FindStatus.Error -> "Error"
            }

        AppCard(title = "RFID") {
            OutlinedTextField(
                value = uiState.epcInput,
                onValueChange = viewModel::onEpcInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = "RFID") },
                placeholder = { Text(text = "RFID (hex)") },
                singleLine = true,
                enabled = !uiState.isRunning,
                isError = showInputError,
                supportingText = {
                    val message =
                        if (showInputError) {
                            "Invalid EPC. Use 8-64 hex characters."
                        } else {
                            "Paste or type the tag EPC."
                        }
                    Text(text = message)
                },
            )
            if (uiState.lastScannedEpc.isNotBlank()) {
                TextButton(onClick = viewModel::useLastScannedEpc, enabled = !uiState.isRunning) {
                    Text(text = "Use last scanned RFID")
                }
                Text(
                    text = uiState.lastScannedEpc,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        AppCard(title = "Proximity") {
            val isNearbyMode = uiState.targetEpcNormalized.isNullOrBlank()
            val canSetTargetFromLastSeen =
                uiState.lastSeenAnyEpc != null &&
                    (isNearbyMode || uiState.matchStatus == MatchStatus.NotMatchedYet)

            ProximityMeter(
                proximity = uiState.proximity,
                modifier = Modifier.fillMaxWidth(),
            )
            if (isNearbyMode) {
                Text(
                    text = "Mode: Nearby tag",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = "Status: $statusLabel",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (uiState.status is FindStatus.Error) {
                Text(
                    text = uiState.lastErrorMessage.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            when {
                uiState.isRunning && isNearbyMode && uiState.tagsSeenAny == 0 -> {
                    Text(
                        text = "No tags seen yet, bring a tag closer.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                uiState.isRunning &&
                    !isNearbyMode &&
                    uiState.tagsSeenMatched == 0 &&
                    uiState.tagsSeenAny > 0 -> {
                    Text(
                        text = "Target not seen yet, but tags nearby: ${uiState.lastSeenAnyEpc.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (canSetTargetFromLastSeen) {
                SecondaryButton(
                    text = "Set target from last seen tag",
                    onClick = viewModel::setTargetFromLastSeenAny,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (uiState.debugOverlayEnabled) {
            AppCard(title = "Find Debug") {
                TextButton(
                    onClick = { debugExpanded = !debugExpanded },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(text = if (debugExpanded) "Hide details" else "Show details")
                }
                AnimatedVisibility(visible = debugExpanded) {
                    DebugPanel(
                        uiState = uiState,
                        onDisableFilterChange = viewModel::setDebugDisableFilter,
                    )
                }
            }
        }

        AppCard(title = "Controls") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PrimaryButton(
                    text = "Start",
                    onClick = viewModel::startFind,
                    modifier = Modifier.weight(1f),
                    enabled = isValid && !uiState.isRunning,
                )
                SecondaryButton(
                    text = "Stop",
                    onClick = viewModel::stopFind,
                    modifier = Modifier.weight(1f),
                    enabled = uiState.isRunning,
                )
            }
            ToggleRow(
                title = "Sound",
                description = "Ticking audio feedback",
                checked = uiState.soundEnabled,
                onCheckedChange = viewModel::setSoundEnabled,
            )
            ToggleRow(
                title = "Haptic",
                description = "Vibration pulses",
                checked = uiState.hapticEnabled,
                onCheckedChange = viewModel::setHapticEnabled,
            )
            if (showBackToBatch) {
                SecondaryButton(
                    text = "Back to Batch",
                    onClick = onBackToBatch,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ProximityMeter(
    proximity: Int,
    modifier: Modifier = Modifier,
) {
    val animatedProgress by
        animateFloatAsState(
            targetValue = proximity / 100f,
            animationSpec = tween(durationMillis = 200),
            label = "proximityProgress",
        )
    val displayValue = (animatedProgress * 100f).roundToInt().coerceIn(0, 100)
    val barColor =
        when {
            displayValue < 35 -> MaterialTheme.colorScheme.error
            displayValue < 70 -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = displayValue.toString(),
            style = MaterialTheme.typography.displaySmall,
            color = barColor,
        )
        EqualizerBar(
            progress = animatedProgress,
            color = barColor,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(160.dp),
        )
    }
}

@Composable
private fun EqualizerBar(
    progress: Float,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(4.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(progress.coerceIn(0f, 1f))
                    .clip(MaterialTheme.shapes.small)
                    .background(color),
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(text = description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun DebugPanel(
    uiState: FindUiState,
    onDisableFilterChange: (Boolean) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val formattedRaw = String.format(Locale.US, "%.2f", uiState.rawProximity)
    val formattedSmoothed = String.format(Locale.US, "%.2f", uiState.smoothedProximity)
    val matchStatusLabel =
        when (uiState.matchStatus) {
            MatchStatus.NoTarget -> "No target"
            MatchStatus.Matched -> "Matched"
            MatchStatus.NotMatchedYet -> "Not matched yet"
        }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = "Target (normalized): ${uiState.targetEpcNormalized ?: "n/a"}")
        Text(text = "Any EPC: ${uiState.lastSeenAnyEpc ?: "n/a"}")
        Text(text = "Any RSSI: ${uiState.lastSeenAnyRssi ?: "n/a"}")
        Text(text = "Any tags seen: ${uiState.tagsSeenAny}")
        Text(text = "Matched EPC: ${uiState.lastSeenMatchedEpc ?: "n/a"}")
        Text(text = "Matched RSSI: ${uiState.lastSeenMatchedRssi ?: "n/a"}")
        Text(text = "Matched tags seen: ${uiState.tagsSeenMatched}")
        Text(text = "Match status: $matchStatusLabel")
        ToggleRow(
            title = "Debug: disable filter",
            description = "Use any tag for Geiger updates",
            checked = uiState.debugDisableFilter,
            onCheckedChange = onDisableFilterChange,
        )
        if (uiState.lastSeenAnyEpc != null) {
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(uiState.lastSeenAnyEpc)) },
            ) {
                Text(text = "Copy last seen EPC")
            }
        }
        Text(text = "Last RSSI: ${uiState.lastRssi ?: "n/a"}")
        Text(text = "Hits / window: ${uiState.hitsPerWindow}")
        Text(text = "Raw proximity: $formattedRaw")
        Text(text = "Smoothed proximity: $formattedSmoothed")
        Text(text = "Seen recently: ${uiState.seenRecently}")
    }
}

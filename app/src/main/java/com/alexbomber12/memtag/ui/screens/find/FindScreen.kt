@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.screens.find

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import com.alexbomber12.memtag.app.HardwareAction
import com.alexbomber12.memtag.ui.components.AppScaffold
import com.alexbomber12.memtag.ui.components.PrimaryButton
import com.alexbomber12.memtag.ui.components.SecondaryButton
import com.alexbomber12.memtag.ui.components.SectionCard
import com.alexbomber12.memtag.ui.components.StatChip
import com.alexbomber12.memtag.util.epc.EpcValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

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
    val errorMessage = uiState.lastErrorMessage?.takeIf { it.isNotBlank() }
    val isNearbyMode = uiState.targetEpcNormalized.isNullOrBlank()
    val canSetTargetFromLastSeen =
        uiState.lastSeenAnyEpc != null &&
            (isNearbyMode || uiState.matchStatus == MatchStatus.NotMatchedYet)
    val statusLabel =
        when (uiState.status) {
            FindStatus.Idle -> "Idle"
            FindStatus.NoSignal -> "Running"
            FindStatus.Running -> "Signal detected"
            is FindStatus.Error -> "Idle"
        }
    val canEditTarget = !uiState.isRunning

    val showTargetUpdated: () -> Unit = {
        coroutineScope.launch {
            snackbarHostState.showSnackbar("Target EPC updated")
        }
    }
    val pasteFromClipboard: () -> Unit = {
        val clipboardText = clipboardManager.getText()?.text?.trim().orEmpty()
        if (clipboardText.isNotBlank()) {
            viewModel.onEpcInputChange(clipboardText)
            showTargetUpdated()
        }
    }

    AppScaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .imePadding(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard(
                    title = "Target",
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = uiState.epcInput,
                        onValueChange = viewModel::onEpcInputChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text = "Target EPC") },
                        placeholder = { Text(text = "Paste or scan EPC") },
                        singleLine = true,
                        enabled = canEditTarget,
                        isError = showInputError,
                        supportingText =
                            if (showInputError) {
                                { Text(text = "Invalid EPC. Use 8-64 hex characters.") }
                            } else {
                                null
                            },
                        trailingIcon = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (uiState.lastScannedEpc.isNotBlank()) {
                                    IconButton(
                                        onClick = {
                                            viewModel.useLastScannedEpc()
                                            showTargetUpdated()
                                        },
                                        enabled = canEditTarget,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.History,
                                            contentDescription = "Use last scanned EPC",
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = pasteFromClipboard,
                                    enabled = canEditTarget,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ContentPaste,
                                        contentDescription = "Paste EPC from clipboard",
                                    )
                                }
                                if (uiState.epcInput.isNotBlank()) {
                                    IconButton(
                                        onClick = { viewModel.onEpcInputChange("") },
                                        enabled = canEditTarget,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Clear,
                                            contentDescription = "Clear target EPC",
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }

            item {
                SectionCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = "Proximity", style = MaterialTheme.typography.titleMedium)
                        StatChip(label = statusLabel)
                    }
                    if (errorMessage != null) {
                        ErrorBanner(
                            message = errorMessage,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PrimaryButton(
                            text = if (uiState.isRunning) "Stop" else "Start",
                            onClick = viewModel::toggleFind,
                            modifier = Modifier.weight(1f),
                            enabled = uiState.isRunning || isValid,
                        )
                        if (errorMessage != null) {
                            SecondaryButton(
                                text = "Clear error",
                                onClick = viewModel::clearError,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    ProximityMeter(
                        proximity = uiState.proximity,
                        isRunning = uiState.isRunning,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (uiState.isRunning &&
                        !isNearbyMode &&
                        uiState.tagsSeenMatched == 0 &&
                        uiState.tagsSeenAny > 0
                    ) {
                        Text(
                            text = "Target not seen yet, but tags nearby: ${uiState.lastSeenAnyEpc.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (canSetTargetFromLastSeen) {
                        SecondaryButton(
                            text = "Set target from last seen tag",
                            onClick = viewModel::setTargetFromLastSeenAny,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            if (showBackToBatch) {
                item {
                    SectionCard(
                        title = "Actions",
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SecondaryButton(
                            text = "Back to Batch",
                            onClick = onBackToBatch,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProximityMeter(
    proximity: Int,
    isRunning: Boolean,
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
        if (!isRunning) {
            MaterialTheme.colorScheme.outline
        } else {
            when {
                displayValue < 35 -> MaterialTheme.colorScheme.error
                displayValue < 70 -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.primary
            }
        }
    val labelColor =
        if (!isRunning) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            barColor
        }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = displayValue.toString(),
            style = MaterialTheme.typography.headlineMedium,
            color = labelColor,
        )
        LinearProgressIndicator(
            progress = { animatedProgress.coerceIn(0f, 1f) },
            color = barColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier =
                Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(MaterialTheme.shapes.small),
        )
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(12.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

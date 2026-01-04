@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.screens.find

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.alexbomber12.memtag.app.HardwareAction
import com.alexbomber12.memtag.ui.components.AppScaffold
import com.alexbomber12.memtag.ui.components.PrimaryButton
import com.alexbomber12.memtag.ui.components.SecondaryButton
import com.alexbomber12.memtag.ui.components.SectionCard
import com.alexbomber12.memtag.ui.components.StatChip
import com.alexbomber12.memtag.ui.theme.MemTagTheme
import com.alexbomber12.memtag.ui.theme.SignalOrange
import com.alexbomber12.memtag.ui.theme.SignalYellow
import com.alexbomber12.memtag.ui.theme.SuccessGreen
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
    val isValid = uiState.epcInput.isBlank() || EpcValidator.isValidEpcHex(uiState.epcInput)
    val showInputError = uiState.epcInput.isNotBlank() && !isValid
    val errorMessage = uiState.lastErrorMessage?.takeIf { it.isNotBlank() }
    val isNearbyMode = uiState.targetEpcNormalized.isNullOrBlank()
    val statusLabel =
        when (uiState.status) {
            FindStatus.Idle -> "Idle"
            FindStatus.NoSignal -> "Running"
            FindStatus.Running -> "Signal detected"
            is FindStatus.Error -> "Idle"
        }
    val canEditTarget = !uiState.isRunning

    LaunchedEffect(hardwareActions, canEditTarget) {
        hardwareActions.collect { action ->
            when (action) {
                HardwareAction.Rfid -> viewModel.toggleFind()
                HardwareAction.Scan -> if (canEditTarget) viewModel.scanQr()
            }
        }
    }

    val showTargetUpdated: () -> Unit = {
        coroutineScope.launch {
            snackbarHostState.showSnackbar("Target EPC updated")
        }
    }
    val targetNotSeenMessage =
        if (
            uiState.isRunning &&
            !isNearbyMode &&
            uiState.tagsSeenMatched == 0 &&
            uiState.tagsSeenAny > 0
        ) {
            "Target not seen yet, but tags nearby: ${uiState.lastSeenAnyEpc.orEmpty()}"
        } else {
            null
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
                TargetSection(
                    value = uiState.epcInput,
                    enabled = canEditTarget,
                    isQrBusy = uiState.isQrBusy,
                    isUhfBusy = uiState.isUhfBusy,
                    showInputError = showInputError,
                    onValueChange = viewModel::onEpcInputChange,
                    onHistoryClick = {
                        viewModel.useLastScannedEpc()
                        showTargetUpdated()
                    },
                    onClearClick = { viewModel.onEpcInputChange("") },
                    onScanRfid = viewModel::scanRfidOnce,
                    onScanQr = viewModel::scanQr,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                ProximitySection(
                    statusLabel = statusLabel,
                    errorMessage = errorMessage,
                    isRunning = uiState.isRunning,
                    isValid = isValid,
                    proximity = uiState.proximity,
                    targetNotSeenMessage = targetNotSeenMessage,
                    onToggle = viewModel::toggleFind,
                    onClearError = viewModel::clearError,
                    modifier = Modifier.fillMaxWidth(),
                )
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
private fun TargetSection(
    value: String,
    enabled: Boolean,
    isQrBusy: Boolean,
    isUhfBusy: Boolean,
    showInputError: Boolean,
    onValueChange: (String) -> Unit,
    onHistoryClick: () -> Unit,
    onClearClick: () -> Unit,
    onScanRfid: () -> Unit,
    onScanQr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scanEnabled = enabled && !isQrBusy && !isUhfBusy
    SectionCard(modifier = modifier) {
        TargetEpcField(
            value = value,
            enabled = enabled,
            showInputError = showInputError,
            onValueChange = onValueChange,
            onHistoryClick = onHistoryClick,
            onClearClick = onClearClick,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrimaryButton(
                text = "Scan RFID",
                onClick = onScanRfid,
                modifier = Modifier.weight(1f),
                enabled = scanEnabled,
                loading = isUhfBusy,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.CenterFocusStrong,
                        contentDescription = "Scan RFID",
                    )
                },
            )
            SecondaryButton(
                text = "Scan QR",
                onClick = onScanQr,
                modifier = Modifier.weight(1f),
                enabled = scanEnabled,
                loading = isQrBusy,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.QrCodeScanner,
                        contentDescription = "Scan QR",
                    )
                },
            )
        }
    }
}

@Composable
private fun TargetEpcField(
    value: String,
    enabled: Boolean,
    showInputError: Boolean,
    onValueChange: (String) -> Unit,
    onHistoryClick: () -> Unit,
    onClearClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isEmpty = value.isBlank()
    val trailingIcon = if (isEmpty) Icons.Filled.History else Icons.Filled.Clear
    val trailingDescription =
        if (isEmpty) {
            "Use last scanned EPC"
        } else {
            "Clear target EPC"
        }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(text = "Target EPC") },
        placeholder = { Text(text = "Paste or scan EPC") },
        singleLine = true,
        enabled = enabled,
        isError = showInputError,
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.outline,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        supportingText =
            if (showInputError) {
                { Text(text = "Invalid EPC. Use 8-64 hex characters.") }
            } else {
                null
            },
        trailingIcon = {
            IconButton(
                onClick = {
                    if (isEmpty) {
                        onHistoryClick()
                    } else {
                        onClearClick()
                    }
                },
                enabled = enabled,
            ) {
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = trailingDescription,
                )
            }
        },
    )
}

@Composable
private fun ProximitySection(
    statusLabel: String,
    errorMessage: String?,
    isRunning: Boolean,
    isValid: Boolean,
    proximity: Int,
    targetNotSeenMessage: String?,
    onToggle: () -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayErrorMessage = errorMessage?.takeIf { !isQrTimeoutMessage(it) }
    val stopColors =
        if (isRunning) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else {
            null
        }
    SectionCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Proximity", style = MaterialTheme.typography.titleMedium)
            StatChip(label = statusLabel)
        }
        if (displayErrorMessage != null) {
            ErrorBanner(
                message = displayErrorMessage,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrimaryButton(
                text = if (isRunning) "Stop" else "Start",
                onClick = onToggle,
                modifier = Modifier.weight(1f),
                enabled = isRunning || isValid,
                colors = stopColors,
                textStyle = MaterialTheme.typography.titleMedium,
            )
            if (displayErrorMessage != null) {
                SecondaryButton(
                    text = "Clear error",
                    onClick = onClearError,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        ProximityMeter(
            proximity = proximity,
            isRunning = isRunning,
            modifier = Modifier.fillMaxWidth(),
        )
        if (targetNotSeenMessage != null) {
            Text(
                text = targetNotSeenMessage,
                style = MaterialTheme.typography.bodySmall,
            )
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
                displayValue >= 75 -> SuccessGreen
                displayValue >= 50 -> SignalYellow
                displayValue >= 25 -> SignalOrange
                else -> MaterialTheme.colorScheme.error
            }
        }
    val labelColor =
        if (!isRunning) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            barColor
        }
    val guideColor = MaterialTheme.colorScheme.onSurfaceVariant
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
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "SIGNAL STRENGTH",
                    style = MaterialTheme.typography.labelSmall,
                    color = guideColor,
                )
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(animatedProgress.coerceIn(0f, 1f))
                            .clip(MaterialTheme.shapes.small)
                            .background(barColor),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Low",
                    style = MaterialTheme.typography.labelSmall,
                    color = guideColor,
                )
                Text(
                    text = "High",
                    style = MaterialTheme.typography.labelSmall,
                    color = guideColor,
                )
            }
        }
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

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun TargetFieldEmptyPreview() {
    MemTagTheme {
        TargetSection(
            value = "",
            enabled = true,
            isQrBusy = false,
            isUhfBusy = false,
            showInputError = false,
            onValueChange = {},
            onHistoryClick = {},
            onClearClick = {},
            onScanRfid = {},
            onScanQr = {},
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun TargetFieldFilledPreview() {
    MemTagTheme {
        TargetSection(
            value = "E2000017221101441890ABCD",
            enabled = true,
            isQrBusy = false,
            isUhfBusy = false,
            showInputError = false,
            onValueChange = {},
            onHistoryClick = {},
            onClearClick = {},
            onScanRfid = {},
            onScanQr = {},
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ProximityIdlePreview() {
    MemTagTheme {
        ProximitySection(
            statusLabel = "Idle",
            errorMessage = null,
            isRunning = false,
            isValid = true,
            proximity = 0,
            targetNotSeenMessage = null,
            onToggle = {},
            onClearError = {},
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ProximityQrTimeoutHiddenPreview() {
    MemTagTheme {
        ProximitySection(
            statusLabel = "Idle",
            errorMessage = "QR scan timed out.",
            isRunning = false,
            isValid = true,
            proximity = 0,
            targetNotSeenMessage = null,
            onToggle = {},
            onClearError = {},
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ProximityRunningMaxPowerPreview() {
    MemTagTheme {
        ProximitySection(
            statusLabel = "Signal detected",
            errorMessage = null,
            isRunning = true,
            isValid = true,
            proximity = 91,
            targetNotSeenMessage = null,
            onToggle = {},
            onClearError = {},
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        )
    }
}

private const val QR_TIMEOUT_MESSAGE = "QR scan timed out."

private fun isQrTimeoutMessage(message: String): Boolean {
    return message.trim() == QR_TIMEOUT_MESSAGE
}

@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexbomber12.memtag.app.SyncStatusState
import com.alexbomber12.memtag.data.AppDefaults
import com.alexbomber12.memtag.data.settings.AppSettings
import com.alexbomber12.memtag.ui.components.AppCard
import com.alexbomber12.memtag.ui.components.ErrorState
import com.alexbomber12.memtag.ui.components.LoadingState
import com.alexbomber12.memtag.ui.components.PrimaryButton
import com.alexbomber12.memtag.ui.components.SecondaryButton
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenDiagnostics: () -> Unit,
) {
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatusState.collectAsStateWithLifecycle()
    val lastSyncState by viewModel.lastSyncState.collectAsStateWithLifecycle()

    var baseUrl by rememberSaveable { mutableStateOf(settings.mementoBaseUrl) }
    var token by rememberSaveable { mutableStateOf(settings.mementoToken) }
    var libraryId by rememberSaveable { mutableStateOf(settings.mementoLibraryId) }
    var region by rememberSaveable { mutableStateOf(settings.uhfRegion) }
    var power by rememberSaveable { mutableStateOf(settings.uhfPower.toFloat()) }
    var scanAction by rememberSaveable { mutableStateOf(settings.scan2dAction) }
    var scanExtraKey by rememberSaveable { mutableStateOf(settings.scan2dExtraKey) }
    var rfidKeyCodes by rememberSaveable { mutableStateOf(settings.rfidKeyCodes) }
    var scanKeyCodes by rememberSaveable { mutableStateOf(settings.scanKeyCodes) }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    var regionExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingOpenDiagnostics by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(
        settings.mementoBaseUrl,
        settings.mementoToken,
        settings.mementoLibraryId,
        settings.uhfRegion,
        settings.uhfPower,
        settings.scan2dAction,
        settings.scan2dExtraKey,
        settings.rfidKeyCodes,
        settings.scanKeyCodes,
    ) {
        baseUrl = settings.mementoBaseUrl
        token = settings.mementoToken
        libraryId = settings.mementoLibraryId
        region = settings.uhfRegion
        power = settings.uhfPower.toFloat()
        scanAction = settings.scan2dAction
        scanExtraKey = settings.scan2dExtraKey
        rfidKeyCodes = settings.rfidKeyCodes
        scanKeyCodes = settings.scanKeyCodes
    }

    LaunchedEffect(settings.showDiagnosticsTab, pendingOpenDiagnostics) {
        if (pendingOpenDiagnostics && settings.showDiagnosticsTab) {
            pendingOpenDiagnostics = false
            onOpenDiagnostics()
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppCard(title = "Memento") {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text(text = "Base URL") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Stored without trailing slash.",
                style = MaterialTheme.typography.labelMedium,
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(text = "Token") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation =
                    if (tokenVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                trailingIcon = {
                    val icon = if (tokenVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility
                    val description = if (tokenVisible) "Hide token" else "Show token"
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(imageVector = icon, contentDescription = description)
                    }
                },
            )
            OutlinedTextField(
                value = libraryId,
                onValueChange = { libraryId = it },
                label = { Text(text = "Library ID") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        AppCard(title = "UHF") {
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = region,
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
                    AppDefaults.UHF_REGIONS.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(text = option) },
                            onClick = {
                                region = option
                                regionExpanded = false
                            },
                        )
                    }
                }
            }
            Text(
                text = "Power: ${power.toInt()} dBm",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = power,
                onValueChange = { power = it },
                valueRange = AppDefaults.UHF_POWER_MIN.toFloat()..AppDefaults.UHF_POWER_MAX.toFloat(),
                steps = (AppDefaults.UHF_POWER_MAX - AppDefaults.UHF_POWER_MIN) - 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        AppCard(title = "2D Scan") {
            OutlinedTextField(
                value = scanAction,
                onValueChange = { scanAction = it },
                label = { Text(text = "Broadcast action") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = scanExtraKey,
                onValueChange = { scanExtraKey = it },
                label = { Text(text = "Broadcast extra key") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        AppCard(title = "Hardware keys") {
            OutlinedTextField(
                value = rfidKeyCodes,
                onValueChange = { rfidKeyCodes = it },
                label = { Text(text = "RFID key codes") },
                supportingText = { Text(text = "Comma-separated key codes for RFID trigger.") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = scanKeyCodes,
                onValueChange = { scanKeyCodes = it },
                label = { Text(text = "Scan key codes") },
                supportingText = { Text(text = "Comma-separated key codes for QR scan.") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        AppCard(title = "Find") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Find Debug Overlay", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Show Geiger debug details in the Find screen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = settings.showFindDebugOverlay,
                    onCheckedChange = viewModel::toggleFindDebugOverlay,
                )
            }
        }

        AppCard(title = "Diagnostics") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Show Diagnostics tab", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Add Diagnostics to the bottom bar.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = settings.showDiagnosticsTab,
                    onCheckedChange = { enabled ->
                        if (!enabled) {
                            pendingOpenDiagnostics = false
                        }
                        viewModel.toggleShowDiagnosticsTab(enabled)
                    },
                )
            }
            SecondaryButton(
                text = "Open Diagnostics",
                onClick = {
                    if (settings.showDiagnosticsTab) {
                        onOpenDiagnostics()
                    } else {
                        pendingOpenDiagnostics = true
                        viewModel.toggleShowDiagnosticsTab(true)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        AppCard(title = "Sync") {
            val lastSync = lastSyncState
            if (lastSync == null) {
                Text(text = "Last sync: --")
            } else {
                Text(text = "Last sync: ${formatTimestamp(lastSync.lastSyncAt)}")
                Text(text = "Last status: ${lastSync.lastSyncStatus.name.lowercase()}")
                if (!lastSync.lastErrorMessage.isNullOrBlank()) {
                    Text(text = "Last error: ${lastSync.lastErrorMessage}")
                }
            }

            when (syncStatus) {
                is SyncStatusState.Idle -> {
                    Text(text = "Sync status: Idle")
                }

                is SyncStatusState.Running -> {
                    val progress = (syncStatus as SyncStatusState.Running).progress
                    LoadingState(
                        message =
                            "Syncing... fetched=${progress.fetchedCount} " +
                                "stored=${progress.storedCount} " +
                                "skipped=${progress.skippedCount}",
                    )
                }

                is SyncStatusState.Completed -> {
                    val result = (syncStatus as SyncStatusState.Completed).result
                    Text(text = "Sync complete.")
                    Text(
                        text =
                            "Fetched: ${result.fetchedCount} | " +
                                "Stored: ${result.storedCount} | " +
                                "Skipped: ${result.skippedCount}",
                    )
                }

                is SyncStatusState.Error -> {
                    val message = (syncStatus as SyncStatusState.Error).message
                    ErrorState(message = message)
                }
            }

            PrimaryButton(
                text = "Sync Library",
                onClick = viewModel::syncNow,
                modifier = Modifier.fillMaxWidth(),
                enabled = syncStatus !is SyncStatusState.Running,
            )
        }

        PrimaryButton(
            text = "Save settings",
            onClick = {
                viewModel.saveSettings(
                    AppSettings(
                        mementoBaseUrl = baseUrl,
                        mementoToken = token,
                        mementoLibraryId = libraryId,
                        uhfRegion = region,
                        uhfPower = power.toInt(),
                        scan2dAction = scanAction,
                        scan2dExtraKey = scanExtraKey,
                        rfidKeyCodes = rfidKeyCodes,
                        scanKeyCodes = scanKeyCodes,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatTimestamp(epochMs: Long): String {
    val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    return formatter.format(Date(epochMs))
}

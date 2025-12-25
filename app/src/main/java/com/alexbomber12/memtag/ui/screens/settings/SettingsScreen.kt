@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexbomber12.memtag.data.AppDefaults
import com.alexbomber12.memtag.data.settings.AppSettings
import com.alexbomber12.memtag.ui.components.AppCard
import com.alexbomber12.memtag.ui.components.PrimaryButton

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()

    var baseUrl by rememberSaveable { mutableStateOf(settings.mementoBaseUrl) }
    var token by rememberSaveable { mutableStateOf(settings.mementoToken) }
    var libraryId by rememberSaveable { mutableStateOf(settings.mementoLibraryId) }
    var region by rememberSaveable { mutableStateOf(settings.uhfRegion) }
    var power by rememberSaveable { mutableStateOf(settings.uhfPower.toFloat()) }
    var scanAction by rememberSaveable { mutableStateOf(settings.scan2dAction) }
    var scanExtraKey by rememberSaveable { mutableStateOf(settings.scan2dExtraKey) }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    var regionExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(settings) {
        baseUrl = settings.mementoBaseUrl
        token = settings.mementoToken
        libraryId = settings.mementoLibraryId
        region = settings.uhfRegion
        power = settings.uhfPower.toFloat()
        scanAction = settings.scan2dAction
        scanExtraKey = settings.scan2dExtraKey
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
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

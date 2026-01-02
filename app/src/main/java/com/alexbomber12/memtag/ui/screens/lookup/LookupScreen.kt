@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.screens.lookup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexbomber12.memtag.app.HardwareAction
import com.alexbomber12.memtag.domain.InventoryItem
import com.alexbomber12.memtag.ui.components.ErrorState
import com.alexbomber12.memtag.ui.components.LoadingState
import com.alexbomber12.memtag.ui.components.PrimaryButton
import com.alexbomber12.memtag.ui.components.SecondaryButton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

@Composable
fun LookupScreen(
    viewModel: LookupViewModel,
    hardwareActions: Flow<HardwareAction>,
    onNavigateToVerify: () -> Unit,
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val selectedLabel =
        remember(state.selectedEpc, state.results) {
            val selectedItem = state.results.firstOrNull { it.epcNormalized == state.selectedEpc }
            val name = selectedItem?.name?.takeIf { it.isNotBlank() }
            name ?: state.selectedEpc
        }

    DisposableEffect(Unit) {
        onDispose { viewModel.cancelUhfScan() }
    }
    LaunchedEffect(hardwareActions) {
        hardwareActions.collect { action ->
            when (action) {
                HardwareAction.Rfid -> viewModel.scanUhf()
                HardwareAction.Scan -> viewModel.scanQr()
            }
        }
    }

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Column {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outline),
                    )
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::updateQuery,
                            label = { Text(text = "Search") },
                            placeholder = {
                                Text(
                                    text = "Name, EPC, status...",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors =
                                OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.outline,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                    focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    cursorColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            trailingIcon = {
                                if (state.query.isNotBlank()) {
                                    IconButton(onClick = { viewModel.updateQuery("") }) {
                                        Icon(
                                            imageVector = Icons.Filled.Clear,
                                            contentDescription = "Clear search",
                                        )
                                    }
                                }
                            },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            PrimaryButton(
                                text = "Scan RFID",
                                onClick = viewModel::scanUhf,
                                enabled = state.uhfScanStatus !is ScanUhfStatus.Scanning,
                                modifier = Modifier.weight(1f),
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.CenterFocusStrong,
                                        contentDescription = "Scan RFID",
                                    )
                                },
                            )
                            SecondaryButton(
                                text = "Scan QR",
                                onClick = viewModel::scanQr,
                                enabled = state.scanStatus !is ScanQrStatus.Scanning,
                                modifier = Modifier.weight(1f),
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.QrCodeScanner,
                                        contentDescription = "Scan QR",
                                    )
                                },
                            )
                        }
                        when (val uhfStatus = state.uhfScanStatus) {
                            is ScanUhfStatus.Scanning -> {
                                LoadingState(
                                    message = "Scanning RFID...",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            is ScanUhfStatus.Error -> {
                                ErrorState(
                                    message = uhfStatus.message,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            is ScanUhfStatus.Idle -> Unit
                        }
                        when (val scanStatus = state.scanStatus) {
                            is ScanQrStatus.Scanning -> {
                                LoadingState(
                                    message = "Scanning QR...",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            is ScanQrStatus.Error -> {
                                ErrorState(
                                    message = scanStatus.message,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            is ScanQrStatus.Idle -> Unit
                        }
                        if (selectedLabel != null) {
                            Text(
                                text = "Selected: $selectedLabel",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }

        state.searchError?.let { message ->
            item {
                ErrorState(
                    message = message,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (state.query.isBlank()) {
            item {
                Text(
                    text = "Type to search the library.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else if (state.isSearching) {
            item {
                LoadingState(
                    message = "Searching...",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else if (state.results.isEmpty()) {
            item {
                Text(
                    text = "Not found. Sync in Settings may be required.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "RESULTS",
                        style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { viewModel.updateQuery("") },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text(text = "Clear All")
                    }
                }
            }
            items(state.results, key = { it.entryId }) { item ->
                LookupResultRow(
                    item = item,
                    isSelected = item.epcNormalized == state.selectedEpc,
                    onClick = { viewModel.selectItem(item) },
                    onVerify = {
                        viewModel.selectItem(item)
                        onNavigateToVerify()
                    },
                )
            }
        }
    }
}

@Composable
private fun LookupResultRow(
    item: InventoryItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onVerify: () -> Unit,
) {
    val containerColor =
        if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    val name = item.name?.takeIf { it.isNotBlank() } ?: "(no name)"
    val status = item.status?.takeIf { it.isNotBlank() } ?: "(none)"
    val location = item.locationPath?.takeIf { it.isNotBlank() } ?: "(none)"
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Name: $name",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "EPC: ${item.epcNormalized}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "Status: $status",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Location: $location",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(
                onClick = onVerify,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(text = "Verify")
            }
        }
    }
}

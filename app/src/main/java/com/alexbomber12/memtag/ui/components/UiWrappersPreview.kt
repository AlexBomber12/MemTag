@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.alexbomber12.memtag.ui.theme.MemTagTheme

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun UiWrappersPreview() {
    MemTagTheme {
        AppScaffold(
            title = "UI Wrappers",
            navigationIcon = {
                IconButton(onClick = {}) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            actions = {
                IconButton(onClick = {}) {
                    Icon(imageVector = Icons.Filled.MoreVert, contentDescription = null)
                }
            },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SectionCard(
                    title = "Summary",
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatChip(label = "Total: 9")
                        StatChip(label = "Found: 6")
                        StatChip(label = "Missing: 3")
                    }
                }
                SectionCard(
                    title = "Actions",
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PrimaryButton(text = "Primary action", onClick = {})
                    SecondaryButton(text = "Secondary action", onClick = {})
                }
            }
        }
    }
}

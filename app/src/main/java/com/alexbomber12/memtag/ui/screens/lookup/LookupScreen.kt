@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.screens.lookup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alexbomber12.memtag.ui.components.AppCard
import com.alexbomber12.memtag.ui.components.PrimaryButton

@Composable
fun LookupScreen() {
    var status by rememberSaveable { mutableStateOf("Ready to search") }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppCard(title = "Lookup") {
            Text(
                text = "Search by EPC, barcode, or SKU to find inventory quickly.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Status: $status",
                style = MaterialTheme.typography.labelMedium,
            )
            PrimaryButton(
                text = "Start lookup",
                onClick = { status = "Lookup started" },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

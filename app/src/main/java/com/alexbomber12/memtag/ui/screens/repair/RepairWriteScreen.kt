@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.screens.repair

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.alexbomber12.memtag.ui.components.ErrorState

@Composable
fun RepairWriteScreen() {
    var attempts by rememberSaveable { mutableStateOf(0) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppCard(title = "Repair & Write") {
            Text(
                text = "Rewrite tag data or fix mismatched fields.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ErrorState(
                message = "No tag selected yet.",
                actionLabel = "Retry",
                onAction = { attempts += 1 },
            )
            Text(
                text = "Retry attempts: $attempts",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

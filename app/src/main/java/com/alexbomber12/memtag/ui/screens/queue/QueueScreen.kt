@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.screens.queue

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
import com.alexbomber12.memtag.ui.components.SecondaryButton

@Composable
fun QueueScreen() {
    var queuedItems by rememberSaveable { mutableStateOf(3) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppCard(title = "Queue") {
            Text(
                text = "Pending actions ready to sync.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Queued items: $queuedItems",
                style = MaterialTheme.typography.labelMedium,
            )
            SecondaryButton(
                text = "Clear queue",
                onClick = { queuedItems = 0 },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

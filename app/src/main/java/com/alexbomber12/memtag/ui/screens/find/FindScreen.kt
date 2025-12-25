@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.screens.find

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
import com.alexbomber12.memtag.ui.components.LoadingState
import com.alexbomber12.memtag.ui.components.PrimaryButton
import com.alexbomber12.memtag.ui.components.SecondaryButton

@Composable
fun FindScreen() {
    var scanning by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppCard(title = "Find") {
            if (scanning) {
                LoadingState(
                    message = "Listening for nearby tags...",
                    modifier = Modifier.fillMaxWidth(),
                )
                SecondaryButton(
                    text = "Stop find",
                    onClick = { scanning = false },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = "Start a guided find session to locate a tag.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PrimaryButton(
                    text = "Start find",
                    onClick = { scanning = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

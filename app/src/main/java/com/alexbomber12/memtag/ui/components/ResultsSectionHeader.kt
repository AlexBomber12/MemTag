@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ResultsHeaderVerticalPadding = 6.dp

@Composable
fun ResultsSectionHeader(
    label: String,
    onClear: () -> Unit,
    canClear: Boolean,
    modifier: Modifier = Modifier,
    verticalPadding: Dp = ResultsHeaderVerticalPadding,
) {
    Column(modifier = modifier) {
        Spacer(modifier = Modifier.height(verticalPadding))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onClear,
                enabled = canClear,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(text = "Clear All")
            }
        }
        Spacer(modifier = Modifier.height(verticalPadding))
    }
}

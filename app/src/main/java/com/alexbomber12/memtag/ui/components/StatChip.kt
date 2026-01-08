@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.components

import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

enum class StatChipTone {
    Neutral,
    Found,
    Extra,
    Unknown,
}

@Composable
fun StatChip(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    tone: StatChipTone = StatChipTone.Neutral,
) {
    val (containerColor, labelColor, borderColor) = statChipColors(tone)
    AssistChip(
        onClick = onClick,
        label = { Text(text = label, style = MaterialTheme.typography.labelSmall) },
        modifier = modifier,
        colors =
            AssistChipDefaults.assistChipColors(
                containerColor = containerColor,
                labelColor = labelColor,
            ),
        border =
            AssistChipDefaults.assistChipBorder(
                enabled = true,
                borderColor = borderColor,
            ),
    )
}

@Composable
private fun statChipColors(tone: StatChipTone): Triple<Color, Color, Color> {
    return when (tone) {
        StatChipTone.Found ->
            Triple(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.primary,
            )
        StatChipTone.Extra ->
            Triple(
                MaterialTheme.colorScheme.tertiaryContainer,
                MaterialTheme.colorScheme.tertiary,
                MaterialTheme.colorScheme.tertiary,
            )
        StatChipTone.Unknown ->
            Triple(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.onSurfaceVariant,
                MaterialTheme.colorScheme.outline,
            )
        StatChipTone.Neutral ->
            Triple(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.onSurfaceVariant,
                MaterialTheme.colorScheme.outline,
            )
    }
}

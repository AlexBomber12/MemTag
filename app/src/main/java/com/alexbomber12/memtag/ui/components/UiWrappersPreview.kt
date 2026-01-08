@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.alexbomber12.memtag.ui.theme.MemTagTheme

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ButtonsPreview() {
    MemTagTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrimaryButton(text = "Primary action", onClick = {})
            SecondaryButton(text = "Secondary action", onClick = {})
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun StatChipsPreview() {
    MemTagTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatChip(label = "Idle", tone = StatChipTone.Neutral)
            StatChip(label = "Found", tone = StatChipTone.Found)
            StatChip(label = "Extra", tone = StatChipTone.Extra)
            StatChip(label = "Unknown", tone = StatChipTone.Unknown)
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun CardPreview() {
    MemTagTheme {
        SectionCard(
            title = "Sample card",
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            content = {
                Text(text = "Card content goes here.")
            },
        )
    }
}

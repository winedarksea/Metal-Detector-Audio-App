package com.metaldetectoraudioapp.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.metaldetectoraudioapp.app.ui.theme.Spacing

/**
 * Guidance for capturing good ML training data, shown atop the recording screen.
 *
 * Rendered in the brand "gold" tertiary tone — the same highlight used for a
 * found target — so the tips read as helpful rather than as a warning. Shared by
 * the web (PWA) and desktop recording screens; the Android screen keeps its own
 * copy in line with the rest of its UI.
 */
@Composable
fun RecordingHintCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                "Tips for good training data",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            RecordingHintBullet(
                "Record only the target signal, with as little extra audio as possible — aim for clips just a second or two long."
            )
            RecordingHintBullet(
                "If several objects share one signal, add a label for each, but keep separate recordings for separate objects where you can."
            )
        }
    }
}

@Composable
private fun RecordingHintBullet(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Text("•", style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

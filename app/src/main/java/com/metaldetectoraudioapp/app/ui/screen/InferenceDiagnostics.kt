package com.metaldetectoraudioapp.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.metaldetectoraudioapp.app.inference.InferenceBackendPreference
import com.metaldetectoraudioapp.app.inference.InferenceUiState
import com.metaldetectoraudioapp.app.ui.theme.Spacing

@Composable
internal fun HardwareAccelerationPreferenceControl(
    backendPreference: InferenceBackendPreference,
    onHardwareAccelerationEnabledChanged: (Boolean) -> Unit,
) {
    val hardwareAccelerationEnabled =
        backendPreference == InferenceBackendPreference.HARDWARE_ACCELERATION

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = hardwareAccelerationEnabled,
                role = Role.Switch,
                onValueChange = onHardwareAccelerationEnabledChanged,
            )
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Hardware Acceleration",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                "Disable to use the model on CPU",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = hardwareAccelerationEnabled,
            onCheckedChange = null,
        )
    }
}

@Composable
internal fun InferenceScoreDiagnostics(uiState: InferenceUiState) {
    if (uiState.perLabelScores.isNotEmpty()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier.padding(top = Spacing.xs),
        ) {
            Text(
                "Model Output",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            uiState.perLabelScores.forEach { (label, score) ->
                ModelOutputScoreRow(label = label, score = score)
            }
        }
    }
    uiState.lastInferenceError?.let { error ->
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = RoundedCornerShape(Spacing.sm),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(Spacing.sm),
            )
        }
    }
}

@Composable
private fun ModelOutputScoreRow(label: String, score: Float) {
    val boundedScore = score.coerceIn(0f, 1f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(72.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            LinearProgressIndicator(
                progress = { boundedScore },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
            )
        }
        Text(
            "${"%.1f".format(boundedScore * 100f)}%",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(48.dp),
        )
    }
}

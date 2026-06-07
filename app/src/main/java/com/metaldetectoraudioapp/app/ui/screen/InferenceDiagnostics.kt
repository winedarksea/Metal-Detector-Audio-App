package com.metaldetectoraudioapp.app.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.metaldetectoraudioapp.app.inference.InferenceBackendPreference
import com.metaldetectoraudioapp.app.inference.InferenceUiState

@Composable
internal fun HardwareAccelerationPreferenceControl(
    backendPreference: InferenceBackendPreference,
    onHardwareAccelerationEnabledChanged: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Hardware Acceleration",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                "Disable to use the waveform model on CPU",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = backendPreference == InferenceBackendPreference.HARDWARE_ACCELERATION,
            onCheckedChange = onHardwareAccelerationEnabledChanged,
        )
    }
}

@Composable
internal fun InferenceScoreDiagnostics(uiState: InferenceUiState) {
    if (uiState.perLabelScores.isNotEmpty()) {
        Text(
            uiState.perLabelScores.entries.joinToString("  ") { (label, score) ->
                "$label=${"%.3f".format(score)}"
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
    uiState.lastInferenceError?.let { error ->
        Text(
            error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

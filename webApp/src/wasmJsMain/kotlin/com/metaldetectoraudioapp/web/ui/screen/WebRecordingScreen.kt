package com.metaldetectoraudioapp.web.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.DETECTOR_MODEL_OPTIONS
import com.metaldetectoraudioapp.app.ui.model.SEARCH_MODE_OPTIONS
import com.metaldetectoraudioapp.app.ui.model.SweepPattern
import com.metaldetectoraudioapp.app.ui.screen.RecordingHintCard
import com.metaldetectoraudioapp.app.ui.theme.Spacing
import com.metaldetectoraudioapp.web.viewmodel.WebRecordingViewModel

@Composable
fun WebRecordingScreen(
    viewModel: WebRecordingViewModel,
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    val uiState by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = Modifier.padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item {
            RecordingHintCard()
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text("Capture", style = MaterialTheme.typography.titleMedium)
                    MicSelector(modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Button(onClick = viewModel::startRecording, enabled = !uiState.isRecording) {
                            Text("Start")
                        }
                        FilledTonalButton(onClick = viewModel::stopRecording, enabled = uiState.isRecording) {
                            Text("Stop")
                        }
                        OutlinedButton(
                            onClick = {
                                if (uiState.isPlayingPreview) viewModel.stopPreview() else viewModel.playPreview()
                            },
                            enabled = uiState.pendingAudio != null
                        ) {
                            Text(if (uiState.isPlayingPreview) "Stop Preview" else "Play Preview")
                        }
                        OutlinedButton(
                            onClick = viewModel::clearPendingCapture,
                            enabled = uiState.pendingAudio != null
                        ) {
                            Text("Clear")
                        }
                    }
                    Text(
                        "Duration: ${uiState.pendingDurationMs} ms",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Microphone access is requested when the app opens. Tap the detector coil against a target during recording.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text("Labels", style = MaterialTheme.typography.titleMedium)

                    WebLabelPickerField(
                        value = uiState.draft.targetNameInput,
                        onValueChange = viewModel::updateTargetNames,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = uiState.draft.depthInches,
                        onValueChange = viewModel::updateDepthInches,
                        label = { Text("depth_inches (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    OutlinedTextField(
                        value = uiState.draft.notesInput,
                        onValueChange = viewModel::updateNotes,
                        label = { Text("notes (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                    )

                    Text(
                        "Each object needs a TARGET or JUNK label. Use AMBIENT only when no " +
                            "identifiable object is present. Keep all sounds within the same " +
                            "1-second window; record sounds farther apart as separate files, " +
                            "and avoid a full second of empty audio in non-ambient recordings.",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    Text("pattern", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        SweepPattern.entries.forEach { pattern ->
                            FilterChip(
                                selected = uiState.draft.pattern == pattern,
                                onClick = { viewModel.updatePattern(pattern) },
                                label = { Text(pattern.name) }
                            )
                        }
                    }

                    if (
                        uiState.draft.targetNameInput.contains("TARGET@") &&
                        uiState.draft.targetNameInput.contains("JUNK@")
                    ) {
                        Text(
                            "mixed_target_and_junk: true",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = uiState.draft.includeInTraining,
                            onCheckedChange = viewModel::updateIncludeInTraining,
                        )
                        Text("include_in_training", style = MaterialTheme.typography.labelLarge)
                    }

                    uiState.saveResultMessage?.let { msg ->
                        Text(msg, color = MaterialTheme.colorScheme.primary)
                    }
                    uiState.errorMessage?.let { err ->
                        Text(err, color = MaterialTheme.colorScheme.error)
                    }
                    Button(onClick = viewModel::saveRecording, enabled = uiState.pendingAudio != null) {
                        Text("Save Recording")
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("Environment (optional)", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = uiState.draft.soilType,
                        onValueChange = viewModel::updateSoilType,
                        label = { Text("soil_type") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Text("moisture", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        listOf("dry", "moist", "wet").forEach { option ->
                            FilterChip(
                                selected = uiState.draft.moisture == option,
                                onClick = { viewModel.updateMoisture(if (uiState.draft.moisture == option) "" else option) },
                                label = { Text(option) }
                            )
                        }
                    }
                    WebDropdownField(
                        label = "detector_model",
                        value = uiState.draft.detectorModel,
                        suggestions = DETECTOR_MODEL_OPTIONS,
                        onValueChange = viewModel::updateDetectorModel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    WebDropdownField(
                        label = "search_mode",
                        value = uiState.draft.searchMode,
                        suggestions = SEARCH_MODE_OPTIONS,
                        onValueChange = viewModel::updateSearchMode,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

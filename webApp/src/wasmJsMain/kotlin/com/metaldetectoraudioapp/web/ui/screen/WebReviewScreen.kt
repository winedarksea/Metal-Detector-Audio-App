package com.metaldetectoraudioapp.web.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metaldetectoraudioapp.app.recording.RecordingMetadata
import com.metaldetectoraudioapp.app.ui.model.AUDIO_PROFILE_OPTIONS
import com.metaldetectoraudioapp.app.ui.screen.AudioTrimmer
import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.DETECTOR_MODEL_OPTIONS
import com.metaldetectoraudioapp.app.ui.model.LabelEntry
import com.metaldetectoraudioapp.app.ui.model.RECOVERY_SPEED_OPTIONS
import com.metaldetectoraudioapp.app.ui.model.SEARCH_MODE_OPTIONS
import com.metaldetectoraudioapp.app.ui.model.SENSITIVITY_OPTIONS
import com.metaldetectoraudioapp.app.ui.model.STABILIZER_OPTIONS
import com.metaldetectoraudioapp.app.ui.model.serializeLabelEntries
import com.metaldetectoraudioapp.app.ui.theme.Spacing
import com.metaldetectoraudioapp.web.viewmodel.WebReviewViewModel

private val SOIL_TYPE_OPTIONS = listOf(
    "dry-sand", "wet-sand", "clay", "loam", "gravel", "mineralized", "fill", "unknown"
)
private val MOISTURE_OPTIONS = listOf("dry", "moist", "wet")

@Composable
fun WebReviewScreen(
    viewModel: WebReviewViewModel,
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    val uiState by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = Modifier.padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text("Dataset", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Recordings are stored in browser IndexedDB. Export as a zip to transfer data for training.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Button(onClick = viewModel::exportBundle) { Text("Export Bundle") }
                        FilledTonalButton(onClick = viewModel::importBundle) { Text("Import Bundle") }
                        OutlinedButton(onClick = viewModel::refresh) { Text("Refresh") }
                    }
                }
            }
        }

        uiState.message?.let { msg ->
            item { Text(msg, color = MaterialTheme.colorScheme.primary) }
        }
        uiState.errorMessage?.let { err ->
            item { Text(err, color = MaterialTheme.colorScheme.error) }
        }

        items(uiState.recordings, key = { it.recordingId }) { recording ->
            WebRecordingCard(
                recording = recording,
                isPlaying = uiState.selectedPlayingId == recording.recordingId,
                isTrimEditorOpen = uiState.trimEditId == recording.recordingId,
                trimEnvelope = uiState.trimEnvelope,
                trimStartMs = uiState.trimStartMs,
                trimEndMs = uiState.trimEndMs,
                trimFullDurationMs = uiState.trimFullDurationMs,
                isTrimmed = uiState.isTrimmed,
                onPlay = { viewModel.playOrStop(recording) },
                onOpenTrimEditor = { viewModel.openTrimEditor(recording) },
                onUpdateTrim = viewModel::updateTrim,
                onResetTrim = viewModel::resetTrim,
                onCloseTrimEditor = viewModel::closeTrimEditor,
                onSaveTrim = { viewModel.saveTrim(recording) },
                onToggleInclude = { viewModel.toggleIncludeInTraining(recording, it) },
                onRelabelTargets = { viewModel.relabelTargetNames(recording, it) },
                onRelabelNotes = { viewModel.relabelNotes(recording, it) },
                onRelabelEnvironment = { soil, moisture, detectorModel, searchMode, audioProfile, sensitivity, recoverySpeed, stabilizer ->
                    viewModel.relabelEnvironment(
                        recording = recording,
                        soilType = soil,
                        moisture = moisture,
                        detectorModel = detectorModel,
                        searchMode = searchMode,
                        audioProfile = audioProfile,
                        sensitivity = sensitivity,
                        recoverySpeed = recoverySpeed,
                        stabilizer = stabilizer,
                    )
                },
                onDelete = { viewModel.delete(recording.recordingId) },
            )
        }
    }
}

@Composable
private fun WebRecordingCard(
    recording: RecordingMetadata,
    isPlaying: Boolean,
    isTrimEditorOpen: Boolean,
    trimEnvelope: List<Float>,
    trimStartMs: Long,
    trimEndMs: Long,
    trimFullDurationMs: Long,
    isTrimmed: Boolean,
    onPlay: () -> Unit,
    onOpenTrimEditor: () -> Unit,
    onUpdateTrim: (Long, Long) -> Unit,
    onResetTrim: () -> Unit,
    onCloseTrimEditor: () -> Unit,
    onSaveTrim: () -> Unit,
    onToggleInclude: (Boolean) -> Unit,
    onRelabelTargets: (String) -> Unit,
    onRelabelNotes: (String) -> Unit,
    onRelabelEnvironment: (
        soilType: String,
        moisture: String,
        detectorModel: String,
        searchMode: String,
        audioProfile: String,
        sensitivity: String,
        recoverySpeed: String,
        stabilizer: String,
    ) -> Unit,
    onDelete: () -> Unit,
) {
    var targetInput by remember(recording.recordingId) {
        mutableStateOf(
            serializeLabelEntries(recording.objectLabels.map {
                val parts = it.targetName.split(":")
                LabelEntry(
                    obj = parts.getOrElse(0) { "" },
                    name = parts.getOrElse(1) { "" },
                    material = parts.getOrElse(2) { "" },
                    labelClass = it.labelClass,
                )
            })
        )
    }
    var notesInput by remember(recording.recordingId) {
        mutableStateOf(recording.notes.orEmpty())
    }
    var soilTypeInput by remember(recording.recordingId) {
        mutableStateOf(recording.soilType.orEmpty())
    }
    var moistureInput by remember(recording.recordingId) {
        mutableStateOf(recording.moisture.orEmpty())
    }
    var detectorModelInput by remember(recording.recordingId) {
        mutableStateOf(recording.detectorModel.orEmpty())
    }
    var searchModeInput by remember(recording.recordingId) {
        mutableStateOf(recording.searchMode.orEmpty())
    }
    var audioProfileInput by remember(recording.recordingId) {
        mutableStateOf(recording.audioProfile.orEmpty())
    }
    var sensitivityInput by remember(recording.recordingId) {
        mutableStateOf(recording.sensitivity.orEmpty())
    }
    var recoverySpeedInput by remember(recording.recordingId) {
        mutableStateOf(recording.recoverySpeed.orEmpty())
    }
    var stabilizerInput by remember(recording.recordingId) {
        mutableStateOf(recording.stabilizer.orEmpty())
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(recording.audioFileName, style = MaterialTheme.typography.titleSmall)
            Text(
                "Class: ${recording.classLabel.name} | Pattern: ${recording.pattern.name} | ${recording.durationMs} ms",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Photo: ${recording.imageFileName ?: "none"}",
                style = MaterialTheme.typography.bodySmall,
            )
            val gpsLatitude = recording.gpsLatitude
            val gpsLongitude = recording.gpsLongitude
            Text(
                text = if (gpsLatitude == null || gpsLongitude == null) {
                    "GPS: not set"
                } else {
                    "GPS: ${formatReviewCoordinate(gpsLatitude)}, ${formatReviewCoordinate(gpsLongitude)}"
                },
                style = MaterialTheme.typography.bodySmall,
            )

            WebLabelPickerField(
                value = targetInput,
                onValueChange = { targetInput = it },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = notesInput,
                onValueChange = { notesInput = it },
                label = { Text("notes") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FilledTonalButton(onClick = { onRelabelTargets(targetInput) }) { Text("Apply Names") }
                FilledTonalButton(onClick = { onRelabelNotes(notesInput) }) { Text("Apply Notes") }
                OutlinedButton(onClick = onPlay) { Text(if (isPlaying) "Stop" else "Play") }
                OutlinedButton(onClick = onDelete) { Text("Delete") }
                if (!isTrimEditorOpen) {
                    OutlinedButton(onClick = onOpenTrimEditor) { Text("Trim") }
                }
            }
            if (isTrimEditorOpen) {
                AudioTrimmer(
                    envelope = trimEnvelope,
                    durationMs = trimFullDurationMs,
                    trimStartMs = trimStartMs,
                    trimEndMs = trimEndMs,
                    onTrimChange = onUpdateTrim,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "$trimStartMs – $trimEndMs ms  (${trimFullDurationMs} ms total)",
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TextButton(onClick = onResetTrim, enabled = isTrimmed) { Text("Reset") }
                    TextButton(onClick = onCloseTrimEditor) { Text("Cancel") }
                    FilledTonalButton(onClick = onSaveTrim) { Text("Save Trim") }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = recording.includeInTraining,
                    onCheckedChange = onToggleInclude
                )
                Text("include_in_training", style = MaterialTheme.typography.labelLarge)
            }

            WebSuggestiveTextField(
                label = "soil_type",
                value = soilTypeInput,
                suggestions = SOIL_TYPE_OPTIONS,
                onValueChange = { soilTypeInput = it },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("moisture", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                MOISTURE_OPTIONS.forEach { option ->
                    FilterChip(
                        selected = moistureInput == option,
                        onClick = { moistureInput = if (moistureInput == option) "" else option },
                        label = { Text(option) },
                    )
                }
            }

            WebSuggestiveTextField(
                label = "detector_model",
                value = detectorModelInput,
                suggestions = DETECTOR_MODEL_OPTIONS,
                onValueChange = { detectorModelInput = it },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Custom detector model values are allowed.", style = MaterialTheme.typography.bodySmall)

            WebSuggestiveTextField(
                label = "search_mode",
                value = searchModeInput,
                suggestions = SEARCH_MODE_OPTIONS,
                onValueChange = { searchModeInput = it },
                modifier = Modifier.fillMaxWidth(),
            )

            WebSuggestiveTextField(
                label = "audio_profile",
                value = audioProfileInput,
                suggestions = AUDIO_PROFILE_OPTIONS,
                onValueChange = { audioProfileInput = it },
                modifier = Modifier.fillMaxWidth(),
            )

            WebSuggestiveTextField(
                label = "sensitivity",
                value = sensitivityInput,
                suggestions = SENSITIVITY_OPTIONS,
                onValueChange = { sensitivityInput = it },
                modifier = Modifier.fillMaxWidth(),
            )

            WebSuggestiveTextField(
                label = "recovery_speed",
                value = recoverySpeedInput,
                suggestions = RECOVERY_SPEED_OPTIONS,
                onValueChange = { recoverySpeedInput = it },
                modifier = Modifier.fillMaxWidth(),
            )

            WebSuggestiveTextField(
                label = "stabilizer",
                value = stabilizerInput,
                suggestions = STABILIZER_OPTIONS,
                onValueChange = { stabilizerInput = it },
                modifier = Modifier.fillMaxWidth(),
            )

            FilledTonalButton(
                onClick = {
                    onRelabelEnvironment(
                        soilTypeInput,
                        moistureInput,
                        detectorModelInput,
                        searchModeInput,
                        audioProfileInput,
                        sensitivityInput,
                        recoverySpeedInput,
                        stabilizerInput,
                    )
                },
            ) {
                Text("Apply Environment")
            }
        }
    }
}

private fun formatReviewCoordinate(value: Double): String = js("value.toFixed(6)")

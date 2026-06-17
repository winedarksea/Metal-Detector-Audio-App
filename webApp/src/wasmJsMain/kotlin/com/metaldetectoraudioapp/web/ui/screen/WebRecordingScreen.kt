package com.metaldetectoraudioapp.web.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metaldetectoraudioapp.app.ui.model.AUDIO_PROFILE_OPTIONS
import com.metaldetectoraudioapp.app.ui.model.DETECTOR_MODEL_OPTIONS
import com.metaldetectoraudioapp.app.ui.model.RECOVERY_SPEED_OPTIONS
import com.metaldetectoraudioapp.app.ui.model.SEARCH_MODE_OPTIONS
import com.metaldetectoraudioapp.app.ui.model.SENSITIVITY_OPTIONS
import com.metaldetectoraudioapp.app.ui.model.STABILIZER_OPTIONS
import com.metaldetectoraudioapp.app.ui.model.SweepPattern
import com.metaldetectoraudioapp.app.ui.screen.AudioTrimmer
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
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item {
            RecordingHintCard()
        }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text("Capture", style = MaterialTheme.typography.titleMedium)
                    MicSelector(modifier = Modifier.fillMaxWidth())
                    WrappingActionRow {
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
                            enabled = uiState.pendingAudio != null,
                        ) {
                            Text(if (uiState.isPlayingPreview) "Stop Preview" else "Play Preview")
                        }
                        OutlinedButton(
                            onClick = viewModel::clearPendingCapture,
                            enabled = uiState.pendingAudio != null || uiState.pendingImage != null,
                        ) {
                            Text("Clear")
                        }
                    }
                    if (uiState.pendingAudio != null && !uiState.isRecording) {
                        AudioTrimmer(
                            envelope = uiState.clipEnvelope,
                            durationMs = uiState.pendingDurationMs,
                            trimStartMs = uiState.trimStartMs,
                            trimEndMs = uiState.trimEndMs,
                            onTrimChange = viewModel::updateTrim,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "Trim ${uiState.trimStartMs}–${uiState.trimEndMs} ms (${uiState.trimEndMs - uiState.trimStartMs} ms kept)",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = viewModel::resetTrim, enabled = uiState.isTrimmed) {
                                Text("Reset")
                            }
                        }
                    }
                    Text(
                        "Duration: ${uiState.pendingDurationMs} ms",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Microphone access is requested when the app opens. Tap the detector coil against a target during recording.",
                        style = MaterialTheme.typography.bodySmall,
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

                    HorizontalDivider()

                    WrappingActionRow {
                        OutlinedButton(
                            onClick = viewModel::capturePhoto,
                            enabled = !uiState.isRecording && !uiState.isPhotoCaptureInProgress,
                        ) {
                            if (uiState.isPhotoCaptureInProgress) {
                                LoadingButtonContent("Processing Photo")
                            } else {
                                Text(if (uiState.pendingImage != null) "Replace Photo" else "Add Photo")
                            }
                        }
                        if (uiState.pendingImage != null) {
                            TextButton(onClick = viewModel::removePendingPhoto) {
                                Text("Remove Photo")
                            }
                        }
                    }
                    uiState.pendingImage?.let { pendingImage ->
                        Text(
                            "Photo attached: JPEG, ${formatByteCount(pendingImage.bytes.size)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    WrappingActionRow {
                        OutlinedButton(
                            onClick = viewModel::captureCurrentLocation,
                            enabled = !uiState.isLocationCaptureInProgress,
                        ) {
                            if (uiState.isLocationCaptureInProgress) {
                                LoadingButtonContent("Finding Location")
                            } else {
                                Text("Use Current GPS")
                            }
                        }
                        if (uiState.draft.gpsLatitude != null && uiState.draft.gpsLongitude != null) {
                            TextButton(onClick = viewModel::clearCurrentLocation) {
                                Text("Clear GPS")
                            }
                        }
                    }
                    val gpsLatitude = uiState.draft.gpsLatitude
                    val gpsLongitude = uiState.draft.gpsLongitude
                    Text(
                        text = if (gpsLatitude == null || gpsLongitude == null) {
                            "GPS: not set"
                        } else {
                            "GPS: ${formatCoordinate(gpsLatitude)}, ${formatCoordinate(gpsLongitude)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )

                    HorizontalDivider()

                    Text(
                        "Each object needs a TARGET or JUNK label. Use AMBIENT only when no " +
                            "identifiable object is present. Keep all sounds within the same " +
                            "1-second window; record sounds farther apart as separate files, " +
                            "and avoid a full second of empty audio in non-ambient recordings.",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    Text("pattern", style = MaterialTheme.typography.labelLarge)
                    WrappingActionRow {
                        SweepPattern.entries.forEach { pattern ->
                            FilterChip(
                                selected = uiState.draft.pattern == pattern,
                                onClick = { viewModel.updatePattern(pattern) },
                                label = { Text(pattern.name) },
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
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }

                    HorizontalDivider()

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = uiState.draft.includeInTraining,
                            onCheckedChange = viewModel::updateIncludeInTraining,
                        )
                        Text("include_in_training", style = MaterialTheme.typography.labelLarge)
                    }

                    uiState.saveResultMessage?.let { msg ->
                        WebStatusBanner(message = msg, isError = false)
                    }
                    uiState.errorMessage?.let { err ->
                        WebStatusBanner(message = err, isError = true)
                    }
                    Button(
                        onClick = viewModel::saveRecording,
                        enabled = uiState.pendingAudio != null &&
                            !uiState.isPhotoCaptureInProgress &&
                            !uiState.isLocationCaptureInProgress,
                    ) {
                        Text("Save Recording")
                    }
                }
            }
        }

        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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
                    WrappingActionRow {
                        listOf("dry", "moist", "wet").forEach { option ->
                            FilterChip(
                                selected = uiState.draft.moisture == option,
                                onClick = {
                                    viewModel.updateMoisture(if (uiState.draft.moisture == option) "" else option)
                                },
                                label = { Text(option) },
                            )
                        }
                    }
                    AdaptiveDetectorFields(
                        detectorModel = uiState.draft.detectorModel,
                        searchMode = uiState.draft.searchMode,
                        audioProfile = uiState.draft.audioProfile,
                        sensitivity = uiState.draft.sensitivity,
                        recoverySpeed = uiState.draft.recoverySpeed,
                        stabilizer = uiState.draft.stabilizer,
                        onDetectorModelChange = viewModel::updateDetectorModel,
                        onSearchModeChange = viewModel::updateSearchMode,
                        onAudioProfileChange = viewModel::updateAudioProfile,
                        onSensitivityChange = viewModel::updateSensitivity,
                        onRecoverySpeedChange = viewModel::updateRecoverySpeed,
                        onStabilizerChange = viewModel::updateStabilizer,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WrappingActionRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        itemVerticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun AdaptiveDetectorFields(
    detectorModel: String,
    searchMode: String,
    audioProfile: String,
    sensitivity: String,
    recoverySpeed: String,
    stabilizer: String,
    onDetectorModelChange: (String) -> Unit,
    onSearchModeChange: (String) -> Unit,
    onAudioProfileChange: (String) -> Unit,
    onSensitivityChange: (String) -> Unit,
    onRecoverySpeedChange: (String) -> Unit,
    onStabilizerChange: (String) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth >= 720.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    WebSuggestiveTextField(
                        label = "detector_model",
                        value = detectorModel,
                        suggestions = DETECTOR_MODEL_OPTIONS,
                        onValueChange = onDetectorModelChange,
                        modifier = Modifier.weight(1f),
                    )
                    WebSuggestiveTextField(
                        label = "search_mode",
                        value = searchMode,
                        suggestions = SEARCH_MODE_OPTIONS,
                        onValueChange = onSearchModeChange,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    WebSuggestiveTextField(
                        label = "audio_profile",
                        value = audioProfile,
                        suggestions = AUDIO_PROFILE_OPTIONS,
                        onValueChange = onAudioProfileChange,
                        modifier = Modifier.weight(1f),
                    )
                    WebSuggestiveTextField(
                        label = "sensitivity",
                        value = sensitivity,
                        suggestions = SENSITIVITY_OPTIONS,
                        onValueChange = onSensitivityChange,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    WebSuggestiveTextField(
                        label = "recovery_speed",
                        value = recoverySpeed,
                        suggestions = RECOVERY_SPEED_OPTIONS,
                        onValueChange = onRecoverySpeedChange,
                        modifier = Modifier.weight(1f),
                    )
                    WebSuggestiveTextField(
                        label = "stabilizer",
                        value = stabilizer,
                        suggestions = STABILIZER_OPTIONS,
                        onValueChange = onStabilizerChange,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                WebSuggestiveTextField(
                    label = "detector_model",
                    value = detectorModel,
                    suggestions = DETECTOR_MODEL_OPTIONS,
                    onValueChange = onDetectorModelChange,
                    modifier = Modifier.fillMaxWidth(),
                )
                WebSuggestiveTextField(
                    label = "search_mode",
                    value = searchMode,
                    suggestions = SEARCH_MODE_OPTIONS,
                    onValueChange = onSearchModeChange,
                    modifier = Modifier.fillMaxWidth(),
                )
                WebSuggestiveTextField(
                    label = "audio_profile",
                    value = audioProfile,
                    suggestions = AUDIO_PROFILE_OPTIONS,
                    onValueChange = onAudioProfileChange,
                    modifier = Modifier.fillMaxWidth(),
                )
                WebSuggestiveTextField(
                    label = "sensitivity",
                    value = sensitivity,
                    suggestions = SENSITIVITY_OPTIONS,
                    onValueChange = onSensitivityChange,
                    modifier = Modifier.fillMaxWidth(),
                )
                WebSuggestiveTextField(
                    label = "recovery_speed",
                    value = recoverySpeed,
                    suggestions = RECOVERY_SPEED_OPTIONS,
                    onValueChange = onRecoverySpeedChange,
                    modifier = Modifier.fillMaxWidth(),
                )
                WebSuggestiveTextField(
                    label = "stabilizer",
                    value = stabilizer,
                    suggestions = STABILIZER_OPTIONS,
                    onValueChange = onStabilizerChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun formatCoordinate(value: Double): String = js("value.toFixed(6)")

private fun formatByteCount(byteCount: Int): String =
    if (byteCount < 1024) {
        "$byteCount bytes"
    } else {
        "${(byteCount + 512) / 1024} KB"
    }

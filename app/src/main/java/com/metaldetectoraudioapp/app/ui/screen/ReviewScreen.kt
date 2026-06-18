package com.metaldetectoraudioapp.app.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metaldetectoraudioapp.app.recording.RecordingMetadata
import com.metaldetectoraudioapp.app.ui.ReviewViewModel
import com.metaldetectoraudioapp.app.ui.screen.AudioTrimmer
import com.metaldetectoraudioapp.app.ui.model.AUDIO_PROFILE_OPTIONS
import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.DETECTOR_MODEL_OPTIONS
import com.metaldetectoraudioapp.app.ui.model.EnvironmentCache
import com.metaldetectoraudioapp.app.ui.model.LabelEntry
import com.metaldetectoraudioapp.app.ui.model.MOISTURE_OPTIONS
import com.metaldetectoraudioapp.app.ui.model.RECOVERY_SPEED_OPTIONS
import com.metaldetectoraudioapp.app.ui.model.SEARCH_MODE_OPTIONS
import com.metaldetectoraudioapp.app.ui.model.SENSITIVITY_OPTIONS
import com.metaldetectoraudioapp.app.ui.model.SOIL_TYPE_OPTIONS
import com.metaldetectoraudioapp.app.ui.model.STABILIZER_OPTIONS
import com.metaldetectoraudioapp.app.ui.model.serializeLabelEntries
import com.metaldetectoraudioapp.app.ui.theme.Spacing

@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel,
    contentPadding: PaddingValues
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            viewModel.exportToUri(uri)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importFromUri(uri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    LazyColumn(
        modifier = Modifier.padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("Dataset Storage", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${context.filesDir.absolutePath}/dataset",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Android app data is private storage. Use Export Bundle to move CSV labels, WAVs, and images off-device.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Button(
                    onClick = { exportLauncher.launch("detector_dataset_${System.currentTimeMillis()}.zip") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Export Bundle")
                }
                FilledTonalButton(
                    onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Import Bundle")
                }
                OutlinedButton(
                    onClick = viewModel::refresh,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Refresh")
                }
            }
        }

        uiState.message?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.primary) }
        }

        uiState.errorMessage?.let { error ->
            item { Text(error, color = MaterialTheme.colorScheme.error) }
        }

        items(uiState.recordings, key = { it.recordingId }) { recording ->
            RecordingReviewCard(
                recording = recording,
                environmentCache = uiState.environmentCache,
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
                onRelabelEnvironment = { soil, moist, detectorModel, searchMode, audioProfile, sensitivity, recovery, stabilizer ->
                    viewModel.relabelEnvironment(
                        recording = recording,
                        soilType = soil,
                        moisture = moist,
                        detectorModel = detectorModel,
                        searchMode = searchMode,
                        audioProfile = audioProfile,
                        sensitivity = sensitivity,
                        recoverySpeed = recovery,
                        stabilizer = stabilizer,
                    )
                },
                onDelete = { viewModel.delete(recording.recordingId) }
            )
        }
    }
}

@Composable
private fun RecordingReviewCard(
    recording: RecordingMetadata,
    environmentCache: EnvironmentCache,
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
    onDelete: () -> Unit
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
        mutableStateOf(recording.soilType.orEmpty().ifEmpty { environmentCache.soilType })
    }
    var moistureInput by remember(recording.recordingId) {
        mutableStateOf(recording.moisture.orEmpty().ifEmpty { environmentCache.moisture })
    }
    var detectorModelInput by remember(recording.recordingId) {
        mutableStateOf(recording.detectorModel.orEmpty().ifEmpty { environmentCache.detectorModel })
    }
    var searchModeInput by remember(recording.recordingId) {
        mutableStateOf(recording.searchMode.orEmpty().ifEmpty { environmentCache.searchMode })
    }
    var audioProfileInput by remember(recording.recordingId) {
        mutableStateOf(recording.audioProfile.orEmpty().ifEmpty { environmentCache.audioProfile })
    }
    var sensitivityInput by remember(recording.recordingId) {
        mutableStateOf(recording.sensitivity.orEmpty().ifEmpty { environmentCache.sensitivity })
    }
    var recoverySpeedInput by remember(recording.recordingId) {
        mutableStateOf(recording.recoverySpeed.orEmpty().ifEmpty { environmentCache.recoverySpeed })
    }
    var stabilizerInput by remember(recording.recordingId) {
        mutableStateOf(recording.stabilizer.orEmpty().ifEmpty { environmentCache.stabilizer })
    }

    val latitude = recording.gpsLatitude
    val longitude = recording.gpsLongitude
    val isWideScreen = LocalConfiguration.current.screenWidthDp >= 600

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            // Metadata header — always full-width at the top
            Text(recording.audioFileName, style = MaterialTheme.typography.titleSmall)
            Text("ID: ${recording.recordingId}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Class: ${recording.classLabel.name} | Pattern: ${recording.pattern.name} | Duration: ${recording.durationMs} ms",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Depth: ${recording.depthInches ?: "N/A"} | " +
                    "mixed_target_and_junk: ${recording.mixedTargetAndJunk}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text("Image: ${recording.imageFileName ?: "none"}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "GPS: ${
                    if (latitude == null || longitude == null) "N/A"
                    else "%.6f, %.6f".format(latitude, longitude)
                }",
                style = MaterialTheme.typography.bodyMedium
            )

            if (isWideScreen) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
                ) {
                    RecordingMainSection(
                        recording = recording,
                        targetInput = targetInput,
                        onTargetChange = { targetInput = it },
                        notesInput = notesInput,
                        onNotesChange = { notesInput = it },
                        isPlaying = isPlaying,
                        isTrimEditorOpen = isTrimEditorOpen,
                        trimEnvelope = trimEnvelope,
                        trimStartMs = trimStartMs,
                        trimEndMs = trimEndMs,
                        trimFullDurationMs = trimFullDurationMs,
                        isTrimmed = isTrimmed,
                        onPlay = onPlay,
                        onOpenTrimEditor = onOpenTrimEditor,
                        onUpdateTrim = onUpdateTrim,
                        onResetTrim = onResetTrim,
                        onCloseTrimEditor = onCloseTrimEditor,
                        onSaveTrim = onSaveTrim,
                        onRelabelTargets = onRelabelTargets,
                        onRelabelNotes = onRelabelNotes,
                        onToggleInclude = onToggleInclude,
                        onDelete = onDelete,
                        modifier = Modifier.weight(2f)
                    )
                    RecordingEnvironmentSection(
                        soilTypeInput = soilTypeInput,
                        onSoilTypeChange = { soilTypeInput = it },
                        moistureInput = moistureInput,
                        onMoistureChange = { moistureInput = it },
                        detectorModelInput = detectorModelInput,
                        onDetectorModelChange = { detectorModelInput = it },
                        searchModeInput = searchModeInput,
                        onSearchModeChange = { searchModeInput = it },
                        audioProfileInput = audioProfileInput,
                        onAudioProfileChange = { audioProfileInput = it },
                        sensitivityInput = sensitivityInput,
                        onSensitivityChange = { sensitivityInput = it },
                        recoverySpeedInput = recoverySpeedInput,
                        onRecoverySpeedChange = { recoverySpeedInput = it },
                        stabilizerInput = stabilizerInput,
                        onStabilizerChange = { stabilizerInput = it },
                        onApplyEnvironment = {
                            onRelabelEnvironment(
                                soilTypeInput, moistureInput, detectorModelInput,
                                searchModeInput, audioProfileInput, sensitivityInput, recoverySpeedInput, stabilizerInput
                            )
                        },
                        showTitle = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                RecordingMainSection(
                    recording = recording,
                    targetInput = targetInput,
                    onTargetChange = { targetInput = it },
                    notesInput = notesInput,
                    onNotesChange = { notesInput = it },
                    isPlaying = isPlaying,
                    isTrimEditorOpen = isTrimEditorOpen,
                    trimEnvelope = trimEnvelope,
                    trimStartMs = trimStartMs,
                    trimEndMs = trimEndMs,
                    trimFullDurationMs = trimFullDurationMs,
                    isTrimmed = isTrimmed,
                    onPlay = onPlay,
                    onOpenTrimEditor = onOpenTrimEditor,
                    onUpdateTrim = onUpdateTrim,
                    onResetTrim = onResetTrim,
                    onCloseTrimEditor = onCloseTrimEditor,
                    onSaveTrim = onSaveTrim,
                    onRelabelTargets = onRelabelTargets,
                    onRelabelNotes = onRelabelNotes,
                    onToggleInclude = onToggleInclude,
                    onDelete = onDelete
                )
                RecordingEnvironmentSection(
                    soilTypeInput = soilTypeInput,
                    onSoilTypeChange = { soilTypeInput = it },
                    moistureInput = moistureInput,
                    onMoistureChange = { moistureInput = it },
                    detectorModelInput = detectorModelInput,
                    onDetectorModelChange = { detectorModelInput = it },
                    searchModeInput = searchModeInput,
                    onSearchModeChange = { searchModeInput = it },
                    audioProfileInput = audioProfileInput,
                    onAudioProfileChange = { audioProfileInput = it },
                    sensitivityInput = sensitivityInput,
                    onSensitivityChange = { sensitivityInput = it },
                    recoverySpeedInput = recoverySpeedInput,
                    onRecoverySpeedChange = { recoverySpeedInput = it },
                    stabilizerInput = stabilizerInput,
                    onStabilizerChange = { stabilizerInput = it },
                    onApplyEnvironment = {
                        onRelabelEnvironment(
                            soilTypeInput, moistureInput, detectorModelInput,
                            searchModeInput, audioProfileInput, sensitivityInput, recoverySpeedInput, stabilizerInput
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun RecordingMainSection(
    recording: RecordingMetadata,
    targetInput: String,
    onTargetChange: (String) -> Unit,
    notesInput: String,
    onNotesChange: (String) -> Unit,
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
    onRelabelTargets: (String) -> Unit,
    onRelabelNotes: (String) -> Unit,
    onToggleInclude: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        LabelPickerField(
            value = targetInput,
            onValueChange = onTargetChange,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = notesInput,
            onValueChange = onNotesChange,
            label = { Text("notes") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilledTonalButton(onClick = { onRelabelTargets(targetInput) }) { Text("Apply Names") }
            FilledTonalButton(onClick = { onRelabelNotes(notesInput) }) { Text("Apply Notes") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
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
    }
}

@Composable
private fun RecordingEnvironmentSection(
    soilTypeInput: String,
    onSoilTypeChange: (String) -> Unit,
    moistureInput: String,
    onMoistureChange: (String) -> Unit,
    detectorModelInput: String,
    onDetectorModelChange: (String) -> Unit,
    searchModeInput: String,
    onSearchModeChange: (String) -> Unit,
    audioProfileInput: String,
    onAudioProfileChange: (String) -> Unit,
    sensitivityInput: String,
    onSensitivityChange: (String) -> Unit,
    recoverySpeedInput: String,
    onRecoverySpeedChange: (String) -> Unit,
    stabilizerInput: String,
    onStabilizerChange: (String) -> Unit,
    onApplyEnvironment: () -> Unit,
    showTitle: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (showTitle) {
            Text("Environment", style = MaterialTheme.typography.titleSmall)
        }
        ReviewSuggestiveTextField(
            label = "soil_type",
            value = soilTypeInput,
            suggestions = SOIL_TYPE_OPTIONS,
            onValueChange = onSoilTypeChange,
            modifier = Modifier.fillMaxWidth()
        )
        Text("moisture", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            MOISTURE_OPTIONS.forEach { option ->
                FilterChip(
                    selected = moistureInput == option,
                    onClick = { onMoistureChange(if (moistureInput == option) "" else option) },
                    label = { Text(option) }
                )
            }
        }
        ReviewSuggestiveTextField(
            label = "detector_model",
            value = detectorModelInput,
            suggestions = DETECTOR_MODEL_OPTIONS,
            onValueChange = onDetectorModelChange,
            modifier = Modifier.fillMaxWidth()
        )
        Text("Custom detector model values are allowed.", style = MaterialTheme.typography.bodySmall)
        ReviewSuggestiveTextField(
            label = "search_mode",
            value = searchModeInput,
            suggestions = SEARCH_MODE_OPTIONS,
            onValueChange = onSearchModeChange,
            modifier = Modifier.fillMaxWidth()
        )
        ReviewSuggestiveTextField(
            label = "audio_profile",
            value = audioProfileInput,
            suggestions = AUDIO_PROFILE_OPTIONS,
            onValueChange = onAudioProfileChange,
            modifier = Modifier.fillMaxWidth()
        )
        ReviewSuggestiveTextField(
            label = "sensitivity",
            value = sensitivityInput,
            suggestions = SENSITIVITY_OPTIONS,
            onValueChange = onSensitivityChange,
            modifier = Modifier.fillMaxWidth()
        )
        ReviewSuggestiveTextField(
            label = "recovery_speed",
            value = recoverySpeedInput,
            suggestions = RECOVERY_SPEED_OPTIONS,
            onValueChange = onRecoverySpeedChange,
            modifier = Modifier.fillMaxWidth()
        )
        ReviewSuggestiveTextField(
            label = "stabilizer",
            value = stabilizerInput,
            suggestions = STABILIZER_OPTIONS,
            onValueChange = onStabilizerChange,
            modifier = Modifier.fillMaxWidth()
        )
        FilledTonalButton(onClick = onApplyEnvironment) {
            Text("Apply Environment")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewSuggestiveTextField(
    label: String,
    value: String,
    suggestions: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val filtered = if (value.isEmpty() || suggestions.any { it.equals(value, ignoreCase = true) }) {
        suggestions
    } else {
        suggestions.filter { it.startsWith(value, ignoreCase = true) }
    }

    ExposedDropdownMenuBox(
        expanded = expanded && filtered.isNotEmpty(),
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text(label) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && filtered.isNotEmpty())
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable)
        )
        if (filtered.isNotEmpty()) {
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                filtered.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onValueChange(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

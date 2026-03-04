package com.metaldetectoraudioapp.desktop.ui.screen

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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.screen.LabelPickerField
import com.metaldetectoraudioapp.desktop.viewmodel.DesktopReviewViewModel
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

private val SOIL_TYPE_OPTIONS = listOf(
    "dry-sand", "wet-sand", "clay", "loam", "gravel", "mineralized", "fill", "unknown"
)
private val MOISTURE_OPTIONS = listOf("dry", "moist", "wet")
private val DETECTOR_MODEL_OPTIONS = listOf(
    "minelab manticore",
    "minelab equinox",
    "xp deus 2",
)
private val SEARCH_MODE_OPTIONS = listOf(
    "all terrain high conductivity",
    "beach",
    "field",
)
private val SENSITIVITY_OPTIONS = (15..30).map { it.toString() }
private val RECOVERY_SPEED_OPTIONS = (1..8).map { it.toString() }
private val STABILIZER_OPTIONS = (1..10).map { it.toString() }

@Composable
fun DesktopReviewScreen(
    viewModel: DesktopReviewViewModel,
    contentPadding: PaddingValues,
) {
    val uiState by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = Modifier.padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Dataset Storage", style = MaterialTheme.typography.titleMedium)
                    Text(viewModel.datasetDirectoryPath)
                    Text(
                        "Use Export Bundle to create a zip with CSV labels, WAVs, and images for training transfer.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val selected = chooseSaveZipFile(defaultDirectory = viewModel.datasetDirectoryPath)
                            if (selected != null) {
                                viewModel.exportBundle(selected)
                            }
                        }) {
                            Text("Export Bundle")
                        }
                        Button(onClick = {
                            val selected = chooseOpenZipFile(defaultDirectory = viewModel.datasetDirectoryPath)
                            if (selected != null) {
                                viewModel.importBundle(selected)
                            }
                        }) {
                            Text("Import Bundle")
                        }
                        Button(onClick = {
                            val opened = openDirectory(viewModel.datasetDirectoryPath)
                            viewModel.setMessage(
                                if (opened) {
                                    "Opened: ${viewModel.datasetDirectoryPath}"
                                } else {
                                    "Unable to open folder, path: ${viewModel.datasetDirectoryPath}"
                                }
                            )
                        }) {
                            Text("Open Folder")
                        }
                        Button(onClick = viewModel::refresh) {
                            Text("Refresh")
                        }
                    }
                }
            }
        }

        uiState.message?.let { message ->
            item {
                Text(message, color = MaterialTheme.colorScheme.primary)
            }
        }

        uiState.errorMessage?.let { error ->
            item {
                Text(error, color = MaterialTheme.colorScheme.error)
            }
        }

        items(uiState.recordings, key = { it.recordingId }) { recording ->
            DesktopRecordingReviewCard(
                recording = recording,
                isPlaying = uiState.selectedPlayingId == recording.recordingId,
                onPlay = { viewModel.playOrStop(recording) },
                onToggleInclude = { viewModel.toggleIncludeInTraining(recording, it) },
                onRelabelTargets = { viewModel.relabelTargetNames(recording, it) },
                onRelabelClass = { viewModel.relabelClass(recording, it) },
                onRelabelNotes = { viewModel.relabelNotes(recording, it) },
                onRelabelEnvironment = {
                    soil,
                    moist,
                    detectorModel,
                    searchMode,
                    sensitivity,
                    recovery,
                    stabilizer,
                    ->
                    viewModel.relabelEnvironment(
                        recording = recording,
                        soilType = soil,
                        moisture = moist,
                        detectorModel = detectorModel,
                        searchMode = searchMode,
                        sensitivity = sensitivity,
                        recoverySpeed = recovery,
                        stabilizer = stabilizer,
                    )
                },
                onDelete = { viewModel.delete(recording.recordingId) },
            )
        }
    }
}

@Composable
private fun DesktopRecordingReviewCard(
    recording: RecordingMetadata,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onToggleInclude: (Boolean) -> Unit,
    onRelabelTargets: (String) -> Unit,
    onRelabelClass: (ClassLabel) -> Unit,
    onRelabelNotes: (String) -> Unit,
    onRelabelEnvironment: (
        soilType: String,
        moisture: String,
        detectorModel: String,
        searchMode: String,
        sensitivity: String,
        recoverySpeed: String,
        stabilizer: String,
    ) -> Unit,
    onDelete: () -> Unit,
) {
    var targetInput by remember(recording.recordingId) {
        mutableStateOf(recording.targetNames.joinToString(","))
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
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(recording.audioFileName, style = MaterialTheme.typography.titleSmall)
            Text("ID: ${recording.recordingId}")
            Text("Class: ${recording.classLabel.name} | Pattern: ${recording.pattern.name} | Duration: ${recording.durationMs} ms")
            Text("Depth: ${recording.depthInches ?: "N/A"} | Mixed: ${recording.mixedFlag}")
            Text("Image: ${recording.imageFileName ?: "none"}")

            LabelPickerField(
                value = targetInput,
                onValueChange = { targetInput = it },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = notesInput,
                onValueChange = { notesInput = it },
                label = { Text("notes") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onRelabelTargets(targetInput) }) {
                    Text("Apply Names")
                }
                Button(onClick = { onRelabelNotes(notesInput) }) {
                    Text("Apply Notes")
                }
                Button(onClick = onPlay) {
                    Text(if (isPlaying) "Stop" else "Play")
                }
                Button(onClick = onDelete) {
                    Text("Delete")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ClassLabel.entries.forEach { label ->
                    FilterChip(
                        selected = recording.classLabel == label,
                        onClick = { onRelabelClass(label) },
                        label = { Text(label.name) }
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = recording.includeInTraining,
                    onCheckedChange = onToggleInclude
                )
                Text("include_in_training")
            }

            ReviewSuggestiveTextField(
                label = "soil_type",
                value = soilTypeInput,
                suggestions = SOIL_TYPE_OPTIONS,
                onValueChange = { soilTypeInput = it },
                modifier = Modifier.fillMaxWidth()
            )

            Text("moisture")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MOISTURE_OPTIONS.forEach { option ->
                    FilterChip(
                        selected = moistureInput == option,
                        onClick = { moistureInput = if (moistureInput == option) "" else option },
                        label = { Text(option) }
                    )
                }
            }

            ReviewSuggestiveTextField(
                label = "detector_model",
                value = detectorModelInput,
                suggestions = DETECTOR_MODEL_OPTIONS,
                onValueChange = { detectorModelInput = it },
                modifier = Modifier.fillMaxWidth()
            )

            ReviewSuggestiveTextField(
                label = "search_mode",
                value = searchModeInput,
                suggestions = SEARCH_MODE_OPTIONS,
                onValueChange = { searchModeInput = it },
                modifier = Modifier.fillMaxWidth()
            )

            ReviewSuggestiveTextField(
                label = "sensitivity",
                value = sensitivityInput,
                suggestions = SENSITIVITY_OPTIONS,
                onValueChange = { sensitivityInput = it },
                modifier = Modifier.fillMaxWidth()
            )

            ReviewSuggestiveTextField(
                label = "recovery_speed",
                value = recoverySpeedInput,
                suggestions = RECOVERY_SPEED_OPTIONS,
                onValueChange = { recoverySpeedInput = it },
                modifier = Modifier.fillMaxWidth()
            )

            ReviewSuggestiveTextField(
                label = "stabilizer",
                value = stabilizerInput,
                suggestions = STABILIZER_OPTIONS,
                onValueChange = { stabilizerInput = it },
                modifier = Modifier.fillMaxWidth()
            )

            Button(onClick = {
                onRelabelEnvironment(
                    soilTypeInput,
                    moistureInput,
                    detectorModelInput,
                    searchModeInput,
                    sensitivityInput,
                    recoverySpeedInput,
                    stabilizerInput,
                )
            }) {
                Text("Apply Environment")
            }
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
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val filtered = suggestions.filter { it.startsWith(value, ignoreCase = true) }

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
                .menuAnchor()
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

private fun chooseSaveZipFile(defaultDirectory: String): File? {
    val dialog = FileDialog(null as Frame?, "Export Dataset Bundle", FileDialog.SAVE)
    dialog.directory = defaultDirectory
    dialog.file = "detector_dataset_${System.currentTimeMillis()}.zip"
    dialog.isVisible = true

    val selectedFileName = dialog.file ?: return null
    val selectedDirectory = dialog.directory ?: return null
    return File(selectedDirectory, selectedFileName)
}

private fun chooseOpenZipFile(defaultDirectory: String): File? {
    val dialog = FileDialog(null as Frame?, "Import Dataset Bundle", FileDialog.LOAD)
    dialog.directory = defaultDirectory
    dialog.isVisible = true

    val selectedFileName = dialog.file ?: return null
    val selectedDirectory = dialog.directory ?: return null
    return File(selectedDirectory, selectedFileName)
}

private fun openDirectory(path: String): Boolean {
    return runCatching {
        Desktop.getDesktop().open(File(path))
    }.isSuccess
}

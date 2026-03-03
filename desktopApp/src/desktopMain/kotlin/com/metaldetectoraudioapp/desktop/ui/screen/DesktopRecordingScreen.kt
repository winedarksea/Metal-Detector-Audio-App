package com.metaldetectoraudioapp.desktop.ui.screen

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
import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.SweepPattern
import com.metaldetectoraudioapp.app.ui.screen.LabelPickerField
import com.metaldetectoraudioapp.desktop.viewmodel.DesktopRecordingViewModel

private val SOIL_TYPE_OPTIONS = listOf(
    "dry-sand", "wet-sand", "clay", "loam", "gravel", "mineralized", "fill", "unknown"
)
private val MOISTURE_OPTIONS = listOf("dry", "moist", "wet")

@Composable
fun DesktopRecordingScreen(
    viewModel: DesktopRecordingViewModel,
    contentPadding: PaddingValues,
) {
    val uiState by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = Modifier.padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Dataset Storage", style = MaterialTheme.typography.titleMedium)
                    Text(viewModel.datasetDirectoryPath)
                    Text(
                        "Saved labels are written to recordings_metadata.json and WAV files are written to dataset/audio/.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Capture", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::startRecording, enabled = !uiState.isRecording) {
                            Text("Start")
                        }
                        Button(onClick = viewModel::stopRecording, enabled = uiState.isRecording) {
                            Text("Stop")
                        }
                        Button(
                            onClick = {
                                if (uiState.isPlayingPreview) {
                                    viewModel.stopPreview()
                                } else {
                                    viewModel.playPreview()
                                }
                            },
                            enabled = uiState.pendingAudioFile != null
                        ) {
                            Text(if (uiState.isPlayingPreview) "Stop Preview" else "Play Preview")
                        }
                    }
                    Text("Duration: ${uiState.pendingDurationMs} ms")
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Labels", style = MaterialTheme.typography.titleMedium)

                    LabelPickerField(
                        value = uiState.draft.targetNameInput,
                        onValueChange = viewModel::updateTargetNames,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = uiState.draft.depthInches,
                        onValueChange = viewModel::updateDepthInches,
                        label = { Text("depth_inches (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = uiState.draft.notesInput,
                        onValueChange = viewModel::updateNotes,
                        label = { Text("notes (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )

                    Text("class_label")
                    EnumChips(
                        selectedLabel = uiState.draft.classLabel,
                        labels = ClassLabel.entries,
                        toText = { it.name },
                        onSelect = viewModel::updateClassLabel
                    )

                    Text("pattern")
                    EnumChips(
                        selectedLabel = uiState.draft.pattern,
                        labels = SweepPattern.entries,
                        toText = { it.name },
                        onSelect = viewModel::updatePattern
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = uiState.draft.mixedFlag,
                            onCheckedChange = viewModel::updateMixedFlag
                        )
                        Text("mixed_flag")
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = uiState.draft.includeInTraining,
                            onCheckedChange = viewModel::updateIncludeInTraining,
                            enabled = !uiState.draft.mixedFlag
                        )
                        Text("include_in_training")
                    }

                    Button(onClick = viewModel::saveRecording, enabled = uiState.pendingAudioFile != null) {
                        Text("Save Recording")
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Environment", style = MaterialTheme.typography.titleMedium)

                    SuggestiveTextField(
                        label = "soil_type (optional)",
                        value = uiState.draft.soilType,
                        suggestions = SOIL_TYPE_OPTIONS,
                        onValueChange = viewModel::updateSoilType,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("moisture (optional)")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MOISTURE_OPTIONS.forEach { option ->
                            FilterChip(
                                selected = uiState.draft.moisture == option,
                                onClick = {
                                    viewModel.updateMoisture(
                                        if (uiState.draft.moisture == option) "" else option
                                    )
                                },
                                label = { Text(option) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = uiState.draft.detectorModel,
                        onValueChange = viewModel::updateDetectorModel,
                        label = { Text("detector_model (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        uiState.saveResultMessage?.let { message ->
            item {
                Text(message, color = MaterialTheme.colorScheme.primary)
            }
        }

        uiState.errorMessage?.let { error ->
            item {
                Text(error, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun <T> EnumChips(
    selectedLabel: T,
    labels: List<T>,
    toText: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEach { entry ->
            FilterChip(
                selected = selectedLabel == entry,
                onClick = { onSelect(entry) },
                label = { Text(toText(entry)) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuggestiveTextField(
    label: String,
    value: String,
    suggestions: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
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

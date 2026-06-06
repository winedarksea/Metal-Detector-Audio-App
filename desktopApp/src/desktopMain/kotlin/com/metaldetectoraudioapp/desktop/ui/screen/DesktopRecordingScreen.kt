package com.metaldetectoraudioapp.desktop.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.SweepPattern
import com.metaldetectoraudioapp.app.ui.screen.LabelPickerField
import com.metaldetectoraudioapp.app.ui.screen.RecordingHintCard
import com.metaldetectoraudioapp.desktop.viewmodel.DesktopRecordingViewModel
import org.jetbrains.skia.Image as SkiaImage
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
fun DesktopRecordingScreen(
    viewModel: DesktopRecordingViewModel,
    contentPadding: PaddingValues,
) {
    val uiState by viewModel.uiState.collectAsState()
    val previewImage = remember(uiState.pendingImage) {
        loadDesktopImageBitmap(uiState.pendingImage?.bytes)
    }

    LazyColumn(
        modifier = Modifier.padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            RecordingHintCard()
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Dataset Storage", style = MaterialTheme.typography.titleMedium)
                    Text(viewModel.datasetDirectoryPath)
                    Text(
                        "Saved labels are written to recordings_metadata.csv, WAV files to dataset/audio/, and optional images to dataset/images/.",
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
                            enabled = uiState.pendingAudio != null
                        ) {
                            Text(if (uiState.isPlayingPreview) "Stop Preview" else "Play Preview")
                        }
                        Button(
                            onClick = viewModel::clearPendingCapture,
                            enabled = uiState.pendingAudio != null || uiState.pendingImage != null
                        ) {
                            Text("Clear Pending")
                        }
                    }
                    Text("Duration: ${uiState.pendingDurationMs} ms")
                    Text(
                        "Audio and image stay temporary until Save Recording. Starting a new recording clears unsaved capture.",
                        style = MaterialTheme.typography.bodySmall
                    )
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

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val selected = chooseOpenImageFile(defaultDirectory = viewModel.datasetDirectoryPath)
                                if (selected != null) {
                                    viewModel.attachImageFromFile(selected)
                                }
                            },
                            enabled = !uiState.isRecording
                        ) {
                            Text("Add Image")
                        }

                        if (uiState.pendingImage != null) {
                            Button(onClick = viewModel::removePendingImage) {
                                Text("Remove Image")
                            }
                        }
                    }

                    if (previewImage != null) {
                        Image(
                            bitmap = previewImage,
                            contentDescription = "Captured find image preview",
                            modifier = Modifier
                                .size(120.dp)
                                .clip(MaterialTheme.shapes.small),
                            contentScale = ContentScale.Crop
                        )
                        Text(
                            uiState.pendingImage?.let { "find-image.${it.extension}" }.orEmpty(),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Text("GPS: not available on desktop capture")

                    Text("class_label (required)")
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

                    if (uiState.draft.mixedFlag) {
                        Text(
                            "mixed_flag: auto-set (multiple labels)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = uiState.draft.includeInTraining,
                            onCheckedChange = viewModel::updateIncludeInTraining,
                        )
                        Text("include_in_training")
                    }

                    Button(onClick = viewModel::saveRecording, enabled = uiState.pendingAudio != null) {
                        Text("Save Recording")
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Environment & Detector", style = MaterialTheme.typography.titleMedium)

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

                    SuggestiveTextField(
                        label = "detector_model",
                        value = uiState.draft.detectorModel,
                        suggestions = DETECTOR_MODEL_OPTIONS,
                        onValueChange = viewModel::updateDetectorModel,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Custom detector model values are allowed.", style = MaterialTheme.typography.bodySmall)

                    SuggestiveTextField(
                        label = "search_mode",
                        value = uiState.draft.searchMode,
                        suggestions = SEARCH_MODE_OPTIONS,
                        onValueChange = viewModel::updateSearchMode,
                        modifier = Modifier.fillMaxWidth()
                    )

                    SuggestiveTextField(
                        label = "sensitivity",
                        value = uiState.draft.sensitivity,
                        suggestions = SENSITIVITY_OPTIONS,
                        onValueChange = viewModel::updateSensitivity,
                        modifier = Modifier.fillMaxWidth()
                    )

                    SuggestiveTextField(
                        label = "recovery_speed",
                        value = uiState.draft.recoverySpeed,
                        suggestions = RECOVERY_SPEED_OPTIONS,
                        onValueChange = viewModel::updateRecoverySpeed,
                        modifier = Modifier.fillMaxWidth()
                    )

                    SuggestiveTextField(
                        label = "stabilizer",
                        value = uiState.draft.stabilizer,
                        suggestions = STABILIZER_OPTIONS,
                        onValueChange = viewModel::updateStabilizer,
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
    selectedLabel: T?,
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

private fun chooseOpenImageFile(defaultDirectory: String): File? {
    val dialog = FileDialog(null as Frame?, "Select Find Image", FileDialog.LOAD)
    dialog.directory = defaultDirectory
    dialog.isVisible = true

    val selectedFileName = dialog.file ?: return null
    val selectedDirectory = dialog.directory ?: return null
    return File(selectedDirectory, selectedFileName)
}

private fun loadDesktopImageBitmap(imageBytes: ByteArray?): androidx.compose.ui.graphics.ImageBitmap? {
    if (imageBytes == null || imageBytes.isEmpty()) {
        return null
    }

    return runCatching {
        SkiaImage.makeFromEncoded(imageBytes).toComposeImageBitmap()
    }.getOrNull()
}

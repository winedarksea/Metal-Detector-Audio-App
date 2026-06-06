package com.metaldetectoraudioapp.app.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.AudioDeviceInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metaldetectoraudioapp.app.ui.RecordingViewModel
import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.DETECTOR_MODEL_OPTIONS
import com.metaldetectoraudioapp.app.ui.model.SweepPattern
import com.metaldetectoraudioapp.app.ui.theme.DetectionColors

private val SOIL_TYPE_OPTIONS = listOf(
    "dry-sand", "wet-sand", "clay", "loam", "gravel", "mineralized", "fill", "unknown"
)
private val MOISTURE_OPTIONS = listOf("dry", "moist", "wet")
private val SEARCH_MODE_OPTIONS = listOf(
    "all terrain high conductivity",
    "beach",
    "field",
)
private val SENSITIVITY_OPTIONS = (15..30).map { it.toString() }
private val RECOVERY_SPEED_OPTIONS = (1..8).map { it.toString() }
private val STABILIZER_OPTIONS = (1..10).map { it.toString() }

@Composable
fun RecordingScreen(
    viewModel: RecordingViewModel,
    contentPadding: PaddingValues
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val inputDevices by viewModel.inputDevices.collectAsStateWithLifecycle()
    val selectedInputDevice by viewModel.selectedInputDevice.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.captureCurrentLocation()
        } else {
            viewModel.onLocationPermissionDenied()
        }
    }

    val imageCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        viewModel.attachCapturedImage(bitmap)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            imageCaptureLauncher.launch(null)
        } else {
            viewModel.onCameraPermissionDenied()
        }
    }

    val previewImage = remember(uiState.pendingImageFile?.absolutePath) {
        uiState.pendingImageFile?.let { file ->
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        }
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
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Capture", style = MaterialTheme.typography.titleMedium)
                    AudioDevicePicker(
                        label = "Input Device",
                        devices = inputDevices,
                        selectedDevice = selectedInputDevice,
                        onDeviceSelected = viewModel::setInputDevice
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = viewModel::startRecording,
                            enabled = !uiState.isRecording,
                            colors = ButtonDefaults.buttonColors(containerColor = DetectionColors.Recording)
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Record")
                        }
                        Button(onClick = viewModel::stopRecording, enabled = uiState.isRecording) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Stop")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        Button(
                            onClick = viewModel::clearPendingCapture,
                            enabled = uiState.pendingAudioFile != null || uiState.pendingImageFile != null
                        ) {
                            Text("Clear Pending")
                        }
                    }
                    if (uiState.isRecording) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("●", color = DetectionColors.Recording)
                            Text("RECORDING", color = DetectionColors.Recording, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (uiState.pendingAudioFile != null && !uiState.isRecording) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Recording captured — add labels below, then tap Save Recording",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    if (uiState.isRecording || uiState.pendingAudioFile != null) {
                        WaveformCanvas(waveformPoints = uiState.waveformPoints)
                        LinearProgressIndicator(
                            progress = { uiState.rmsLevel.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
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
                        label = { Text("notes (optional, short)") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val hasCameraPermission = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasCameraPermission) {
                                    imageCaptureLauncher.launch(null)
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            enabled = !uiState.isRecording
                        ) {
                            Text("Add Image")
                        }
                        if (uiState.pendingImageFile != null) {
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
                    }

                    Button(
                        onClick = {
                            val hasLocationPermission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED

                            if (hasLocationPermission) {
                                viewModel.captureCurrentLocation()
                            } else {
                                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                        }
                    ) {
                        Text("Use Current GPS")
                    }
                    Text(
                        text = if (uiState.draft.gpsLatitude == null || uiState.draft.gpsLongitude == null) {
                            "GPS: not set"
                        } else {
                            "GPS: %.6f, %.6f".format(uiState.draft.gpsLatitude, uiState.draft.gpsLongitude)
                        },
                        style = MaterialTheme.typography.bodySmall
                    )

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

                    uiState.saveResultMessage?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.primary)
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

        uiState.errorMessage?.let { error ->
            item { Text(error, color = MaterialTheme.colorScheme.error) }
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

/**
 * Guidance for capturing good ML training data, shown atop the recording screen.
 * Mirrors the shared `RecordingHintCard` used by web/desktop; the app module keeps
 * its own copy in line with the rest of its UI.
 */
@Composable
private fun RecordingHintCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("•", style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun <T> EnumChips(
    selectedLabel: T?,
    labels: List<T>,
    toText: (T) -> String,
    onSelect: (T) -> Unit
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

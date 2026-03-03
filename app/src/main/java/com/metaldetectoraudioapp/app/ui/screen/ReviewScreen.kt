package com.metaldetectoraudioapp.app.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metaldetectoraudioapp.app.recording.RecordingMetadata
import com.metaldetectoraudioapp.app.ui.ReviewViewModel
import com.metaldetectoraudioapp.app.ui.model.ClassLabel

private val SOIL_TYPE_OPTIONS = listOf(
    "dry-sand", "wet-sand", "clay", "loam", "gravel", "mineralized", "fill", "unknown"
)
private val MOISTURE_OPTIONS = listOf("dry", "moist", "wet")

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
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Dataset Storage", style = MaterialTheme.typography.titleMedium)
                    Text("${context.filesDir.absolutePath}/dataset")
                    Text(
                        "Android app data is private storage. Use Export Bundle to move labels/WAVs off-device.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportLauncher.launch("detector_dataset_${System.currentTimeMillis()}.zip") }) {
                    Text("Export Bundle")
                }
                Button(onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                    Text("Import Bundle")
                }
                Button(onClick = viewModel::refresh) {
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
                isPlaying = uiState.selectedPlayingId == recording.recordingId,
                onPlay = { viewModel.playOrStop(recording) },
                onToggleInclude = { viewModel.toggleIncludeInTraining(recording, it) },
                onRelabelTargets = { viewModel.relabelTargetNames(recording, it) },
                onRelabelClass = { viewModel.relabelClass(recording, it) },
                onRelabelNotes = { viewModel.relabelNotes(recording, it) },
                onRelabelEnvironment = { soil, moist, model ->
                    viewModel.relabelEnvironment(recording, soil, moist, model)
                },
                onDelete = { viewModel.delete(recording.recordingId) }
            )
        }
    }
}

@Composable
private fun RecordingReviewCard(
    recording: RecordingMetadata,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onToggleInclude: (Boolean) -> Unit,
    onRelabelTargets: (String) -> Unit,
    onRelabelClass: (ClassLabel) -> Unit,
    onRelabelNotes: (String) -> Unit,
    onRelabelEnvironment: (soilType: String, moisture: String, detectorModel: String) -> Unit,
    onDelete: () -> Unit
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
    val latitude = recording.gpsLatitude
    val longitude = recording.gpsLongitude

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(recording.audioFileName, style = MaterialTheme.typography.titleSmall)
            Text("Class: ${recording.classLabel.name} | Pattern: ${recording.pattern.name} | Duration: ${recording.durationMs} ms")
            Text("Depth: ${recording.depthInches ?: "N/A"} | Mixed: ${recording.mixedFlag}")
            Text(
                "GPS: ${
                    if (latitude == null || longitude == null) {
                        "N/A"
                    } else {
                        "%.6f, %.6f".format(latitude, longitude)
                    }
                }"
            )

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

            // Environment fields
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

            OutlinedTextField(
                value = detectorModelInput,
                onValueChange = { detectorModelInput = it },
                label = { Text("detector_model") },
                modifier = Modifier.fillMaxWidth()
            )

            Button(onClick = {
                onRelabelEnvironment(soilTypeInput, moistureInput, detectorModelInput)
            }) {
                Text("Apply Environment")
            }
        }
    }
}

@Composable
private fun ReviewSuggestiveTextField(
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

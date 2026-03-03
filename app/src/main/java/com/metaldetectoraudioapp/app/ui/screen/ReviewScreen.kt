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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metaldetectoraudioapp.app.recording.RecordingMetadata
import com.metaldetectoraudioapp.app.ui.ReviewViewModel
import com.metaldetectoraudioapp.app.ui.model.ClassLabel

@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel,
    contentPadding: PaddingValues
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
    onDelete: () -> Unit
) {
    var targetInput by remember(recording.recordingId) {
        mutableStateOf(recording.targetNames.joinToString(","))
    }
    var notesInput by remember(recording.recordingId) {
        mutableStateOf(recording.notes.orEmpty())
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

            OutlinedTextField(
                value = targetInput,
                onValueChange = { targetInput = it },
                label = { Text("target_name list") },
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
        }
    }
}

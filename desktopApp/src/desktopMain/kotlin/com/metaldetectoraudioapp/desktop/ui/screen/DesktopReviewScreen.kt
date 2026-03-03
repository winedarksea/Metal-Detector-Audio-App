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
import com.metaldetectoraudioapp.desktop.viewmodel.DesktopReviewViewModel
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

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
                        "Use Export Bundle to create a zip for training transfer.",
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
    onDelete: () -> Unit,
) {
    var targetInput by remember(recording.recordingId) {
        mutableStateOf(recording.targetNames.joinToString(","))
    }
    var notesInput by remember(recording.recordingId) {
        mutableStateOf(recording.notes.orEmpty())
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(recording.audioFileName, style = MaterialTheme.typography.titleSmall)
            Text("Class: ${recording.classLabel.name} | Pattern: ${recording.pattern.name} | Duration: ${recording.durationMs} ms")
            Text("Depth: ${recording.depthInches ?: "N/A"} | Mixed: ${recording.mixedFlag}")

            OutlinedTextField(
                value = targetInput,
                onValueChange = { targetInput = it },
                label = { Text("target_names") },
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

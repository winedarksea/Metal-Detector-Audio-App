package com.metaldetectoraudioapp.desktop.viewmodel

import com.metaldetectoraudioapp.app.audio.AudioPlayer
import com.metaldetectoraudioapp.app.export.DatasetBundleManager
import com.metaldetectoraudioapp.app.recording.RecordingMetadata
import com.metaldetectoraudioapp.app.recording.RecordingRepository
import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.ReviewUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class DesktopReviewViewModel(
    private val recordingRepository: RecordingRepository,
    private val bundleManager: DatasetBundleManager,
    private val audioPlayer: AudioPlayer,
    val datasetDirectoryPath: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playbackJob: Job? = null

    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        scope.launch {
            val recordings = recordingRepository.listRecordings()
            _uiState.value = _uiState.value.copy(recordings = recordings, errorMessage = null)
        }
    }

    fun playOrStop(recording: RecordingMetadata) {
        if (_uiState.value.selectedPlayingId == recording.recordingId) {
            audioPlayer.stop()
            playbackJob?.cancel()
            playbackJob = null
            _uiState.value = _uiState.value.copy(selectedPlayingId = null)
            return
        }

        playbackJob?.cancel()
        playbackJob = scope.launch {
            val bytes = recordingRepository.readAudioBytes(recording)
            if (bytes == null) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Audio file missing: ${recording.audioFileName}"
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(selectedPlayingId = recording.recordingId)
            audioPlayer.play(bytes)
            _uiState.value = _uiState.value.copy(selectedPlayingId = null)
        }
    }

    fun toggleIncludeInTraining(recording: RecordingMetadata, includeInTraining: Boolean) {
        scope.launch {
            recordingRepository.updateRecording(recording.copy(includeInTraining = includeInTraining))
            refresh()
        }
    }

    fun relabelTargetNames(recording: RecordingMetadata, targetNamesInput: String) {
        val targetNames = targetNamesInput
            .split(',', ';', '|')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        scope.launch {
            recordingRepository.updateRecording(
                recording.copy(
                    targetNames = if (targetNames.isEmpty()) listOf("ambient:background:unknown") else targetNames,
                    mixedFlag = targetNames.size > 1,
                )
            )
            refresh()
        }
    }

    fun relabelClass(recording: RecordingMetadata, classLabel: ClassLabel) {
        scope.launch {
            recordingRepository.updateRecording(recording.copy(classLabel = classLabel))
            refresh()
        }
    }

    fun relabelNotes(recording: RecordingMetadata, notes: String) {
        scope.launch {
            recordingRepository.updateRecording(recording.copy(notes = notes.ifBlank { null }))
            refresh()
        }
    }

    fun relabelEnvironment(
        recording: RecordingMetadata,
        soilType: String,
        moisture: String,
        detectorModel: String,
        searchMode: String,
        sensitivity: String,
        recoverySpeed: String,
        stabilizer: String,
    ) {
        scope.launch {
            recordingRepository.updateRecording(
                recording.copy(
                    soilType = soilType.ifBlank { null },
                    moisture = moisture.ifBlank { null },
                    detectorModel = detectorModel.ifBlank { null },
                    searchMode = searchMode.ifBlank { null },
                    sensitivity = sensitivity.ifBlank { null },
                    recoverySpeed = recoverySpeed.ifBlank { null },
                    stabilizer = stabilizer.ifBlank { null },
                )
            )
            refresh()
        }
    }

    fun delete(recordingId: String) {
        scope.launch {
            recordingRepository.deleteRecording(recordingId)
            refresh()
        }
    }

    fun exportBundle(destinationZipFile: File) {
        scope.launch {
            runCatching {
                val bytes = bundleManager.exportBundle()
                destinationZipFile.parentFile?.mkdirs()
                destinationZipFile.writeBytes(bytes)
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    message = "Dataset export complete: ${destinationZipFile.absolutePath}",
                    errorMessage = null
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(errorMessage = "Export failed: ${error.message}")
            }
        }
    }

    fun importBundle(bundleZipFile: File) {
        scope.launch {
            runCatching {
                bundleManager.importBundle(bundleZipFile.readBytes())
            }.onSuccess { importedCount ->
                refresh()
                _uiState.value = _uiState.value.copy(
                    message = "Imported $importedCount recordings",
                    errorMessage = null
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(errorMessage = "Import failed: ${error.message}")
            }
        }
    }

    fun setMessage(message: String?) {
        _uiState.value = _uiState.value.copy(message = message)
    }

    fun close() {
        audioPlayer.stop()
        playbackJob?.cancel()
        scope.cancel()
    }
}

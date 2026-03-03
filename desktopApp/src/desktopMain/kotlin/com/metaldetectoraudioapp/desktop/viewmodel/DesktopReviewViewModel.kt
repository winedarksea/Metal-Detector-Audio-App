package com.metaldetectoraudioapp.desktop.viewmodel

import com.metaldetectoraudioapp.app.export.DatasetBundleManager
import com.metaldetectoraudioapp.app.recording.RecordingMetadata
import com.metaldetectoraudioapp.app.recording.RecordingRepository
import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.ReviewUiState
import com.metaldetectoraudioapp.desktop.audio.DesktopAudioPlaybackController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class DesktopReviewViewModel(
    private val recordingRepository: RecordingRepository,
    private val bundleManager: DatasetBundleManager,
) {
    private val playbackController = DesktopAudioPlaybackController()

    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    val datasetDirectoryPath: String =
        recordingRepository.metadataFile().parentFile?.absolutePath.orEmpty()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(
            recordings = recordingRepository.listRecordings(),
            errorMessage = null
        )
    }

    fun playOrStop(recording: RecordingMetadata) {
        if (_uiState.value.selectedPlayingId == recording.recordingId) {
            playbackController.stop()
            _uiState.value = _uiState.value.copy(selectedPlayingId = null)
            return
        }

        val file = recordingRepository.resolveAudioFile(recording)
        if (file == null) {
            _uiState.value = _uiState.value.copy(errorMessage = "Audio file missing: ${recording.audioFileName}")
            return
        }

        playbackController.play(file) {
            _uiState.value = _uiState.value.copy(selectedPlayingId = null)
        }
        _uiState.value = _uiState.value.copy(selectedPlayingId = recording.recordingId)
    }

    fun toggleIncludeInTraining(recording: RecordingMetadata, includeInTraining: Boolean) {
        recordingRepository.updateRecording(recording.copy(includeInTraining = includeInTraining))
        refresh()
    }

    fun relabelTargetNames(recording: RecordingMetadata, targetNamesInput: String) {
        val targetNames = targetNamesInput
            .split(',', ';', '|')
            .map { it.trim() }
            .filter { it.isNotBlank() }

        recordingRepository.updateRecording(
            recording.copy(targetNames = if (targetNames.isEmpty()) listOf("ambient:background:unknown") else targetNames)
        )
        refresh()
    }

    fun relabelClass(recording: RecordingMetadata, classLabel: ClassLabel) {
        recordingRepository.updateRecording(recording.copy(classLabel = classLabel))
        refresh()
    }

    fun relabelNotes(recording: RecordingMetadata, notes: String) {
        recordingRepository.updateRecording(recording.copy(notes = notes.ifBlank { null }))
        refresh()
    }

    fun delete(recordingId: String) {
        recordingRepository.deleteRecording(recordingId)
        refresh()
    }

    fun exportBundle(destinationZipFile: File) {
        runCatching {
            destinationZipFile.parentFile?.mkdirs()
            destinationZipFile.outputStream().use { bundleManager.exportBundle(it) }
        }.onSuccess {
            _uiState.value = _uiState.value.copy(
                message = "Dataset export complete: ${destinationZipFile.absolutePath}",
                errorMessage = null
            )
        }.onFailure { error ->
            _uiState.value = _uiState.value.copy(errorMessage = "Export failed: ${error.message}")
        }
    }

    fun importBundle(bundleZipFile: File) {
        runCatching {
            bundleZipFile.inputStream().use { bundleManager.importBundle(it) }
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

    fun setMessage(message: String?) {
        _uiState.value = _uiState.value.copy(message = message)
    }

    fun close() {
        playbackController.stop()
    }
}

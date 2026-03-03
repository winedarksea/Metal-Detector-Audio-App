package com.metaldetectoraudioapp.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.metaldetectoraudioapp.app.AppContainerProvider
import com.metaldetectoraudioapp.app.recording.AudioPlaybackController
import com.metaldetectoraudioapp.app.recording.RecordingMetadata
import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.ReviewUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ReviewViewModel(application: Application) : AndroidViewModel(application) {
    private val appContainer = AppContainerProvider.get(application.applicationContext)
    private val recordingRepository = appContainer.recordingRepository
    private val bundleManager = appContainer.datasetBundleManager
    private val playbackController = AudioPlaybackController()

    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

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
            recording.copy(targetNames = if (targetNames.isEmpty()) listOf("ambient") else targetNames)
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

    fun relabelEnvironment(recording: RecordingMetadata, soilType: String, moisture: String, detectorModel: String) {
        recordingRepository.updateRecording(
            recording.copy(
                soilType = soilType.ifBlank { null },
                moisture = moisture.ifBlank { null },
                detectorModel = detectorModel.ifBlank { null }
            )
        )
        refresh()
    }

    fun delete(recordingId: String) {
        recordingRepository.deleteRecording(recordingId)
        refresh()
    }

    fun exportToUri(uri: Uri) {
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val output = resolver.openOutputStream(uri)
            if (output == null) {
                _uiState.value = _uiState.value.copy(errorMessage = "Failed to open export URI")
                return@launch
            }

            output.use { bundleManager.exportBundle(it) }
            _uiState.value = _uiState.value.copy(message = "Dataset export complete")
        }
    }

    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val input = resolver.openInputStream(uri)
            if (input == null) {
                _uiState.value = _uiState.value.copy(errorMessage = "Failed to open import URI")
                return@launch
            }

            val importedCount = input.use { bundleManager.importBundle(it) }
            refresh()
            _uiState.value = _uiState.value.copy(message = "Imported $importedCount recordings")
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null, errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        playbackController.stop()
    }
}

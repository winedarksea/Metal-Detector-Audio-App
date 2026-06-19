package com.metaldetectoraudioapp.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.metaldetectoraudioapp.app.AppContainerProvider
import com.metaldetectoraudioapp.app.recording.AudioPlaybackController
import com.metaldetectoraudioapp.app.recording.AudioTrim
import com.metaldetectoraudioapp.app.recording.RecordingMetadata
import com.metaldetectoraudioapp.app.recording.RecordingObjectLabel
import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.EnvironmentCache
import com.metaldetectoraudioapp.app.ui.model.parseLabelEntries
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

        playbackController.play(file, preferredOutputDevice = null) {
            _uiState.value = _uiState.value.copy(selectedPlayingId = null)
        }
        _uiState.value = _uiState.value.copy(selectedPlayingId = recording.recordingId)
    }

    fun toggleIncludeInTraining(recording: RecordingMetadata, includeInTraining: Boolean) {
        recordingRepository.updateRecording(recording.copy(includeInTraining = includeInTraining))
        refresh()
    }

    fun relabelTargetNames(recording: RecordingMetadata, targetNamesInput: String) {
        val objectLabels = parseLabelEntries(targetNamesInput).map {
            RecordingObjectLabel("${it.obj}:${it.name}:${it.material}", it.labelClass)
        }

        recordingRepository.updateRecording(
            recording.copy(objectLabels = objectLabels)
        )
        refresh()
    }

    fun relabelNotes(recording: RecordingMetadata, notes: String) {
        recordingRepository.updateRecording(recording.copy(notes = notes.ifBlank { null }))
        refresh()
    }

    fun relabelEnvironment(
        recording: RecordingMetadata,
        soilType: String,
        moisture: String,
        detectorModel: String,
        searchMode: String,
        audioProfile: String,
        sensitivity: String,
        recoverySpeed: String,
        stabilizer: String,
    ) {
        recordingRepository.updateRecording(
            recording.copy(
                soilType = soilType.ifBlank { null },
                moisture = moisture.ifBlank { null },
                detectorModel = detectorModel.ifBlank { null },
                searchMode = searchMode.ifBlank { null },
                audioProfile = audioProfile.ifBlank { null },
                sensitivity = sensitivity.ifBlank { null },
                recoverySpeed = recoverySpeed.ifBlank { null },
                stabilizer = stabilizer.ifBlank { null },
            )
        )
        _uiState.value = _uiState.value.copy(
            environmentCache = EnvironmentCache(
                detectorModel = detectorModel,
                searchMode = searchMode,
                audioProfile = audioProfile,
                sensitivity = sensitivity,
                recoverySpeed = recoverySpeed,
                stabilizer = stabilizer,
                soilType = soilType,
                moisture = moisture,
            )
        )
        refresh()
    }

    fun delete(recordingId: String) {
        recordingRepository.deleteRecording(recordingId)
        refresh()
    }

    fun openTrimEditor(recording: RecordingMetadata) {
        val file = recordingRepository.resolveAudioFile(recording) ?: return
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return
        val envelope = AudioTrim.clipEnvelope(bytes)
        val duration = AudioTrim.durationMs(bytes)
        _uiState.value = _uiState.value.copy(
            trimEditId = recording.recordingId,
            trimEnvelope = envelope,
            trimStartMs = 0L,
            trimEndMs = duration,
            trimFullDurationMs = duration,
        )
    }

    fun updateTrim(startMs: Long, endMs: Long) {
        _uiState.value = _uiState.value.copy(trimStartMs = startMs, trimEndMs = endMs)
    }

    fun resetTrim() {
        _uiState.value = _uiState.value.copy(
            trimStartMs = 0L,
            trimEndMs = _uiState.value.trimFullDurationMs,
        )
    }

    fun closeTrimEditor() {
        _uiState.value = _uiState.value.copy(
            trimEditId = null,
            trimEnvelope = emptyList(),
            trimStartMs = 0L,
            trimEndMs = 0L,
            trimFullDurationMs = 0L,
        )
    }

    fun saveTrim(recording: RecordingMetadata) {
        val state = _uiState.value
        val file = recordingRepository.resolveAudioFile(recording) ?: run {
            _uiState.value = state.copy(errorMessage = "Audio file missing: ${recording.audioFileName}")
            return
        }
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: run {
            _uiState.value = state.copy(errorMessage = "Unable to read audio for trimming")
            return
        }
        val trimmed = AudioTrim.trimWav(bytes, state.trimStartMs, state.trimEndMs)
        if (runCatching { file.writeBytes(trimmed) }.isFailure) {
            _uiState.value = state.copy(errorMessage = "Unable to save trimmed audio")
            return
        }
        val newDuration = AudioTrim.durationMs(trimmed)
        recordingRepository.updateRecording(recording.copy(durationMs = newDuration))
        closeTrimEditor()
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

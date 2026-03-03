package com.metaldetectoraudioapp.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.metaldetectoraudioapp.app.AppContainerProvider
import com.metaldetectoraudioapp.app.recording.AudioPlaybackController
import com.metaldetectoraudioapp.app.recording.AudioRecordingSession
import com.metaldetectoraudioapp.app.recording.CapturedRecording
import com.metaldetectoraudioapp.app.recording.RecordingLabelDraft
import com.metaldetectoraudioapp.app.recording.RecordingLocationProvider
import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.RecordingDraftUiState
import com.metaldetectoraudioapp.app.ui.model.RecordingUiState
import com.metaldetectoraudioapp.app.ui.model.SweepPattern
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RecordingViewModel(application: Application) : AndroidViewModel(application) {
    private val appContainer = AppContainerProvider.get(application.applicationContext)
    private val recordingRepository = appContainer.recordingRepository
    private val recordingSession = AudioRecordingSession(application.cacheDir)
    private val playbackController = AudioPlaybackController()
    private val locationProvider = RecordingLocationProvider(application.applicationContext)

    private var lastCapturedRecording: CapturedRecording? = null

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    fun startRecording() {
        val started = recordingSession.start()
        if (started) {
            _uiState.value = _uiState.value.copy(
                isRecording = true,
                saveResultMessage = null,
                errorMessage = null
            )
        } else {
            _uiState.value = _uiState.value.copy(errorMessage = "Unable to start recording")
        }
    }

    fun stopRecording() {
        val captured = recordingSession.stop()
        lastCapturedRecording = captured
        _uiState.value = _uiState.value.copy(
            isRecording = false,
            pendingAudioFile = captured?.tempAudioFile,
            pendingDurationMs = captured?.durationMs ?: 0,
            saveResultMessage = null,
            errorMessage = if (captured == null) "No recording available" else null
        )
    }

    fun updateTargetNames(value: String) {
        updateDraft(_uiState.value.draft.copy(targetNameInput = value))
    }

    fun updateClassLabel(value: ClassLabel) {
        updateDraft(_uiState.value.draft.copy(classLabel = value))
    }

    fun updatePattern(value: SweepPattern) {
        updateDraft(_uiState.value.draft.copy(pattern = value))
    }

    fun updateDepthInches(value: String) {
        updateDraft(_uiState.value.draft.copy(depthInches = value))
    }

    fun updateNotes(value: String) {
        updateDraft(_uiState.value.draft.copy(notesInput = value))
    }

    fun updateMixedFlag(value: Boolean) {
        updateDraft(
            _uiState.value.draft.copy(
                mixedFlag = value,
                includeInTraining = if (value) false else _uiState.value.draft.includeInTraining
            )
        )
    }

    fun updateIncludeInTraining(value: Boolean) {
        updateDraft(_uiState.value.draft.copy(includeInTraining = value))
    }

    fun updateSoilType(value: String) {
        updateDraft(_uiState.value.draft.copy(soilType = value))
    }

    fun updateMoisture(value: String) {
        updateDraft(_uiState.value.draft.copy(moisture = value))
    }

    fun updateDetectorModel(value: String) {
        updateDraft(_uiState.value.draft.copy(detectorModel = value))
    }

    fun captureCurrentLocation() {
        val location = locationProvider.readLatestKnownLocation()
        if (location == null) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Location unavailable. Grant location permission and ensure GPS is on."
            )
            return
        }

        val (latitude, longitude) = location
        updateDraft(
            _uiState.value.draft.copy(
                gpsLatitude = latitude,
                gpsLongitude = longitude
            )
        )
        _uiState.value = _uiState.value.copy(
            saveResultMessage = "Location captured: ${formatCoordinate(latitude)}, ${formatCoordinate(longitude)}",
            errorMessage = null
        )
    }

    fun onLocationPermissionDenied() {
        _uiState.value = _uiState.value.copy(
            errorMessage = "Location permission was denied."
        )
    }

    fun playPreview() {
        val file = _uiState.value.pendingAudioFile ?: return
        playbackController.play(file) {
            _uiState.value = _uiState.value.copy(isPlayingPreview = false)
        }
        _uiState.value = _uiState.value.copy(isPlayingPreview = true)
    }

    fun stopPreview() {
        playbackController.stop()
        _uiState.value = _uiState.value.copy(isPlayingPreview = false)
    }

    fun saveRecording() {
        val captured = lastCapturedRecording
        if (captured == null) {
            _uiState.value = _uiState.value.copy(errorMessage = "Record audio before saving")
            return
        }

        val draft = withAutoLocationIfAvailable(_uiState.value.draft)
        val targetNames = draft.targetNameInput
            .split(',', ';', '|')
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (targetNames.isEmpty() && draft.classLabel != ClassLabel.AMBIENT) {
            _uiState.value = _uiState.value.copy(errorMessage = "target_name is required")
            return
        }
        val invalidToken = targetNames.firstOrNull { !isCategoryObjectMaterialLabel(it) }
        if (draft.classLabel != ClassLabel.AMBIENT && invalidToken != null) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "target_name '$invalidToken' must be category:object:material"
            )
            return
        }

        viewModelScope.launch {
            val metadata = recordingRepository.saveCapturedRecording(
                capturedRecording = captured,
                labelDraft = RecordingLabelDraft(
                    targetNames = if (targetNames.isEmpty()) {
                        listOf("ambient:background:unknown")
                    } else {
                        targetNames
                    },
                    classLabel = draft.classLabel,
                    pattern = draft.pattern,
                    depthInches = draft.depthInches.ifBlank { null },
                    notes = draft.notesInput.ifBlank { null },
                    gpsLatitude = draft.gpsLatitude,
                    gpsLongitude = draft.gpsLongitude,
                    mixedFlag = draft.mixedFlag,
                    includeInTraining = draft.includeInTraining,
                    soilType = draft.soilType.ifBlank { null },
                    moisture = draft.moisture.ifBlank { null },
                    detectorModel = draft.detectorModel.ifBlank { null }
                )
            )

            lastCapturedRecording = null
            _uiState.value = RecordingUiState(
                draft = RecordingDraftUiState(
                    includeInTraining = true,
                    classLabel = metadata.classLabel,
                    pattern = metadata.pattern
                ),
                saveResultMessage = "Saved ${metadata.audioFileName}",
                pendingAudioFile = null,
                pendingDurationMs = 0,
                isRecording = false,
                isPlayingPreview = false
            )
        }
    }

    private fun withAutoLocationIfAvailable(
        draft: RecordingDraftUiState
    ): RecordingDraftUiState {
        if (draft.gpsLatitude != null && draft.gpsLongitude != null) {
            return draft
        }
        val location = locationProvider.readLatestKnownLocation() ?: return draft
        return draft.copy(gpsLatitude = location.first, gpsLongitude = location.second)
    }

    private fun isCategoryObjectMaterialLabel(value: String): Boolean {
        val parts = value.split(":")
        return parts.size == 3 && parts.all { it.isNotBlank() }
    }

    private fun formatCoordinate(value: Double): String = "%.6f".format(value)

    private fun updateDraft(newDraft: RecordingDraftUiState) {
        _uiState.value = _uiState.value.copy(draft = newDraft, errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        playbackController.stop()
        recordingSession.cancelAndDelete()
    }
}

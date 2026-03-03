package com.metaldetectoraudioapp.desktop.viewmodel

import com.metaldetectoraudioapp.app.recording.CapturedRecording
import com.metaldetectoraudioapp.app.recording.RecordingLabelDraft
import com.metaldetectoraudioapp.app.recording.RecordingRepository
import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.RecordingDraftUiState
import com.metaldetectoraudioapp.app.ui.model.RecordingUiState
import com.metaldetectoraudioapp.app.ui.model.SweepPattern
import com.metaldetectoraudioapp.desktop.audio.DesktopAudioPlaybackController
import com.metaldetectoraudioapp.desktop.audio.DesktopAudioRecordingSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DesktopRecordingViewModel(
    private val recordingRepository: RecordingRepository,
    recordingSessionCacheDirectoryPath: String,
) {
    private val recordingSession = DesktopAudioRecordingSession(
        java.io.File(recordingSessionCacheDirectoryPath)
    )
    private val playbackController = DesktopAudioPlaybackController()

    private var lastCapturedRecording: CapturedRecording? = null

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    val datasetDirectoryPath: String =
        recordingRepository.metadataFile().parentFile?.absolutePath.orEmpty()

    fun startRecording() {
        val started = recordingSession.start()
        _uiState.value = if (started) {
            _uiState.value.copy(
                isRecording = true,
                saveResultMessage = null,
                errorMessage = null
            )
        } else {
            _uiState.value.copy(errorMessage = "Unable to start recording")
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

    fun saveRecording() {
        val captured = lastCapturedRecording
        if (captured == null) {
            _uiState.value = _uiState.value.copy(errorMessage = "Record audio before saving")
            return
        }

        val draft = _uiState.value.draft
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
                gpsLatitude = null,
                gpsLongitude = null,
                mixedFlag = draft.mixedFlag,
                includeInTraining = draft.includeInTraining
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

    fun close() {
        playbackController.stop()
        recordingSession.cancelAndDelete()
    }

    private fun isCategoryObjectMaterialLabel(value: String): Boolean {
        val parts = value.split(":")
        return parts.size == 3 && parts.all { it.isNotBlank() }
    }

    private fun updateDraft(newDraft: RecordingDraftUiState) {
        _uiState.value = _uiState.value.copy(draft = newDraft, errorMessage = null)
    }
}

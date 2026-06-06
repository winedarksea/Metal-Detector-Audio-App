package com.metaldetectoraudioapp.desktop.viewmodel

import com.metaldetectoraudioapp.app.audio.AudioPlayer
import com.metaldetectoraudioapp.app.recording.CapturedRecording
import com.metaldetectoraudioapp.app.recording.RecordingLabelDraft
import com.metaldetectoraudioapp.app.recording.RecordingRepository
import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.PendingImage
import com.metaldetectoraudioapp.app.ui.model.RecordingDraftUiState
import com.metaldetectoraudioapp.app.ui.model.RecordingUiState
import com.metaldetectoraudioapp.app.ui.model.SweepPattern
import com.metaldetectoraudioapp.desktop.audio.DesktopAudioRecordingSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class DesktopRecordingViewModel(
    private val recordingRepository: RecordingRepository,
    private val audioPlayer: AudioPlayer,
    recordingSessionCacheDirectoryPath: String,
    val datasetDirectoryPath: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tempDirectory = File(recordingSessionCacheDirectoryPath).also { it.mkdirs() }
    private val recordingSession = DesktopAudioRecordingSession(tempDirectory)
    private var playbackJob: Job? = null

    private var lastCapturedRecording: CapturedRecording? = null
    private var recordingStartEpochMs: Long = 0L
    private var durationTickerJob: Job? = null

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    fun startRecording() {
        clearPendingCaptureInternal(announce = false)

        val started = recordingSession.start()
        if (started) {
            recordingStartEpochMs = System.currentTimeMillis()
            _uiState.value = _uiState.value.copy(
                isRecording = true,
                saveResultMessage = null,
                errorMessage = null,
                pendingDurationMs = 0,
            )
            startDurationTicker()
        } else {
            _uiState.value = _uiState.value.copy(errorMessage = "Unable to start recording")
        }
    }

    fun stopRecording() {
        stopDurationTicker()
        val captured = recordingSession.stop()
        lastCapturedRecording = captured
        _uiState.value = _uiState.value.copy(
            isRecording = false,
            pendingAudio = captured,
            pendingDurationMs = captured?.durationMs ?: 0,
            saveResultMessage = null,
            errorMessage = if (captured == null) "No recording available" else null
        )
    }

    fun clearPendingCapture() {
        clearPendingCaptureInternal(announce = true)
    }

    fun playPreview() {
        val captured = _uiState.value.pendingAudio ?: return
        stopPreview()
        playbackJob = scope.launch {
            _uiState.value = _uiState.value.copy(isPlayingPreview = true)
            audioPlayer.play(captured.wavBytes)
            _uiState.value = _uiState.value.copy(isPlayingPreview = false)
        }
    }

    fun stopPreview() {
        audioPlayer.stop()
        playbackJob?.cancel()
        playbackJob = null
        _uiState.value = _uiState.value.copy(isPlayingPreview = false)
    }

    fun updateTargetNames(value: String) {
        val isMixed = value.split(',', ';', '|').count { it.trim().isNotBlank() } > 1
        updateDraft(_uiState.value.draft.copy(targetNameInput = value, mixedFlag = isMixed))
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

    fun updateSearchMode(value: String) {
        updateDraft(_uiState.value.draft.copy(searchMode = value))
    }

    fun updateSensitivity(value: String) {
        updateDraft(_uiState.value.draft.copy(sensitivity = value))
    }

    fun updateRecoverySpeed(value: String) {
        updateDraft(_uiState.value.draft.copy(recoverySpeed = value))
    }

    fun updateStabilizer(value: String) {
        updateDraft(_uiState.value.draft.copy(stabilizer = value))
    }

    fun attachImageFromFile(sourceImage: File) {
        if (!sourceImage.exists()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Image file not found")
            return
        }
        val extension = sourceImage.extension.ifBlank { "jpg" }
        val bytes = runCatching { sourceImage.readBytes() }.getOrElse {
            _uiState.value = _uiState.value.copy(errorMessage = "Unable to attach image")
            return
        }
        _uiState.value = _uiState.value.copy(
            pendingImage = PendingImage(bytes, extension),
            saveResultMessage = "Image attached",
            errorMessage = null,
        )
    }

    fun removePendingImage() {
        _uiState.value = _uiState.value.copy(
            pendingImage = null,
            saveResultMessage = "Image removed",
            errorMessage = null,
        )
    }

    fun saveRecording() {
        val captured = lastCapturedRecording
        if (captured == null) {
            _uiState.value = _uiState.value.copy(errorMessage = "Record audio before saving")
            return
        }

        val draft = _uiState.value.draft
        val classLabel = draft.classLabel
        if (classLabel == null) {
            _uiState.value = _uiState.value.copy(errorMessage = "class_label is required")
            return
        }
        val targetNames = draft.targetNameInput
            .split(',', ';', '|')
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (targetNames.isEmpty() && classLabel != ClassLabel.AMBIENT) {
            _uiState.value = _uiState.value.copy(errorMessage = "target_name is required")
            return
        }

        val invalidToken = targetNames.firstOrNull { !isCategoryObjectMaterialLabel(it) }
        if (classLabel != ClassLabel.AMBIENT && invalidToken != null) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "target_name '$invalidToken' must be category:object:material"
            )
            return
        }

        val pendingImage = _uiState.value.pendingImage
        scope.launch {
            try {
                val metadata = recordingRepository.saveCapturedRecording(
                    capturedRecording = captured,
                    labelDraft = RecordingLabelDraft(
                        targetNames = if (targetNames.isEmpty()) {
                            listOf("ambient:background:unknown")
                        } else {
                            targetNames
                        },
                        classLabel = classLabel,
                        pattern = draft.pattern,
                        depthInches = draft.depthInches.ifBlank { null },
                        notes = draft.notesInput.ifBlank { null },
                        gpsLatitude = null,
                        gpsLongitude = null,
                        mixedFlag = targetNames.size > 1,
                        includeInTraining = draft.includeInTraining,
                        soilType = draft.soilType.ifBlank { null },
                        moisture = draft.moisture.ifBlank { null },
                        detectorModel = draft.detectorModel.ifBlank { null },
                        searchMode = draft.searchMode.ifBlank { null },
                        sensitivity = draft.sensitivity.ifBlank { null },
                        recoverySpeed = draft.recoverySpeed.ifBlank { null },
                        stabilizer = draft.stabilizer.ifBlank { null },
                        imageBytes = pendingImage?.bytes,
                        imageExtension = pendingImage?.extension,
                    )
                )

                lastCapturedRecording = null
                _uiState.value = RecordingUiState(
                    draft = RecordingDraftUiState(
                        includeInTraining = true,
                        classLabel = null,
                        pattern = metadata.pattern
                    ),
                    saveResultMessage = "Saved ${metadata.audioFileName}",
                    pendingAudio = null,
                    pendingImage = null,
                    pendingDurationMs = 0,
                    isRecording = false,
                    isPlayingPreview = false
                )
            } catch (e: Throwable) {
                _uiState.value = _uiState.value.copy(errorMessage = "Save failed: ${e.message}")
            }
        }
    }

    fun close() {
        clearPendingCaptureInternal(announce = false)
        audioPlayer.stop()
        playbackJob?.cancel()
        recordingSession.cancelAndDelete()
        scope.cancel()
    }

    private fun isCategoryObjectMaterialLabel(value: String): Boolean {
        val parts = value.split(":")
        return parts.size == 3 && parts.all { it.isNotBlank() }
    }

    private fun clearPendingCaptureInternal(announce: Boolean) {
        stopDurationTicker()
        stopPreview()

        lastCapturedRecording = null
        recordingStartEpochMs = 0L

        _uiState.value = _uiState.value.copy(
            pendingAudio = null,
            pendingImage = null,
            pendingDurationMs = 0,
            isPlayingPreview = false,
            saveResultMessage = if (announce) "Cleared unsaved recording" else null,
            errorMessage = null,
        )
    }

    private fun updateDraft(newDraft: RecordingDraftUiState) {
        _uiState.value = _uiState.value.copy(draft = newDraft, errorMessage = null)
    }

    private fun startDurationTicker() {
        durationTickerJob?.cancel()
        durationTickerJob = scope.launch {
            while (isActive && _uiState.value.isRecording) {
                val elapsed = (System.currentTimeMillis() - recordingStartEpochMs).coerceAtLeast(0L)
                _uiState.value = _uiState.value.copy(pendingDurationMs = elapsed)
                delay(250L)
            }
        }
    }

    private fun stopDurationTicker() {
        durationTickerJob?.cancel()
        durationTickerJob = null
    }
}

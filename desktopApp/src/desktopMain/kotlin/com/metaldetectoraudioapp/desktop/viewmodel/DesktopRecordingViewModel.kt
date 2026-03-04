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
    recordingSessionCacheDirectoryPath: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tempDirectory = File(recordingSessionCacheDirectoryPath).also { it.mkdirs() }
    private val recordingSession = DesktopAudioRecordingSession(tempDirectory)
    private val playbackController = DesktopAudioPlaybackController()

    private var lastCapturedRecording: CapturedRecording? = null
    private var recordingStartEpochMs: Long = 0L
    private var durationTickerJob: Job? = null

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    val datasetDirectoryPath: String =
        recordingRepository.metadataFile().parentFile?.absolutePath.orEmpty()

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
            pendingAudioFile = captured?.tempAudioFile,
            pendingDurationMs = captured?.durationMs ?: 0,
            saveResultMessage = null,
            errorMessage = if (captured == null) "No recording available" else null
        )
    }

    fun clearPendingCapture() {
        clearPendingCaptureInternal(announce = true)
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
        val tempImage = File(tempDirectory, "capture_img_${System.currentTimeMillis()}.$extension")
        val copyResult = runCatching {
            sourceImage.copyTo(tempImage, overwrite = true)
        }

        if (copyResult.isFailure) {
            tempImage.delete()
            _uiState.value = _uiState.value.copy(errorMessage = "Unable to attach image")
            return
        }

        replacePendingImage(tempImage)
        _uiState.value = _uiState.value.copy(saveResultMessage = "Image attached", errorMessage = null)
    }

    fun removePendingImage() {
        replacePendingImage(null)
        _uiState.value = _uiState.value.copy(saveResultMessage = "Image removed", errorMessage = null)
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
                includeInTraining = draft.includeInTraining,
                soilType = draft.soilType.ifBlank { null },
                moisture = draft.moisture.ifBlank { null },
                detectorModel = draft.detectorModel.ifBlank { null },
                searchMode = draft.searchMode.ifBlank { null },
                sensitivity = draft.sensitivity.ifBlank { null },
                recoverySpeed = draft.recoverySpeed.ifBlank { null },
                stabilizer = draft.stabilizer.ifBlank { null },
                imageTempFile = _uiState.value.pendingImageFile,
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
            pendingImageFile = null,
            pendingDurationMs = 0,
            isRecording = false,
            isPlayingPreview = false
        )
    }

    fun close() {
        clearPendingCaptureInternal(announce = false)
        playbackController.stop()
        recordingSession.cancelAndDelete()
        scope.cancel()
    }

    private fun isCategoryObjectMaterialLabel(value: String): Boolean {
        val parts = value.split(":")
        return parts.size == 3 && parts.all { it.isNotBlank() }
    }

    private fun replacePendingImage(newFile: File?) {
        val existing = _uiState.value.pendingImageFile
        if (existing != null && existing.absolutePath != newFile?.absolutePath) {
            existing.delete()
        }
        _uiState.value = _uiState.value.copy(pendingImageFile = newFile)
    }

    private fun clearPendingCaptureInternal(announce: Boolean) {
        stopDurationTicker()
        stopPreview()

        lastCapturedRecording?.tempAudioFile?.delete()
        lastCapturedRecording = null
        recordingStartEpochMs = 0L

        _uiState.value.pendingAudioFile?.delete()
        replacePendingImage(null)

        _uiState.value = _uiState.value.copy(
            pendingAudioFile = null,
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

package com.metaldetectoraudioapp.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.media.AudioDeviceInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.metaldetectoraudioapp.app.AppContainerProvider
import com.metaldetectoraudioapp.app.audio.source.AudioDeviceManager
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class RecordingViewModel(application: Application) : AndroidViewModel(application) {
    private val appContainer = AppContainerProvider.get(application.applicationContext)
    private val recordingRepository = appContainer.recordingRepository
    private val recordingSession = AudioRecordingSession(application.cacheDir)
    private val playbackController = AudioPlaybackController()
    private val locationProvider = RecordingLocationProvider(application.applicationContext)

    private var lastCapturedRecording: CapturedRecording? = null
    private var recordingStartEpochMs: Long = 0L
    private var durationTickerJob: Job? = null

    private val audioDeviceManager = AudioDeviceManager(application.applicationContext)
    val inputDevices: StateFlow<List<AudioDeviceInfo>> = audioDeviceManager.inputDevices

    private val _selectedInputDevice = MutableStateFlow<AudioDeviceInfo?>(null)
    val selectedInputDevice: StateFlow<AudioDeviceInfo?> = _selectedInputDevice.asStateFlow()

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            recordingSession.waveformPoints.collect { points ->
                _uiState.value = _uiState.value.copy(waveformPoints = points)
            }
        }
        viewModelScope.launch {
            recordingSession.rmsLevel.collect { rms ->
                _uiState.value = _uiState.value.copy(rmsLevel = rms)
            }
        }
    }

    fun setInputDevice(device: AudioDeviceInfo?) {
        _selectedInputDevice.value = device
    }

    fun startRecording() {
        clearPendingCaptureInternal(announce = false)

        val started = recordingSession.start(preferredInputDevice = _selectedInputDevice.value)
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

        val updatedDraft = withAutoLocationIfAvailable(_uiState.value.draft)
        _uiState.value = _uiState.value.copy(
            isRecording = false,
            pendingAudioFile = captured?.tempAudioFile,
            pendingDurationMs = captured?.durationMs ?: 0,
            draft = updatedDraft,
            saveResultMessage = null,
            errorMessage = if (captured == null) "No recording available" else null
        )
    }

    fun clearPendingCapture() {
        clearPendingCaptureInternal(announce = true)
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

    fun attachCapturedImage(bitmap: Bitmap?) {
        if (bitmap == null) {
            _uiState.value = _uiState.value.copy(errorMessage = "Image capture cancelled")
            return
        }

        val cacheDir = getApplication<Application>().cacheDir
        val tempImage = File(cacheDir, "capture_img_${System.currentTimeMillis()}.jpg")
        val writeResult = runCatching {
            tempImage.outputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                    error("Failed to encode JPEG")
                }
            }
        }

        if (writeResult.isFailure) {
            tempImage.delete()
            _uiState.value = _uiState.value.copy(errorMessage = "Unable to save captured image")
            return
        }

        replacePendingImage(tempImage)
        _uiState.value = _uiState.value.copy(saveResultMessage = "Image attached", errorMessage = null)
    }

    fun removePendingImage() {
        replacePendingImage(null)
        _uiState.value = _uiState.value.copy(saveResultMessage = "Image removed", errorMessage = null)
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

    fun onCameraPermissionDenied() {
        _uiState.value = _uiState.value.copy(
            errorMessage = "Camera permission was denied."
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

        viewModelScope.launch {
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
                    gpsLatitude = draft.gpsLatitude,
                    gpsLongitude = draft.gpsLongitude,
                    mixedFlag = targetNames.size > 1,
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
                    classLabel = null,
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
            waveformPoints = emptyList(),
            rmsLevel = 0f,
            draft = if (announce) _uiState.value.draft.copy(
                targetNameInput = "",
                classLabel = null,
                depthInches = "",
                notesInput = "",
                mixedFlag = false
            ) else _uiState.value.draft
        )
    }

    private fun updateDraft(newDraft: RecordingDraftUiState) {
        _uiState.value = _uiState.value.copy(draft = newDraft, errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        clearPendingCaptureInternal(announce = false)
        playbackController.stop()
        recordingSession.cancelAndDelete()
        audioDeviceManager.release()
    }

    private fun startDurationTicker() {
        durationTickerJob?.cancel()
        durationTickerJob = viewModelScope.launch {
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

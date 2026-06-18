package com.metaldetectoraudioapp.web.viewmodel

import com.metaldetectoraudioapp.app.audio.AudioPlayer
import com.metaldetectoraudioapp.app.export.DatasetBundleManager
import com.metaldetectoraudioapp.app.platform.FileDownloader
import com.metaldetectoraudioapp.app.platform.FilePicker
import com.metaldetectoraudioapp.app.recording.AudioTrim
import com.metaldetectoraudioapp.app.recording.RecordingMetadata
import com.metaldetectoraudioapp.app.recording.RecordingObjectLabel
import com.metaldetectoraudioapp.app.recording.RecordingRepository
import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.parseLabelEntries
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

class WebReviewViewModel(
    private val recordingRepository: RecordingRepository,
    private val bundleManager: DatasetBundleManager,
    private val audioPlayer: AudioPlayer,
    private val fileDownloader: FileDownloader,
    private val filePicker: FilePicker,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playbackJob: Job? = null

    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    init { refresh() }

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
                _uiState.value = _uiState.value.copy(errorMessage = "Audio missing: ${recording.audioFileName}")
                return@launch
            }
            _uiState.value = _uiState.value.copy(selectedPlayingId = recording.recordingId)
            audioPlayer.play(bytes)
            _uiState.value = _uiState.value.copy(selectedPlayingId = null)
        }
    }

    fun toggleIncludeInTraining(recording: RecordingMetadata, value: Boolean) {
        scope.launch {
            recordingRepository.updateRecording(recording.copy(includeInTraining = value))
            refresh()
        }
    }

    fun relabelTargetNames(recording: RecordingMetadata, input: String) {
        val objectLabels = parseLabelEntries(input).map {
            RecordingObjectLabel("${it.obj}:${it.name}:${it.material}", it.labelClass)
        }
        scope.launch {
            recordingRepository.updateRecording(recording.copy(objectLabels = objectLabels))
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
        audioProfile: String,
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
                    audioProfile = audioProfile.ifBlank { null },
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

    fun openTrimEditor(recording: RecordingMetadata) {
        scope.launch {
            val bytes = recordingRepository.readAudioBytes(recording) ?: return@launch
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
        scope.launch {
            val bytes = recordingRepository.readAudioBytes(recording) ?: run {
                _uiState.value = state.copy(errorMessage = "Audio missing: ${recording.audioFileName}")
                return@launch
            }
            val trimmed = AudioTrim.trimWav(bytes, state.trimStartMs, state.trimEndMs)
            val newDuration = AudioTrim.durationMs(trimmed)
            recordingRepository.saveAudioAndUpdateDuration(recording, trimmed, newDuration)
            closeTrimEditor()
            refresh()
        }
    }

    fun exportBundle() {
        scope.launch {
            runCatching {
                val bytes = bundleManager.exportBundle()
                val fileName = "detector_dataset_${com.metaldetectoraudioapp.app.util.Clocks.epochMillis()}.zip"
                fileDownloader.download(fileName, bytes, "application/zip")
            }.onSuccess {
                _uiState.value = _uiState.value.copy(message = "Dataset exported — check your downloads", errorMessage = null)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(errorMessage = "Export failed: ${e.message}")
            }
        }
    }

    fun importBundle() {
        scope.launch {
            runCatching {
                val bytes = filePicker.pickFile(listOf("application/zip", ".zip")) ?: return@launch
                bundleManager.importBundle(bytes)
            }.onSuccess { count ->
                refresh()
                _uiState.value = _uiState.value.copy(message = "Imported $count recordings", errorMessage = null)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(errorMessage = "Import failed: ${e.message}")
            }
        }
    }

    fun setMessage(msg: String?) {
        _uiState.value = _uiState.value.copy(message = msg)
    }

    fun close() {
        audioPlayer.stop()
        playbackJob?.cancel()
        scope.cancel()
    }
}

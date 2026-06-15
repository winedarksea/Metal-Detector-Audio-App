package com.metaldetectoraudioapp.app.ui.model

import com.metaldetectoraudioapp.app.recording.CapturedRecording

/** A pending image attachment held in memory before the recording is saved. */
class PendingImage(
    val bytes: ByteArray,
    val extension: String,
)

data class RecordingUiState(
    val isRecording: Boolean = false,
    val pendingAudio: CapturedRecording? = null,
    val pendingImage: PendingImage? = null,
    val isPhotoCaptureInProgress: Boolean = false,
    val isLocationCaptureInProgress: Boolean = false,
    val pendingDurationMs: Long = 0,
    val draft: RecordingDraftUiState = RecordingDraftUiState(),
    val saveResultMessage: String? = null,
    val errorMessage: String? = null,
    val isPlayingPreview: Boolean = false,
)

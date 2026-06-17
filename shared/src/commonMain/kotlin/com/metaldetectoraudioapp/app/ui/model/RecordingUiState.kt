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
    /** Static amplitude waveform of the captured clip, 0..1, for the trim control. */
    val clipEnvelope: List<Float> = emptyList(),
    /** Selected trim bounds; [trimEndMs] is set to the full duration when a clip is captured. */
    val trimStartMs: Long = 0,
    val trimEndMs: Long = 0,
) {
    /** True when the user has trimmed away part of the captured clip. */
    val isTrimmed: Boolean
        get() = pendingDurationMs > 0 &&
            (trimStartMs > 0 || (trimEndMs in 1 until pendingDurationMs))
}

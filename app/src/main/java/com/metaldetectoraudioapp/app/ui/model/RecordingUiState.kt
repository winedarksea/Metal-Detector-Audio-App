package com.metaldetectoraudioapp.app.ui.model

import java.io.File

data class RecordingUiState(
    val isRecording: Boolean = false,
    val pendingAudioFile: File? = null,
    val pendingImageFile: File? = null,
    val pendingDurationMs: Long = 0,
    val draft: RecordingDraftUiState = RecordingDraftUiState(),
    val saveResultMessage: String? = null,
    val errorMessage: String? = null,
    val isPlayingPreview: Boolean = false,
    val waveformPoints: List<Float> = emptyList(),
    val rmsLevel: Float = 0f,
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

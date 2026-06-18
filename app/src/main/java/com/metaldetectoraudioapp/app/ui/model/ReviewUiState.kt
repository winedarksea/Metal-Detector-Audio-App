package com.metaldetectoraudioapp.app.ui.model

import com.metaldetectoraudioapp.app.recording.RecordingMetadata

data class ReviewUiState(
    val recordings: List<RecordingMetadata> = emptyList(),
    val selectedPlayingId: String? = null,
    val message: String? = null,
    val errorMessage: String? = null,
    val trimEditId: String? = null,
    val trimEnvelope: List<Float> = emptyList(),
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val trimFullDurationMs: Long = 0L,
) {
    val isTrimmed: Boolean
        get() = trimFullDurationMs > 0 && (trimStartMs > 0L || trimEndMs < trimFullDurationMs)
}

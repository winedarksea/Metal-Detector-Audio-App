package com.metaldetectoraudioapp.app.ui.model

import com.metaldetectoraudioapp.app.recording.RecordingMetadata

data class ReviewUiState(
    val recordings: List<RecordingMetadata> = emptyList(),
    val selectedPlayingId: String? = null,
    val message: String? = null,
    val errorMessage: String? = null
)

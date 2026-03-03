package com.metaldetectoraudioapp.app.ui.model

import java.io.File

data class RecordingUiState(
    val isRecording: Boolean = false,
    val pendingAudioFile: File? = null,
    val pendingDurationMs: Long = 0,
    val draft: RecordingDraftUiState = RecordingDraftUiState(),
    val saveResultMessage: String? = null,
    val errorMessage: String? = null,
    val isPlayingPreview: Boolean = false
)

package com.metaldetectoraudioapp.app.recording

import java.io.File

data class CapturedRecording(
    val tempAudioFile: File,
    val durationMs: Long
)

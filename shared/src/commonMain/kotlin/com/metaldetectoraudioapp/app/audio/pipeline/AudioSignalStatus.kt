package com.metaldetectoraudioapp.app.audio.pipeline

data class AudioSignalStatus(
    val rmsLevel: Float = 0f,
    val clippingDetected: Boolean = false,
    val signalPresent: Boolean = false
)

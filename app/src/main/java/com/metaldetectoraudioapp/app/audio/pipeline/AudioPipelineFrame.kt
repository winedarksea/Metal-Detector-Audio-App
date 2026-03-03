package com.metaldetectoraudioapp.app.audio.pipeline

data class AudioPipelineFrame(
    val samples: FloatArray,
    val receivedAtNanos: Long,
    val rmsLevel: Float,
    val clippingDetected: Boolean,
    val signalPresent: Boolean
)

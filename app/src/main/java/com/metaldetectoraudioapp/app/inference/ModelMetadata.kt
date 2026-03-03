package com.metaldetectoraudioapp.app.inference

data class ModelInputConfig(
    val sampleRateHz: Int,
    val windowSizeSamples: Int,
    val hopSizeSamples: Int,
    val expectsNormalizedAudio: Boolean
)

data class ModelMetadata(
    val modelName: String,
    val modelVersion: String,
    val labels: List<String>,
    val input: ModelInputConfig,
    val recommendedThreshold: Float = 0.55f
)

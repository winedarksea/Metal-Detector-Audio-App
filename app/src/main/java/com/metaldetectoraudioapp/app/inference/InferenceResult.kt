package com.metaldetectoraudioapp.app.inference

data class InferenceResult(
    val topLabel: String,
    val topScore: Float,
    val perLabelScores: Map<String, Float>,
    val inferenceTimeMs: Long
)

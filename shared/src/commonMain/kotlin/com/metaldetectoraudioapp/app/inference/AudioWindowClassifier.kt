package com.metaldetectoraudioapp.app.inference

interface AudioWindowClassifier {
    val activeAccelerator: InferenceAccelerator
    suspend fun classifyAudioWindow(samples: FloatArray): InferenceResult
    fun close()
}

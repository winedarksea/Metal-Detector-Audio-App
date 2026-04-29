package com.metaldetectoraudioapp.app.inference

interface AudioWindowClassifier {
    val activeAccelerator: InferenceAccelerator
    fun classifyAudioWindow(samples: FloatArray): InferenceResult
    fun close()
}

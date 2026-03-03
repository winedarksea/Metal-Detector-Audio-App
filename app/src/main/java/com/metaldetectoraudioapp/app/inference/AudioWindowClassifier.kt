package com.metaldetectoraudioapp.app.inference

interface AudioWindowClassifier {
    fun classifyAudioWindow(samples: FloatArray): InferenceResult
    fun close()
}

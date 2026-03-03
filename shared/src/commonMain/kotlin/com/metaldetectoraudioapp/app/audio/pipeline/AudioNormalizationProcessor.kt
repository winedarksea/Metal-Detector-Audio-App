package com.metaldetectoraudioapp.app.audio.pipeline

import kotlin.math.abs

class AudioNormalizationProcessor(
    private val clampScaleFactor: Float = 1.0f
) {
    fun normalizeInPlace(samples: FloatArray): Float {
        var maxMagnitude = 0f
        for (value in samples) {
            val magnitude = abs(value)
            if (magnitude > maxMagnitude) {
                maxMagnitude = magnitude
            }
        }
        if (maxMagnitude <= 1e-7f) {
            return 1f
        }

        val scale = (1f / maxMagnitude) * clampScaleFactor
        for (index in samples.indices) {
            samples[index] = (samples[index] * scale).coerceIn(-1f, 1f)
        }
        return scale
    }
}

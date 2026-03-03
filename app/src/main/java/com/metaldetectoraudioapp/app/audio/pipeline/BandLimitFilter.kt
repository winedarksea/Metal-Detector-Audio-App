package com.metaldetectoraudioapp.app.audio.pipeline

import kotlin.math.PI

class BandLimitFilter(
    sampleRateHz: Int,
    lowCutHz: Float = 120f,
    highCutHz: Float = 3_500f
) {
    private val dt = 1f / sampleRateHz.toFloat()
    private val highPassAlpha = computeHighPassAlpha(lowCutHz)
    private val lowPassAlpha = computeLowPassAlpha(highCutHz)

    private var highPassOutput = 0f
    private var highPassInput = 0f
    private var lowPassOutput = 0f

    fun processInPlace(samples: FloatArray) {
        for (index in samples.indices) {
            val highPassed = highPassAlpha * (highPassOutput + samples[index] - highPassInput)
            highPassInput = samples[index]
            highPassOutput = highPassed

            val lowPassed = lowPassOutput + lowPassAlpha * (highPassed - lowPassOutput)
            lowPassOutput = lowPassed
            samples[index] = lowPassed
        }
    }

    fun reset() {
        highPassOutput = 0f
        highPassInput = 0f
        lowPassOutput = 0f
    }

    private fun computeHighPassAlpha(cutoffHz: Float): Float {
        val rc = 1.0 / (2.0 * PI * cutoffHz)
        return (rc / (rc + dt)).toFloat()
    }

    private fun computeLowPassAlpha(cutoffHz: Float): Float {
        val rc = 1.0 / (2.0 * PI * cutoffHz)
        return (dt / (rc + dt)).toFloat()
    }
}

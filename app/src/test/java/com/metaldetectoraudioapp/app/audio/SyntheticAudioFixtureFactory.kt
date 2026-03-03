package com.metaldetectoraudioapp.app.audio

import kotlin.math.PI
import kotlin.math.sin

object SyntheticAudioFixtureFactory {
    fun sineWave(sampleRateHz: Int, frequencyHz: Float, durationMs: Int, amplitude: Float): FloatArray {
        val sampleCount = (sampleRateHz * durationMs) / 1000
        return FloatArray(sampleCount) { index ->
            (amplitude * sin(2.0 * PI * frequencyHz * index / sampleRateHz)).toFloat()
        }
    }

    fun alternatingPulse(sampleCount: Int, amplitude: Float = 1f): FloatArray {
        return FloatArray(sampleCount) { index -> if (index % 2 == 0) amplitude else -amplitude }
    }

    fun append(vararg arrays: FloatArray): FloatArray {
        val total = arrays.sumOf { it.size }
        val out = FloatArray(total)
        var cursor = 0
        arrays.forEach { part ->
            part.copyInto(out, destinationOffset = cursor)
            cursor += part.size
        }
        return out
    }
}

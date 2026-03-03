package com.metaldetectoraudioapp.app.audio.pipeline

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

class SpectrogramFeatureExtractor(
    private val fftSize: Int = 512,
    private val hopSize: Int = 256,
    private val binsToKeep: Int = 64
) {
    private val window = FloatArray(fftSize) { index ->
        (0.5 - 0.5 * cos(2.0 * PI * index / (fftSize - 1))).toFloat()
    }

    fun extractLogMagnitudeSpectrogram(signal: FloatArray): Array<FloatArray> {
        if (signal.size < fftSize) {
            return emptyArray()
        }
        val frameCount = 1 + (signal.size - fftSize) / hopSize
        val spectrogram = Array(frameCount) { FloatArray(binsToKeep) }

        for (frameIndex in 0 until frameCount) {
            val start = frameIndex * hopSize
            val real = FloatArray(fftSize)
            val imag = FloatArray(fftSize)
            for (offset in 0 until fftSize) {
                real[offset] = signal[start + offset] * window[offset]
                imag[offset] = 0f
            }

            dftInPlace(real, imag)
            for (bin in 0 until binsToKeep) {
                val magnitude = sqrt(real[bin] * real[bin] + imag[bin] * imag[bin])
                spectrogram[frameIndex][bin] = ln(1e-6f + magnitude)
            }
        }

        return spectrogram
    }

    private fun dftInPlace(real: FloatArray, imag: FloatArray) {
        val n = real.size
        val outReal = FloatArray(n)
        val outImag = FloatArray(n)

        for (k in 0 until n) {
            var sumReal = 0.0
            var sumImag = 0.0
            for (t in 0 until n) {
                val angle = -2.0 * PI * t * k / n
                val c = cos(angle)
                val s = sin(angle)
                sumReal += real[t] * c - imag[t] * s
                sumImag += real[t] * s + imag[t] * c
            }
            outReal[k] = sumReal.toFloat()
            outImag[k] = sumImag.toFloat()
        }

        for (index in 0 until n) {
            real[index] = outReal[index]
            imag[index] = outImag[index]
        }
    }
}

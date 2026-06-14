package com.metaldetectoraudioapp.app.audio.pipeline

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Android-local log-mel extractor for the LiteRT split-model path.
 *
 * The app module cannot currently depend on the shared KMP inference classes
 * without broader module consolidation, so this stays in lock-step with the
 * shared extractor and is guarded by Python config-sync tests.
 */
class AndroidMelSpectrogramFeatureExtractor(
    private val sampleRate: Int = 16000,
    private val fftLength: Int = 256,
    private val frameLength: Int = 256,
    private val frameStep: Int = 128,
    private val numMelBins: Int = 40,
    private val melLowerHz: Float = 80.0f,
    private val melUpperHz: Float = 7600.0f,
) {
    private val numSpectrogramBins = fftLength / 2 + 1

    private val hannWindow = FloatArray(frameLength) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / frameLength)).toFloat()
    }

    private val melFilterbank: Array<FloatArray> = buildMelFilterbank()

    fun extractLogMelSpectrogram(signal: FloatArray): Array<FloatArray> {
        if (signal.size < frameLength) return emptyArray()

        val timeFrames = 1 + (signal.size - frameLength) / frameStep
        val result = Array(timeFrames) { FloatArray(numMelBins) }

        for (frameIndex in 0 until timeFrames) {
            val start = frameIndex * frameStep
            val magnitudes = computeStftMagnitude(signal, start)
            for (mel in 0 until numMelBins) {
                var melEnergy = 0.0f
                for (bin in 0 until numSpectrogramBins) {
                    melEnergy += magnitudes[bin] * melFilterbank[bin][mel]
                }
                result[frameIndex][mel] = ln(melEnergy + 1e-6f)
            }
        }

        return result
    }

    /**
     * Peak-normalize -> log-mel -> per-window min-max scale to [0, 1]. The model's spectral
     * input, fully loudness-invariant. Matches scripts/mel_cnn_pipeline.py.
     */
    fun extractScaledSpectrogram(signal: FloatArray): Array<FloatArray> {
        val spectrogram = extractLogMelSpectrogram(peakNormalize(signal))
        if (spectrogram.isEmpty()) return spectrogram

        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        for (frame in spectrogram) {
            for (value in frame) {
                if (value < min) min = value
                if (value > max) max = value
            }
        }
        val range = max - min + 1e-6f
        for (frame in spectrogram) {
            for (i in frame.indices) {
                frame[i] = (frame[i] - min) / range
            }
        }
        return spectrogram
    }

    /** Peak-normalized (not min-max scaled) log-mel for the ribbon visual. */
    fun extractRibbonLogMel(signal: FloatArray): Array<FloatArray> =
        extractLogMelSpectrogram(peakNormalize(signal))

    /** Loudness scalar: ln(rms + eps) of the RAW window; the model graph standardizes it. */
    fun computeLoudness(signal: FloatArray): Float {
        if (signal.isEmpty()) return ln(LOUDNESS_EPSILON)
        var sumOfSquares = 0f
        for (value in signal) sumOfSquares += value * value
        val rms = sqrt(sumOfSquares / signal.size)
        return ln(rms + LOUDNESS_EPSILON)
    }

    /** wn = w / (max|w| + eps). Matches the in-graph peak_norm layer. */
    private fun peakNormalize(signal: FloatArray): FloatArray {
        var peak = 0f
        for (value in signal) {
            val magnitude = abs(value)
            if (magnitude > peak) peak = magnitude
        }
        val scale = 1f / (peak + LOUDNESS_EPSILON)
        return FloatArray(signal.size) { signal[it] * scale }
    }

    fun extractFlattenedForModel(
        signal: FloatArray,
        expectedTimeFrames: Int,
        expectedMelBins: Int,
    ): FloatArray {
        val spectrogram = extractScaledSpectrogram(signal)
        val flattened = FloatArray(expectedTimeFrames * expectedMelBins)
        var index = 0

        for (frameIndex in 0 until expectedTimeFrames) {
            val frame = spectrogram.getOrNull(frameIndex)
            for (melIndex in 0 until expectedMelBins) {
                flattened[index++] = frame?.getOrNull(melIndex) ?: 0.0f
            }
        }

        return flattened
    }

    private fun computeStftMagnitude(signal: FloatArray, offset: Int): FloatArray {
        val real = FloatArray(fftLength)
        val imag = FloatArray(fftLength)
        for (i in 0 until frameLength) {
            real[i] = signal[offset + i] * hannWindow[i]
        }
        fftInPlace(real, imag, fftLength)
        val magnitudes = FloatArray(numSpectrogramBins)
        for (bin in 0 until numSpectrogramBins) {
            magnitudes[bin] = sqrt(real[bin] * real[bin] + imag[bin] * imag[bin])
        }
        return magnitudes
    }

    private fun fftInPlace(real: FloatArray, imag: FloatArray, n: Int) {
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                var tmp = real[i]
                real[i] = real[j]
                real[j] = tmp
                tmp = imag[i]
                imag[i] = imag[j]
                imag[j] = tmp
            }
            var m = n / 2
            while (m >= 1 && j >= m) {
                j -= m
                m /= 2
            }
            j += m
        }

        var step = 2
        while (step <= n) {
            val halfStep = step / 2
            val angle = -2.0 * PI / step
            for (k in 0 until halfStep) {
                val theta = angle * k
                val wr = cos(theta).toFloat()
                val wi = sin(theta).toFloat()
                var i = k
                while (i < n) {
                    val ir = i + halfStep
                    val tr = wr * real[ir] - wi * imag[ir]
                    val ti = wr * imag[ir] + wi * real[ir]
                    real[ir] = real[i] - tr
                    imag[ir] = imag[i] - ti
                    real[i] += tr
                    imag[i] += ti
                    i += step
                }
            }
            step *= 2
        }
    }

    private fun buildMelFilterbank(): Array<FloatArray> {
        val melLow = hzToMel(melLowerHz)
        val melHigh = hzToMel(melUpperHz)
        val melEdges = FloatArray(numMelBins + 2) { i ->
            melToHz(melLow + (melHigh - melLow) * i / (numMelBins + 1))
        }

        val fftFreqs = FloatArray(numSpectrogramBins) { bin ->
            bin.toFloat() * sampleRate / fftLength
        }

        val filterbank = Array(numSpectrogramBins) { FloatArray(numMelBins) }
        for (mel in 0 until numMelBins) {
            val lower = melEdges[mel]
            val center = melEdges[mel + 1]
            val upper = melEdges[mel + 2]
            for (bin in 0 until numSpectrogramBins) {
                val freq = fftFreqs[bin]
                val weight = when {
                    freq < lower -> 0.0f
                    freq <= center -> (freq - lower) / (center - lower)
                    freq <= upper -> (upper - freq) / (upper - center)
                    else -> 0.0f
                }
                filterbank[bin][mel] = weight
            }
        }
        return filterbank
    }

    private fun hzToMel(hz: Float): Float =
        1127.0f * ln(1.0f + hz / 700.0f)

    private fun melToHz(mel: Float): Float =
        700.0f * (kotlin.math.exp(mel / 1127.0f) - 1.0f)

    companion object {
        /** Matches LOUDNESS_EPSILON in scripts/mel_cnn_pipeline.py and the shared extractor. */
        const val LOUDNESS_EPSILON = 1e-6f
    }
}
package com.metaldetectoraudioapp.app.audio.pipeline

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Computes a log-mel spectrogram matching the training pipeline parameters:
 *   STFT frame_length=256, frame_step=128, fft_length=256
 *   40 mel bins, 80-7600 Hz, sample_rate=16000
 *   log compression: ln(mel + 1e-6)
 *
 * The model is loudness-invariant (see scripts/mel_cnn_pipeline.py):
 *   * spectral input  = peak-normalize window -> log-mel -> per-window min-max scale
 *     ([extractScaledSpectrogram], flattened by [extractFlattenedForOnnx]).
 *   * loudness scalar = ln(rms + eps) of the RAW window ([computeLoudness]); the model
 *     graph standardizes it, so we feed the raw log-RMS.
 *
 * Output shape for 8000-sample input: [61 time-frames, 40 mel-bins].
 * The desktop ONNX CNN model expects the spectrogram as [1, 61, 40, 1] plus a [1, 1] loudness.
 */
class MelSpectrogramFeatureExtractor(
    private val sampleRate: Int = 16000,
    private val fftLength: Int = 256,
    private val frameLength: Int = 256,
    private val frameStep: Int = 128,
    private val numMelBins: Int = 40,
    private val melLowerHz: Float = 80.0f,
    private val melUpperHz: Float = 7600.0f,
) {
    private val numSpectrogramBins = fftLength / 2 + 1  // 129

    // Hann window matching tf.signal.stft default
    private val hannWindow = FloatArray(frameLength) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / frameLength)).toFloat()
    }

    // Pre-computed mel filterbank matrix [numSpectrogramBins x numMelBins]
    private val melFilterbank: Array<FloatArray> = buildMelFilterbank()

    /**
     * Compute log-mel spectrogram from raw PCM float samples.
     * @return Array of [timeFrames][melBins] float values (log-compressed).
     */
    fun extractLogMelSpectrogram(signal: FloatArray): Array<FloatArray> {
        if (signal.size < frameLength) return emptyArray()

        val timeFrames = 1 + (signal.size - frameLength) / frameStep
        val result = Array(timeFrames) { FloatArray(numMelBins) }

        for (frameIndex in 0 until timeFrames) {
            val start = frameIndex * frameStep
            val magnitudes = computeStftMagnitude(signal, start)
            // Mel filterbank projection + log compression
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
     * Peak-normalize the window to 1.0 (0 dBFS), then compute the log-mel spectrogram,
     * then min-max scale it to [0, 1] over the whole matrix. This is the model's spectral
     * input — fully invariant to absolute loudness. Matches scripts/mel_cnn_pipeline.py.
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

    /**
     * Log-mel for the tone-quality ribbon visual. Peak-normalized (so amplitude scale stays
     * stable across loudness) but NOT min-max scaled — RibbonAnalyzer is tuned for raw
     * log-mel magnitudes (ln scale), not [0, 1].
     */
    fun extractRibbonLogMel(signal: FloatArray): Array<FloatArray> =
        extractLogMelSpectrogram(peakNormalize(signal))

    /**
     * Loudness scalar fed to the model's second input: ln(rms + eps) of the RAW (un-peak-
     * normalized) window. The model graph applies standardization; we feed raw log-RMS.
     */
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

    /**
     * Flatten the scaled spectrogram into the shape expected by the ONNX/TFLite CNN model:
     * [1, timeFrames, melBins, 1].
     */
    fun extractFlattenedForOnnx(signal: FloatArray): FloatArray {
        val spectrogram = extractScaledSpectrogram(signal)
        if (spectrogram.isEmpty()) return FloatArray(0)
        val flat = FloatArray(spectrogram.size * numMelBins)
        var idx = 0
        for (frame in spectrogram) {
            for (value in frame) {
                flat[idx++] = value
            }
        }
        return flat
    }

    /** STFT magnitude for a single frame starting at [offset]. */
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

    /**
     * In-place Cooley-Tukey radix-2 FFT. Requires [n] to be a power of 2.
     * Operates on interleaved [real] and [imag] arrays of length [n].
     */
    private fun fftInPlace(real: FloatArray, imag: FloatArray, n: Int) {
        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                var tmp = real[i]; real[i] = real[j]; real[j] = tmp
                tmp = imag[i]; imag[i] = imag[j]; imag[j] = tmp
            }
            var m = n / 2
            while (m >= 1 && j >= m) {
                j -= m
                m /= 2
            }
            j += m
        }
        // Butterfly stages
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

    /**
     * Builds a mel filterbank matrix matching tf.signal.linear_to_mel_weight_matrix.
     * Shape: [numSpectrogramBins, numMelBins].
     */
    private fun buildMelFilterbank(): Array<FloatArray> {
        val melLow = hzToMel(melLowerHz)
        val melHigh = hzToMel(melUpperHz)
        // numMelBins + 2 edges for triangular filters
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

    companion object {
        /** Floor for peak-norm divisor and log-RMS loudness. Matches LOUDNESS_EPSILON in
         *  scripts/mel_cnn_pipeline.py and the app-side AndroidMelSpectrogramFeatureExtractor. */
        const val LOUDNESS_EPSILON = 1e-6f

        /** Hz to mel scale (Slaney/HTK formula matching TensorFlow). */
        private fun hzToMel(hz: Float): Float =
            1127.0f * ln(1.0f + hz / 700.0f)

        /** Mel to Hz scale. */
        private fun melToHz(mel: Float): Float =
            700.0f * (kotlin.math.exp(mel / 1127.0f) - 1.0f)
    }
}

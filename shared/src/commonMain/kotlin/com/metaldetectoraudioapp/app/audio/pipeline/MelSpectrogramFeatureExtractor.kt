package com.metaldetectoraudioapp.app.audio.pipeline

import kotlin.math.PI
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
 * Output shape for 8000-sample input: [61 time-frames, 40 mel-bins].
 * The desktop ONNX CNN model expects this as [1, 61, 40, 1].
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
     * Flatten log-mel spectrogram into the shape expected by the ONNX model:
     * [1, timeFrames, melBins, 1].
     */
    fun extractFlattenedForOnnx(signal: FloatArray): FloatArray {
        val spectrogram = extractLogMelSpectrogram(signal)
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
        /** Hz to mel scale (Slaney/HTK formula matching TensorFlow). */
        private fun hzToMel(hz: Float): Float =
            1127.0f * ln(1.0f + hz / 700.0f)

        /** Mel to Hz scale. */
        private fun melToHz(mel: Float): Float =
            700.0f * (kotlin.math.exp(mel / 1127.0f) - 1.0f)
    }
}

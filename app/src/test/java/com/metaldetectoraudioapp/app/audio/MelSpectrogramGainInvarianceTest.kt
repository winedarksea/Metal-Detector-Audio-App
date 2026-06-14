package com.metaldetectoraudioapp.app.audio

import com.metaldetectoraudioapp.app.audio.pipeline.AndroidMelSpectrogramFeatureExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

/**
 * The model's spectral input must be loudness-invariant: peak-normalization + per-window
 * min-max scaling mean the same tone at different gains yields the SAME scaled spectrogram,
 * while only the loudness scalar changes. This is the core guarantee of the redesign.
 */
class MelSpectrogramGainInvarianceTest {

    private val extractor = AndroidMelSpectrogramFeatureExtractor()

    @Test
    fun scaledSpectrogram_isIdenticalAcrossGains() {
        val base = SyntheticAudioFixtureFactory.sineWave(
            sampleRateHz = 16_000, frequencyHz = 1_200f, durationMs = 500, amplitude = 0.8f,
        )
        val reference = extractor.extractScaledSpectrogram(base)

        for (gain in floatArrayOf(0.5f, 0.25f, 0.0625f)) {
            val scaled = FloatArray(base.size) { base[it] * gain }
            val spectrogram = extractor.extractScaledSpectrogram(scaled)

            assertEquals(reference.size, spectrogram.size)
            for (t in reference.indices) {
                for (m in reference[t].indices) {
                    // Not bit-exact: the peak-norm epsilon (LOUDNESS_EPSILON) does not scale with
                    // gain, so a sub-percent difference remains — the same approximation Python uses.
                    assertEquals(
                        "gain=$gain frame=$t bin=$m",
                        reference[t][m], spectrogram[t][m], 5e-3f,
                    )
                }
            }
        }
    }

    @Test
    fun loudnessScalar_tracksGainInLogDomain() {
        val base = SyntheticAudioFixtureFactory.sineWave(
            sampleRateHz = 16_000, frequencyHz = 1_200f, durationMs = 500, amplitude = 0.8f,
        )
        val gain = 0.25f
        val attenuated = FloatArray(base.size) { base[it] * gain }

        val loudBase = extractor.computeLoudness(base)
        val loudAtten = extractor.computeLoudness(attenuated)

        // log(rms*gain) - log(rms) == log(gain), so the loudness scalar drops by ln(gain).
        assertTrue("loudness should decrease with attenuation", loudAtten < loudBase)
        assertEquals(ln(gain.toDouble()).toFloat(), loudAtten - loudBase, 1e-2f)
    }

    @Test
    fun scaledSpectrogram_valuesAreWithinUnitRange() {
        val signal = SyntheticAudioFixtureFactory.sineWave(
            sampleRateHz = 16_000, frequencyHz = 900f, durationMs = 500, amplitude = 0.3f,
        )
        val spectrogram = extractor.extractScaledSpectrogram(signal)
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        for (frame in spectrogram) {
            for (value in frame) {
                if (value < min) min = value
                if (value > max) max = value
            }
        }
        assertTrue("min-max output must be >= 0", min >= -1e-4f)
        assertTrue("min-max output must be <= 1", max <= 1f + 1e-4f)
        assertTrue("min should hit ~0", abs(min) < 1e-3f)
        assertTrue("max should hit ~1", abs(max - 1f) < 1e-3f)
    }
}

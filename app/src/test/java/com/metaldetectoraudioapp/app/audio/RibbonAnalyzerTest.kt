package com.metaldetectoraudioapp.app.audio

import com.metaldetectoraudioapp.app.audio.pipeline.AndroidMelSpectrogramFeatureExtractor
import com.metaldetectoraudioapp.app.audio.ribbon.RibbonAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.random.Random
import org.junit.Test

/**
 * Behavioural tests for the ribbon DSP. They assert *ordering* (clean tone > noise, etc.),
 * which is robust to constant tuning, rather than exact magnitudes.
 */
class RibbonAnalyzerTest {

    private val mel = AndroidMelSpectrogramFeatureExtractor()
    private val window = 8_000

    private fun whiteNoise(n: Int, amplitude: Float, seed: Int): FloatArray {
        val rng = Random(seed)
        return FloatArray(n) { (rng.nextFloat() * 2f - 1f) * amplitude }
    }

    /** Feeds [windowSignal] through the analyzer [repeats] times (consecutive identical windows). */
    private fun analyze(windowSignal: FloatArray, repeats: Int = 3): RibbonAnalyzer {
        val analyzer = RibbonAnalyzer()
        val logMel = mel.extractLogMelSpectrogram(windowSignal.copyOf(window))
        repeat(repeats) { analyzer.process(logMel) }
        return analyzer
    }

    private fun maxPeakQuality(a: RibbonAnalyzer): Float {
        val wc = a.writeCounter
        var maxQ = 0f
        var g = maxOf(0L, wc - RibbonAnalyzer.NEW_COLS)
        while (g < wc) {
            for (k in 0 until RibbonAnalyzer.MAX_PEAKS) {
                if (a.peakBinFrac(g, k) >= 0f) maxQ = maxOf(maxQ, a.peakQuality(g, k))
            }
            g++
        }
        return maxQ
    }

    private fun avgHaze(a: RibbonAnalyzer): Float {
        val wc = a.writeCounter
        if (wc == 0L) return 0f
        var sum = 0f
        var cnt = 0
        var g = maxOf(0L, wc - RibbonAnalyzer.NEW_COLS)
        while (g < wc) {
            for (h in 0 until RibbonAnalyzer.HAZE_BINS) { sum += a.haze(g, h); cnt++ }
            g++
        }
        return if (cnt > 0) sum / cnt else 0f
    }

    @Test
    fun process_appendsNewColumnsPerWindow() {
        val analyzer = analyze(
            SyntheticAudioFixtureFactory.sineWave(16_000, 700f, durationMs = 600, amplitude = 0.6f),
            repeats = 1,
        )
        assertEquals(RibbonAnalyzer.NEW_COLS.toLong(), analyzer.writeCounter)
    }

    @Test
    fun cleanTone_producesHighQualityRibbon() {
        val tone = SyntheticAudioFixtureFactory.sineWave(16_000, 700f, durationMs = 600, amplitude = 0.6f)
        val analyzer = analyze(tone)
        val q = maxPeakQuality(analyzer)
        assertTrue("clean tone should yield a strong ribbon, got quality=$q", q > 0.4f)
    }

    @Test
    fun broadbandNoise_producesLowerQualityThanTone() {
        val toneQ = maxPeakQuality(
            analyze(SyntheticAudioFixtureFactory.sineWave(16_000, 700f, durationMs = 600, amplitude = 0.6f))
        )
        val noiseQ = maxPeakQuality(analyze(whiteNoise(window, amplitude = 0.6f, seed = 7)))
        assertTrue(
            "tone quality ($toneQ) should clearly exceed noise quality ($noiseQ)",
            toneQ > noiseQ + 0.15f,
        )
    }

    @Test
    fun noise_producesVisibleHaze_silenceDoesNot() {
        val noiseHaze = avgHaze(analyze(whiteNoise(window, amplitude = 0.6f, seed = 11)))
        val silenceHaze = avgHaze(analyze(FloatArray(window)))
        assertTrue("noise haze ($noiseHaze) should exceed silence haze ($silenceHaze)", noiseHaze > silenceHaze)
        assertTrue("silence should be nearly dark, got $silenceHaze", silenceHaze < 0.1f)
    }

    @Test
    fun mixedToneAndNoise_keepsTonePeakAboveNoiseFloor() {
        val tone = SyntheticAudioFixtureFactory.sineWave(16_000, 700f, durationMs = 600, amplitude = 0.6f)
        val noise = whiteNoise(window, amplitude = 0.25f, seed = 3)
        val mixed = FloatArray(window) { tone[it] + noise[it] }
        val analyzer = analyze(mixed)
        assertTrue("a tone embedded in noise should still raise a ribbon", maxPeakQuality(analyzer) > 0.3f)
        assertTrue("mixed signal should also show haze", avgHaze(analyzer) > 0f)
    }
}

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

    private fun maxRecentBand(a: RibbonAnalyzer): Float {
        val wc = a.writeCounter
        var maxBand = 0f
        var g = maxOf(0L, wc - RibbonAnalyzer.NEW_COLS)
        while (g < wc) {
            for (k in 0 until RibbonAnalyzer.MAX_PEAKS) {
                val binFrac = a.peakBinFrac(g, k)
                if (binFrac >= 0f) {
                    val band = (binFrac / (RibbonAnalyzer.BAND_HI_BIN.toFloat() / RibbonAnalyzer.MEL_BINS))
                        .coerceIn(0f, 1f)
                    if (band > maxBand) maxBand = band
                }
            }
            g++
        }
        return maxBand
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

    @Test
    fun noise_isMoreHazeDominantThanRibbonDominant() {
        val analyzer = analyze(whiteNoise(window, amplitude = 0.6f, seed = 17), repeats = 5)
        val haze = avgHaze(analyzer)
        val quality = maxPeakQuality(analyzer)
        assertTrue("noise should read more as diffuse haze than clean ribbon, haze=$haze quality=$quality", haze > quality)
    }

    @Test
    fun pitchJump_updatesRecentRibbonPitch() {
        val low = SyntheticAudioFixtureFactory.sineWave(16_000, 350f, durationMs = 300, amplitude = 0.55f)
        val high = SyntheticAudioFixtureFactory.sineWave(16_000, 1_600f, durationMs = 300, amplitude = 0.55f)
        val signal = FloatArray(window)
        for (i in 0 until window / 2) signal[i] = low[i]
        for (i in window / 2 until window) signal[i] = high[i - window / 2]

        val analyzer = analyze(signal, repeats = 2)

        assertTrue("recent columns should reflect the high-pitch half of the signal", maxRecentBand(analyzer) > 0.55f)
    }

    @Test
    fun realFixtures_doNotSaturateHaze() {
        val junk = RealWavRibbonFixture.stats(RealWavRibbonFixture.analyzeFixture("18_wig_smallnail.wav"))
        val dime = RealWavRibbonFixture.stats(RealWavRibbonFixture.analyzeFixture("8_wig_dime.wav"))

        assertTrue("junk haze should not saturate, stats=$junk", junk.hazeSaturationFraction < 0.08f)
        assertTrue("dime haze should not saturate, stats=$dime", dime.hazeSaturationFraction < 0.08f)
    }

    @Test
    fun realFixtures_dimeHasStrongerHighCleanRibbonThanSmallNail() {
        val junk = RealWavRibbonFixture.stats(RealWavRibbonFixture.analyzeFixture("18_wig_smallnail.wav"))
        val dime = RealWavRibbonFixture.stats(RealWavRibbonFixture.analyzeFixture("8_wig_dime.wav"))

        assertTrue(
            "dime should have stronger high clean ribbon than small nail, dime=$dime junk=$junk",
            dime.highCleanRibbonScore > junk.highCleanRibbonScore + 0.03f,
        )
    }

    @Test
    fun realFixtures_smallNailHasMoreDiffuseLowMidHazeThanDime() {
        val junk = RealWavRibbonFixture.stats(RealWavRibbonFixture.analyzeFixture("18_wig_smallnail.wav"))
        val dime = RealWavRibbonFixture.stats(RealWavRibbonFixture.analyzeFixture("8_wig_dime.wav"))

        assertTrue(
            "small nail should show more low-mid diffuse haze than dime, junk=$junk dime=$dime",
            junk.diffuseLowMidHazeScore > dime.diffuseLowMidHazeScore + 0.01f,
        )
    }
}

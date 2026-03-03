package com.metaldetectoraudioapp.app.audio

import com.metaldetectoraudioapp.app.audio.pipeline.SlidingAudioFramer
import com.metaldetectoraudioapp.app.audio.pipeline.SpectrogramFeatureExtractor
import org.junit.Assert.assertEquals
import org.junit.Test

class SpectrogramFeatureExtractorParityTest {
    @Test
    fun extractLogMagnitudeSpectrogram_streamFramesMatchDirectSlices() {
        val signal = SyntheticAudioFixtureFactory.sineWave(
            sampleRateHz = 16_000,
            frequencyHz = 700f,
            durationMs = 180,
            amplitude = 0.8f
        )

        val framer = SlidingAudioFramer(frameSizeSamples = 1024, hopSizeSamples = 512)
        val extractor = SpectrogramFeatureExtractor(fftSize = 256, hopSize = 128, binsToKeep = 32)

        val streamFrames = mutableListOf<FloatArray>()
        for (offset in signal.indices step 200) {
            val end = minOf(offset + 200, signal.size)
            val chunk = signal.copyOfRange(offset, end)
            streamFrames += framer.push(chunk)
        }

        assertEquals(4, streamFrames.size)

        streamFrames.forEachIndexed { index, frame ->
            val start = index * 512
            val expectedFrame = signal.copyOfRange(start, start + 1024)
            val expectedSpec = extractor.extractLogMagnitudeSpectrogram(expectedFrame)
            val actualSpec = extractor.extractLogMagnitudeSpectrogram(frame)
            assertSpectrogramClose(expectedSpec, actualSpec)
        }
    }

    private fun assertSpectrogramClose(expected: Array<FloatArray>, actual: Array<FloatArray>) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { rowIndex ->
            assertEquals(expected[rowIndex].size, actual[rowIndex].size)
            expected[rowIndex].indices.forEach { colIndex ->
                assertEquals(expected[rowIndex][colIndex], actual[rowIndex][colIndex], 1e-4f)
            }
        }
    }
}

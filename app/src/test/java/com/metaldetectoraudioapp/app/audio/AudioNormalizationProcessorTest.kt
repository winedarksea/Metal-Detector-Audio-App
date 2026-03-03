package com.metaldetectoraudioapp.app.audio

import com.metaldetectoraudioapp.app.audio.pipeline.AudioNormalizationProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioNormalizationProcessorTest {
    @Test
    fun normalizeInPlace_scalesPeakToOne() {
        val samples = floatArrayOf(0.2f, -0.5f, 0.75f)
        val processor = AudioNormalizationProcessor()

        processor.normalizeInPlace(samples)

        val peak = samples.maxOf { abs(it) }
        assertEquals(1f, peak, 1e-4f)
        assertTrue(samples.all { it in -1f..1f })
    }

    @Test
    fun normalizeInPlace_silentInputRemainsSilent() {
        val samples = FloatArray(8)
        val processor = AudioNormalizationProcessor()

        val scale = processor.normalizeInPlace(samples)

        assertEquals(1f, scale, 1e-6f)
        assertTrue(samples.all { it == 0f })
    }
}

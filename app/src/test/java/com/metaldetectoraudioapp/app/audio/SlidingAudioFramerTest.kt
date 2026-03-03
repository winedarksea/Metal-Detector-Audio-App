package com.metaldetectoraudioapp.app.audio

import com.metaldetectoraudioapp.app.audio.pipeline.SlidingAudioFramer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SlidingAudioFramerTest {
    @Test
    fun push_generatesOverlappingFramesAtHopIntervals() {
        val framer = SlidingAudioFramer(frameSizeSamples = 4, hopSizeSamples = 2)

        val firstBatch = framer.push(floatArrayOf(1f, 2f, 3f, 4f, 5f))
        assertEquals(1, firstBatch.size)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), firstBatch.first(), 1e-6f)

        val secondBatch = framer.push(floatArrayOf(6f, 7f))
        assertEquals(1, secondBatch.size)
        assertArrayEquals(floatArrayOf(3f, 4f, 5f, 6f), secondBatch[0], 1e-6f)
    }
}

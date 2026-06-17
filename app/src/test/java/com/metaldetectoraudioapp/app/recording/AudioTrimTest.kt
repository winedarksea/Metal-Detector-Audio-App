package com.metaldetectoraudioapp.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the shared trim logic via the byte-identical `app/` copy (kept in sync by
 * [AudioTrimSyncTest]). The `shared/` module has no test source set, so this is the home for the
 * unit coverage.
 */
class AudioTrimTest {

    private val rate = 16_000

    private fun wav(samples: ShortArray) = WavCodec.encodePcm16(samples, rate, channels = 1)

    @Test
    fun trimWav_keepsOnlySelectedRange() {
        val trimmed = AudioTrim.trimWav(wav(ShortArray(rate) { 1000 }), startMs = 200, endMs = 700)
        val decoded = WavCodec.decodePcm16(trimmed)
        assertEquals(rate, decoded.sampleRateHz)
        assertEquals(1, decoded.channels)
        assertEquals(8000, decoded.samples.size) // (700-200) ms * 16 samples/ms
        assertEquals(500L, decoded.durationMs)
    }

    @Test
    fun trimWav_fullRangeReturnsOriginalBytes() {
        val original = wav(ShortArray(rate) { 500 })
        assertTrue(AudioTrim.trimWav(original, 0, 1000).contentEquals(original))
    }

    @Test
    fun trimWav_emptyOrInvertedRangeReturnsOriginalBytes() {
        val original = wav(ShortArray(rate) { 500 })
        assertTrue(AudioTrim.trimWav(original, 300, 300).contentEquals(original))
        assertTrue(AudioTrim.trimWav(original, 700, 200).contentEquals(original))
    }

    @Test
    fun trimWav_clampsEndBeyondClip() {
        val trimmed = AudioTrim.trimWav(wav(ShortArray(rate) { 500 }), startMs = 500, endMs = 5_000)
        assertEquals(500L, WavCodec.decodePcm16(trimmed).durationMs)
    }

    @Test
    fun clipEnvelope_isBucketedAndNormalized() {
        val samples = ShortArray(rate) { i -> if (i < rate / 2) 0 else 10_000 }
        val envelope = AudioTrim.clipEnvelope(wav(samples), bucketCount = 10)
        assertEquals(10, envelope.size)
        assertTrue(envelope.all { it in 0f..1f })
        assertEquals(0f, envelope.first(), 1e-6f) // silent leading bucket
        assertEquals(1f, envelope.last(), 1e-6f)  // loudest sample normalizes to 1.0
    }

    @Test
    fun durationMs_matchesDecodedDuration() {
        assertEquals(500L, AudioTrim.durationMs(wav(ShortArray(rate / 2) { 100 })))
    }
}

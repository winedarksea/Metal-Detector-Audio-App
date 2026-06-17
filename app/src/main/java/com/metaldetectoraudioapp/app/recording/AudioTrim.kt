package com.metaldetectoraudioapp.app.recording

import kotlin.math.abs

/**
 * Pure-Kotlin helpers for the recording-screen trim UI: cut a PCM16 WAV down to a sub-range and
 * compute a static amplitude envelope for the waveform. Built on [WavCodec], so it compiles for
 * every target.
 *
 * IMPORTANT: duplicated verbatim in the Android `app/` module and `shared/` (app/ does not depend
 * on :shared). Keep both copies byte-identical; `AudioTrimSyncTest` enforces it.
 */
// === AUDIO-TRIM-SYNC START ===
object AudioTrim {

    /** Default number of waveform buckets used by the trim visual. */
    const val DEFAULT_ENVELOPE_BUCKETS: Int = 100

    /**
     * Return a new PCM16 WAV containing only the audio in `[startMs, endMs)`. The range is clamped
     * to the clip bounds. If the resulting range is the whole clip, empty, or invalid, the original
     * bytes are returned unchanged.
     */
    fun trimWav(wavBytes: ByteArray, startMs: Long, endMs: Long): ByteArray {
        val decoded = WavCodec.decodePcm16(wavBytes)
        val rate = decoded.sampleRateHz
        val channels = decoded.channels
        if (rate <= 0 || channels <= 0) return wavBytes

        val totalFrames = decoded.samples.size / channels
        val startFrame = msToFrame(startMs, rate).coerceIn(0, totalFrames)
        val endFrame = msToFrame(endMs, rate).coerceIn(startFrame, totalFrames)
        if (endFrame <= startFrame) return wavBytes
        if (startFrame == 0 && endFrame == totalFrames) return wavBytes

        val trimmed = decoded.samples.copyOfRange(startFrame * channels, endFrame * channels)
        return WavCodec.encodePcm16(trimmed, rate, channels)
    }

    /** Exact duration in ms of the clip encoded in [wavBytes]. */
    fun durationMs(wavBytes: ByteArray): Long = WavCodec.decodePcm16(wavBytes).durationMs

    /**
     * Downsample [wavBytes] into [bucketCount] amplitude values in 0..1 (peak per bucket, normalized
     * to the clip's loudest sample) for a static waveform. Uses channel 0 for multichannel input.
     */
    fun clipEnvelope(wavBytes: ByteArray, bucketCount: Int = DEFAULT_ENVELOPE_BUCKETS): List<Float> {
        if (bucketCount <= 0) return emptyList()
        val decoded = WavCodec.decodePcm16(wavBytes)
        val channels = decoded.channels.coerceAtLeast(1)
        val frames = decoded.samples.size / channels
        if (frames <= 0) return emptyList()

        val bucketMax = IntArray(bucketCount)
        var globalMax = 1
        for (f in 0 until frames) {
            val amplitude = abs(decoded.samples[f * channels].toInt())
            val bucket = (f.toLong() * bucketCount / frames).toInt().coerceIn(0, bucketCount - 1)
            if (amplitude > bucketMax[bucket]) bucketMax[bucket] = amplitude
            if (amplitude > globalMax) globalMax = amplitude
        }

        val norm = globalMax.toFloat()
        return List(bucketCount) { (bucketMax[it] / norm).coerceIn(0f, 1f) }
    }

    private fun msToFrame(ms: Long, rate: Int): Int =
        ((ms.coerceAtLeast(0L) * rate) / 1000L).toInt()
}
// === AUDIO-TRIM-SYNC END ===

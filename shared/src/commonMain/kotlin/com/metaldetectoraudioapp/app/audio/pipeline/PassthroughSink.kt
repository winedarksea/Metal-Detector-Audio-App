package com.metaldetectoraudioapp.app.audio.pipeline

/**
 * Platform-agnostic sink for audio passthrough (speaker monitoring).
 *
 * Android implements this with [AudioTrack], Desktop with
 * [javax.sound.sampled.SourceDataLine].
 */
interface PassthroughSink {
    fun setEnabled(enabled: Boolean)
    fun writePcm16(samples: ShortArray, count: Int)
    fun release()
}

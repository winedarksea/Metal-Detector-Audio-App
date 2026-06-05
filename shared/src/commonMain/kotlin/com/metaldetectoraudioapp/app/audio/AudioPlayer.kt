package com.metaldetectoraudioapp.app.audio

/**
 * Plays a PCM WAV clip in memory. Suspends until playback completes or [stop] is called.
 *
 * Desktop: javax.sound.sampled Clip.  Web: Audio element fed a Blob URL.
 */
interface AudioPlayer {
    suspend fun play(wavBytes: ByteArray)
    fun stop()
}

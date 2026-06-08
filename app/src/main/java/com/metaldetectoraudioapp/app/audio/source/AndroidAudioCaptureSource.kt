package com.metaldetectoraudioapp.app.audio.source

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder

/**
 * Detector tones should bypass voice-oriented gain and noise processing when the
 * device advertises Android's unprocessed capture path.
 */
fun preferredDetectorAudioSource(context: Context): Int {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val unprocessedCaptureSupported =
        audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            ?.toBooleanStrictOrNull() == true
    return if (unprocessedCaptureSupported) {
        MediaRecorder.AudioSource.UNPROCESSED
    } else {
        MediaRecorder.AudioSource.MIC
    }
}

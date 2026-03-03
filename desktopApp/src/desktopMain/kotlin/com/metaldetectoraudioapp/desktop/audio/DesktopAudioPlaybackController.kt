package com.metaldetectoraudioapp.desktop.audio

import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

/**
 * Lightweight WAV playback helper for recorded sample preview/review.
 */
class DesktopAudioPlaybackController {
    @Volatile
    private var clip: Clip? = null

    fun play(file: File, onComplete: () -> Unit = {}) {
        stop()

        runCatching {
            val inputStream = AudioSystem.getAudioInputStream(file)
            val newClip = AudioSystem.getClip()
            newClip.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP) {
                    runCatching { newClip.close() }
                    if (clip === newClip) {
                        clip = null
                    }
                    onComplete()
                }
            }
            newClip.open(inputStream)
            clip = newClip
            newClip.start()
        }
    }

    fun stop() {
        clip?.let {
            runCatching { it.stop() }
            runCatching { it.close() }
        }
        clip = null
    }
}

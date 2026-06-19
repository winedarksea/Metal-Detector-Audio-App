package com.metaldetectoraudioapp.app.recording

import android.media.AudioDeviceInfo
import android.media.MediaPlayer
import java.io.File

class AudioPlaybackController {
    private var mediaPlayer: MediaPlayer? = null

    fun play(file: File, preferredOutputDevice: AudioDeviceInfo?, onCompletion: () -> Unit) {
        stop()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            if (preferredOutputDevice != null) {
                preferredDevice = preferredOutputDevice
            }
            setOnCompletionListener {
                stop()
                onCompletion()
            }
            prepare()
            start()
        }
    }

    fun stop() {
        mediaPlayer?.run {
            if (isPlaying) {
                stop()
            }
            release()
        }
        mediaPlayer = null
    }
}

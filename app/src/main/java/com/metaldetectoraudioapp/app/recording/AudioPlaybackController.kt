package com.metaldetectoraudioapp.app.recording

import android.media.MediaPlayer
import java.io.File

class AudioPlaybackController {
    private var mediaPlayer: MediaPlayer? = null

    fun play(file: File, onCompletion: () -> Unit) {
        stop()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
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

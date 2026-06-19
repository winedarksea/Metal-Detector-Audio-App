package com.metaldetectoraudioapp.app.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineEvent
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume

/** Desktop [AudioPlayer] using `javax.sound.sampled.Clip`. Plays an in-memory PCM WAV. */
class DesktopAudioPlayer : AudioPlayer {

    @Volatile
    private var activeClip: Clip? = null

    override suspend fun play(wavBytes: ByteArray) {
        play(wavBytes, outputDevice = null)
    }

    suspend fun play(wavBytes: ByteArray, outputDevice: DesktopAudioDevice?) {
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                val audioInputStream = runCatching {
                    AudioSystem.getAudioInputStream(ByteArrayInputStream(wavBytes))
                }.getOrElse {
                    cont.resume(Unit)
                    return@suspendCancellableCoroutine
                }
                val clip = runCatching {
                    if (outputDevice == null) {
                        AudioSystem.getClip()
                    } else {
                        val info = DataLine.Info(Clip::class.java, audioInputStream.format)
                        AudioSystem.getMixer(outputDevice.mixerInfo).getLine(info) as Clip
                    }
                }.getOrElse {
                    cont.resume(Unit)
                    return@suspendCancellableCoroutine
                }
                activeClip = clip

                clip.addLineListener { event ->
                    if (event.type == LineEvent.Type.STOP) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                }

                runCatching {
                    clip.open(audioInputStream)
                    clip.start()
                }.onFailure {
                    clip.close()
                    activeClip = null
                    if (cont.isActive) cont.resume(Unit)
                }

                cont.invokeOnCancellation {
                    clip.stop()
                    clip.close()
                    activeClip = null
                }
            }
            activeClip?.close()
            activeClip = null
        }
    }

    override fun stop() {
        activeClip?.stop()
    }
}

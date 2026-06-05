package com.metaldetectoraudioapp.app.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume

/** Desktop [AudioPlayer] using `javax.sound.sampled.Clip`. Plays an in-memory PCM WAV. */
class DesktopAudioPlayer : AudioPlayer {

    @Volatile
    private var activeClip: Clip? = null

    override suspend fun play(wavBytes: ByteArray) {
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                val clip = runCatching { AudioSystem.getClip() }.getOrElse {
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
                    clip.open(AudioSystem.getAudioInputStream(ByteArrayInputStream(wavBytes)))
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

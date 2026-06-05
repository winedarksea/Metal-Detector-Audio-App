package com.metaldetectoraudioapp.web.audio

import com.metaldetectoraudioapp.app.audio.AudioPlayer
import com.metaldetectoraudioapp.web.platform.createBlobUrl
import com.metaldetectoraudioapp.web.platform.revokeObjectUrl
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class WebAudioPlayer : AudioPlayer {
    private var currentBlobUrl: String? = null

    override suspend fun play(wavBytes: ByteArray) {
        stop()
        val url = createBlobUrl(wavBytes, "audio/wav")
        currentBlobUrl = url

        suspendCancellableCoroutine<Unit> { cont ->
            playAudioElement(url) {
                revokeObjectUrl(url)
                currentBlobUrl = null
                if (cont.isActive) cont.resume(Unit)
            }
            cont.invokeOnCancellation { stopAudioElement() }
        }
    }

    override fun stop() {
        stopAudioElement()
        currentBlobUrl?.let { revokeObjectUrl(it) }
        currentBlobUrl = null
    }
}

private fun playAudioElement(url: String, onEnded: () -> Unit) {
    js("""
        if (window.__mdAudioEl) { window.__mdAudioEl.pause(); }
        var audio = new Audio(url);
        window.__mdAudioEl = audio;
        audio.onended = function() { onEnded(); };
        audio.onerror = function() { onEnded(); };
        audio.play().catch(function() { onEnded(); });
    """)
}

private fun stopAudioElement() {
    js("""
        if (window.__mdAudioEl) {
            window.__mdAudioEl.pause();
            window.__mdAudioEl = null;
        }
    """)
}

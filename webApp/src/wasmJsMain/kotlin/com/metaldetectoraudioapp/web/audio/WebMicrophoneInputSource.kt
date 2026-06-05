package com.metaldetectoraudioapp.web.audio

import com.metaldetectoraudioapp.app.audio.source.AudioInputSource

/**
 * AudioInputSource backed by the Web Audio API.
 *
 * JS interop restriction: Kotlin classes can't be passed as JS function parameters.
 * Instead, the active instance is stored in [globalInfSource]. JS callbacks invoke
 * [onInfChunkGlobal] (a top-level function with only Int params) which delegates to the
 * stored instance. Audio float data is transferred via `window.__mdInfBuf` (global JS array).
 */
class WebMicrophoneInputSource(
    override val sampleRateHz: Int = 16_000,
) : AudioInputSource {

    private val ringBuffer = mutableListOf<Float>()
    private var ctxSampleRate: Int = 44_100
    private var started = false

    internal fun onChunk(ctxRate: Int, count: Int) {
        ctxSampleRate = ctxRate
        for (i in 0 until count) ringBuffer.add(readGlobalInfBufAt(i))
    }

    override fun start() {
        if (started) return
        started = true
        globalInfSource = this
        startInfMicJs { ctxRate, count -> onInfChunkGlobal(ctxRate, count) }
    }

    override fun readPcm16(targetBuffer: ShortArray): Int {
        if (ringBuffer.isEmpty()) return 0
        val src = ringBuffer.toFloatArray()
        ringBuffer.clear()
        val resampled = resampleLinear(src, ctxSampleRate, sampleRateHz)
        val count = minOf(resampled.size, targetBuffer.size)
        for (i in 0 until count) {
            targetBuffer[i] = (resampled[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        return count
    }

    override fun stop() {
        started = false
        if (globalInfSource === this) globalInfSource = null
        stopInfMicJs()
        ringBuffer.clear()
    }

    override fun release() = stop()
}

// ── Global singleton ──────────────────────────────────────────────────────────

private var globalInfSource: WebMicrophoneInputSource? = null

/** Called from JS with only primitives (satisfies Kotlin/WASM interop rules). */
fun onInfChunkGlobal(ctxRate: Int, count: Int) {
    globalInfSource?.onChunk(ctxRate, count)
}

// ── JS bridge ─────────────────────────────────────────────────────────────────

private fun readGlobalInfBufAt(i: Int): Float = js("window.__mdInfBuf[i]")

private fun startInfMicJs(onChunk: (Int, Int) -> Unit) {
    js("""
        var __mic = window.__micDeviceId ? { deviceId: { exact: window.__micDeviceId } } : true;
        navigator.mediaDevices.getUserMedia({ audio: __mic, video: false }).then(function(stream) {
            var ctx = new (window.AudioContext || window.webkitAudioContext)();
            window.__mdInfCtx = ctx;
            window.__mdInfStream = stream;
            // iOS Safari starts the context suspended; resume it within this user gesture.
            if (ctx.state === 'suspended' && ctx.resume) ctx.resume();
            var src = ctx.createMediaStreamSource(stream);

            function sendChunk(channelData) {
                window.__mdInfBuf = channelData;
                onChunk(Math.round(ctx.sampleRate), channelData.length);
            }

            if (ctx.audioWorklet) {
                ctx.audioWorklet.addModule('/app/mic-worklet.js').then(function() {
                    var node = new AudioWorkletNode(ctx, 'mic-processor');
                    window.__mdInfNode = node;
                    node.port.onmessage = function(e) { sendChunk(e.data); };
                    src.connect(node);
                }).catch(function() { fallback(ctx, src, sendChunk); });
            } else { fallback(ctx, src, sendChunk); }

            function fallback(ctx, src, sendChunk) {
                var proc = ctx.createScriptProcessor(4096, 1, 1);
                window.__mdInfProc = proc;
                proc.onaudioprocess = function(e) { sendChunk(e.inputBuffer.getChannelData(0)); };
                src.connect(proc);
                proc.connect(ctx.destination);
            }
        }).catch(function(e) { console.error('Inference getUserMedia failed:', e); });
    """)
}

private fun stopInfMicJs() {
    js("""
        if (window.__mdInfNode)   { window.__mdInfNode.disconnect();   window.__mdInfNode = null; }
        if (window.__mdInfProc)   { window.__mdInfProc.disconnect();   window.__mdInfProc = null; }
        if (window.__mdInfStream) { window.__mdInfStream.getTracks().forEach(function(t) { t.stop(); }); window.__mdInfStream = null; }
        if (window.__mdInfCtx)    { window.__mdInfCtx.close();         window.__mdInfCtx = null; }
        window.__mdInfBuf = null;
    """)
}

private fun resampleLinear(src: FloatArray, fromHz: Int, toHz: Int): FloatArray {
    if (fromHz == toHz || src.isEmpty()) return src
    val ratio = fromHz.toDouble() / toHz
    val outLen = (src.size / ratio).toInt().coerceAtLeast(1)
    return FloatArray(outLen) { i ->
        val pos = i * ratio
        val lo = pos.toInt().coerceIn(0, src.lastIndex)
        val hi = (lo + 1).coerceIn(0, src.lastIndex)
        src[lo] * (1f - (pos - lo).toFloat()) + src[hi] * (pos - lo).toFloat()
    }
}

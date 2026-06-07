package com.metaldetectoraudioapp.web.audio

/**
 * Speaker pass-through ("monitor") control for the web Detect path, the web
 * counterpart of Android's `AudioPassthroughPlayer`.
 *
 * The inference capture graph ([WebMicrophoneInputSource]) builds a monitor branch
 * `src -> monitorGain -> ctx.destination` whose gain defaults to 0. This object just
 * flips that gain and chooses the output sink. State is mirrored into JS globals
 * (`window.__mdInfPassthroughOn`, `window.__mdInfSinkId`) so it survives a
 * start/stop cycle and is re-applied when the graph is rebuilt.
 */
object WebPassthroughMonitor {

    /** Enable/disable audible monitoring of the live mic input through the speaker. */
    fun setEnabled(enabled: Boolean) {
        setPassthroughEnabledJs(if (enabled) 1 else 0)
    }

    /** Choose the output device (sink). Empty string = system default. No-op if unsupported. */
    fun setOutputSink(deviceId: String) {
        setOutputSinkJs(deviceId)
    }
}

private fun setPassthroughEnabledJs(enabled: Int) {
    js("""
        window.__mdInfPassthroughOn = (enabled === 1);
        if (window.__mdInfMonitorGain) {
            window.__mdInfMonitorGain.gain.value = (enabled === 1) ? 1 : 0;
        }
    """)
}

private fun setOutputSinkJs(deviceId: String) {
    js("""
        window.__mdInfSinkId = deviceId;
        var ctx = window.__mdInfCtx;
        if (ctx && ctx.setSinkId) {
            ctx.setSinkId(deviceId || '').catch(function() {});
        }
    """)
}

package com.metaldetectoraudioapp.web.audio

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Microphone device enumeration + selection for the web app, mirroring the
 * Android `AudioDeviceManager` input-device picker.
 *
 * The chosen deviceId is stored in the JS global `window.__micDeviceId` (empty
 * string = system default). Both capture paths ([WebMicrophoneInputSource] for
 * inference and `WebRecordingViewModel` for recording) read it when building the
 * `getUserMedia` constraints, so a single selection governs the whole app.
 *
 * Device labels are only populated by the browser **after** microphone permission
 * has been granted at least once; before that `enumerateDevices()` returns blank
 * labels and we fall back to "Microphone N". [ensureMicPermission] is therefore
 * called before enumerating so the picker shows real system device names.
 */
data class MicDevice(val deviceId: String, val label: String)

/** The currently selected microphone deviceId, or "" for the system default. */
fun selectedMicDeviceId(): String = js("window.__micDeviceId || ''")

fun setSelectedMicDeviceId(deviceId: String): Unit = js("window.__micDeviceId = deviceId")

/**
 * Requests microphone permission via a throwaway `getUserMedia` call, immediately
 * stopping the tracks. This is what unlocks real device labels for
 * [enumerateDevices()]. Resolves true if granted, false otherwise. Safe to call
 * repeatedly — the browser only prompts the first time.
 */
suspend fun ensureMicPermission(): Boolean =
    suspendCancellableCoroutine { cont ->
        requestMicPermission(onResult = { granted -> if (cont.isActive) cont.resume(granted == 1) })
    }

/**
 * Enumerates audio **input** devices via `navigator.mediaDevices.enumerateDevices()`,
 * requesting permission first so labels are populated. Results are cached in
 * `window.__micDevices`; we read them back through primitive/String-returning
 * helpers to satisfy the Kotlin/Wasm interop rules. Returns an empty list if
 * enumeration is unsupported or fails.
 */
suspend fun listMicDevices(): List<MicDevice> {
    ensureMicPermission()
    return suspendCancellableCoroutine { cont ->
        enumerateDevicesToGlobal(
            kind = "audioinput",
            global = "__micDevices",
            fallbackPrefix = "Microphone",
            onSuccess = { count ->
                if (cont.isActive) cont.resume(readDevicesFromGlobal("__micDevices", count))
            },
            onError = { if (cont.isActive) cont.resume(emptyList()) }
        )
    }
}

/**
 * Enumerates audio **output** devices (speaker / headphones / USB sinks) for the
 * pass-through output picker. Like [listMicDevices] but filters `audiooutput`.
 */
suspend fun listOutputDevices(): List<MicDevice> {
    ensureMicPermission()
    return suspendCancellableCoroutine { cont ->
        enumerateDevicesToGlobal(
            kind = "audiooutput",
            global = "__outDevices",
            fallbackPrefix = "Output",
            onSuccess = { count ->
                if (cont.isActive) cont.resume(readDevicesFromGlobal("__outDevices", count))
            },
            onError = { if (cont.isActive) cont.resume(emptyList()) }
        )
    }
}

/** Registers a `devicechange` listener so the picker can auto-refresh on hot-plug. */
fun registerDeviceChangeListener(onChange: () -> Unit) {
    addDeviceChangeListener(onChange)
}

/** True if the browser supports choosing an output sink (`AudioContext.setSinkId`). */
fun outputSelectionSupported(): Boolean = outputSinkSupportedJs() == 1

private fun outputSinkSupportedJs(): Int =
    js("(typeof AudioContext !== 'undefined' && 'setSinkId' in AudioContext.prototype) ? 1 : 0")

// ── JS bridge ───────────────────────────────────────────────────────────────

private fun readDevicesFromGlobal(global: String, count: Int): List<MicDevice> {
    val out = ArrayList<MicDevice>(count)
    for (i in 0 until count) {
        out.add(MicDevice(deviceIdAt(global, i), labelAt(global, i)))
    }
    return out
}

private fun deviceIdAt(global: String, i: Int): String = js("window[global][i].deviceId")
private fun labelAt(global: String, i: Int): String = js("window[global][i].label")

private fun requestMicPermission(onResult: (Int) -> Unit) {
    js("""
        if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) { onResult(0); return; }
        navigator.mediaDevices.getUserMedia({ audio: true, video: false }).then(function(stream) {
            stream.getTracks().forEach(function(t) { t.stop(); });
            onResult(1);
        }).catch(function(e) { onResult(0); });
    """)
}

private fun enumerateDevicesToGlobal(
    kind: String,
    global: String,
    fallbackPrefix: String,
    onSuccess: (Int) -> Unit,
    onError: () -> Unit
) {
    js("""
        if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) { onError(); return; }
        navigator.mediaDevices.enumerateDevices().then(function(devices) {
            window[global] = devices
                .filter(function(d) { return d.kind === kind; })
                .map(function(d, idx) {
                    return { deviceId: d.deviceId, label: d.label || (fallbackPrefix + ' ' + (idx + 1)) };
                });
            onSuccess(window[global].length);
        }).catch(function(e) { onError(); });
    """)
}

private fun addDeviceChangeListener(onChange: () -> Unit) {
    js("""
        if (navigator.mediaDevices && navigator.mediaDevices.addEventListener) {
            navigator.mediaDevices.addEventListener('devicechange', function() { onChange(); });
        }
    """)
}

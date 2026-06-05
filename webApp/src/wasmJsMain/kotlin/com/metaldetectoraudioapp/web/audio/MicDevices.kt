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
 * Device labels are only populated by the browser after microphone permission has
 * been granted at least once; before that they fall back to "Microphone N".
 */
data class MicDevice(val deviceId: String, val label: String)

/** The currently selected microphone deviceId, or "" for the system default. */
fun selectedMicDeviceId(): String = js("window.__micDeviceId || ''")

fun setSelectedMicDeviceId(deviceId: String): Unit = js("window.__micDeviceId = deviceId")

/**
 * Enumerates audio input devices via `navigator.mediaDevices.enumerateDevices()`.
 * Results are cached in `window.__micDevices`; we read them back through
 * primitive/String-returning helpers to satisfy the Kotlin/Wasm interop rules.
 * Returns an empty list if enumeration is unsupported or fails.
 */
suspend fun listMicDevices(): List<MicDevice> =
    suspendCancellableCoroutine { cont ->
        enumerateMicsToGlobal(
            onSuccess = { count ->
                if (cont.isActive) {
                    val out = ArrayList<MicDevice>(count)
                    for (i in 0 until count) {
                        out.add(MicDevice(micDeviceIdAt(i), micLabelAt(i)))
                    }
                    cont.resume(out)
                }
            },
            onError = { if (cont.isActive) cont.resume(emptyList()) }
        )
    }

private fun micDeviceIdAt(i: Int): String = js("window.__micDevices[i].deviceId")
private fun micLabelAt(i: Int): String = js("window.__micDevices[i].label")

private fun enumerateMicsToGlobal(onSuccess: (Int) -> Unit, onError: () -> Unit) {
    js("""
        if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) { onError(); return; }
        navigator.mediaDevices.enumerateDevices().then(function(devices) {
            window.__micDevices = devices
                .filter(function(d) { return d.kind === 'audioinput'; })
                .map(function(d, idx) {
                    return { deviceId: d.deviceId, label: d.label || ('Microphone ' + (idx + 1)) };
                });
            onSuccess(window.__micDevices.length);
        }).catch(function(e) { onError(); });
    """)
}

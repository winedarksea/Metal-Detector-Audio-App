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

data class AudioDeviceSnapshot(
    val inputDevices: List<MicDevice>,
    val outputDevices: List<MicDevice>,
)

/** The currently selected microphone deviceId, or "" for the system default. */
fun selectedMicDeviceId(): String = js("window.__micDeviceId || ''")

fun setSelectedMicDeviceId(deviceId: String): Unit = js("window.__micDeviceId = deviceId")

fun selectedOutputDeviceId(): String = js("window.__mdInfSinkId || ''")

fun selectedPreviewOutputDeviceId(): String = js("window.__mdPreviewSinkId || ''")

fun setSelectedPreviewOutputDeviceId(deviceId: String): Unit =
    js("window.__mdPreviewSinkId = deviceId")

/**
 * Requests microphone permission via a throwaway `getUserMedia` call, immediately
 * stopping the tracks. Calls are coalesced because the app shell and source
 * selector can mount together; concurrent requests can produce duplicate prompts
 * in installed mobile PWAs.
 */
suspend fun ensureMicPermission(): Boolean {
    when (microphonePermissionRequestState) {
        MicrophonePermissionRequestState.GRANTED -> return true
        MicrophonePermissionRequestState.DENIED -> return false
        else -> Unit
    }

    return suspendCancellableCoroutine { continuation ->
        microphonePermissionWaiters += { granted ->
            if (continuation.isActive) continuation.resume(granted)
        }
        if (microphonePermissionRequestState == MicrophonePermissionRequestState.UNKNOWN) {
            microphonePermissionRequestState = MicrophonePermissionRequestState.REQUESTING
            requestMicPermission(::completeMicrophonePermissionRequest)
        }
    }
}

/**
 * Takes one input/output snapshot so a hot-plug refresh cannot combine results
 * from two different calls to `enumerateDevices()`.
 *
 * [useActiveStream] is a best-effort aid for the explicit Refresh action: some Android builds only
 * expose the full input list (e.g. a freshly plugged-in USB mic) while a capture stream is live, so
 * we briefly hold a default stream open across `enumerateDevices()`. It may not overcome the
 * platform limitation, and is opt-in so automatic/hot-plug refreshes don't activate the mic.
 */
suspend fun listAudioDevices(useActiveStream: Boolean = false): AudioDeviceSnapshot {
    ensureMicPermission()
    return suspendCancellableCoroutine { cont ->
        enumerateAudioDevicesToGlobals(
            useActiveStream = if (useActiveStream) 1 else 0,
            onSuccess = { inputCount, outputCount ->
                if (cont.isActive) {
                    cont.resume(
                        AudioDeviceSnapshot(
                            inputDevices = readDevicesFromGlobal("__micDevices", inputCount),
                            outputDevices = readDevicesFromGlobal("__outDevices", outputCount),
                        )
                    )
                }
            },
            onError = {
                if (cont.isActive) {
                    cont.resume(AudioDeviceSnapshot(emptyList(), emptyList()))
                }
            },
        )
    }
}

/**
 * Registers a `devicechange` listener so the picker can auto-refresh on hot-plug.
 *
 * Idempotent: the underlying JS `addEventListener` is attached only once for the page
 * lifetime (a fresh attach on every composition would leak listeners). Subsequent calls
 * just replace the callback the single listener invokes, so the latest mounted picker wins.
 */
fun registerDeviceChangeListener(onChange: () -> Unit) {
    deviceChangeCallback = onChange
    if (!deviceChangeListenerAttached) {
        deviceChangeListenerAttached = true
        addDeviceChangeListener { deviceChangeCallback?.invoke() }
    }
}

private var deviceChangeCallback: (() -> Unit)? = null
private var deviceChangeListenerAttached = false

private enum class MicrophonePermissionRequestState {
    UNKNOWN,
    REQUESTING,
    GRANTED,
    DENIED,
}

private var microphonePermissionRequestState = MicrophonePermissionRequestState.UNKNOWN
private val microphonePermissionWaiters = mutableListOf<(Boolean) -> Unit>()

private fun completeMicrophonePermissionRequest(grantedValue: Int) {
    val granted = grantedValue == 1
    microphonePermissionRequestState = if (granted) {
        MicrophonePermissionRequestState.GRANTED
    } else {
        MicrophonePermissionRequestState.DENIED
    }
    val waiters = microphonePermissionWaiters.toList()
    microphonePermissionWaiters.clear()
    waiters.forEach { it(granted) }
}

/**
 * True if the browser can route the live pass-through monitor to a chosen output sink
 * (`AudioContext.setSinkId`). Narrower support than [mediaElementSinkSelectionSupported].
 */
fun outputSelectionSupported(): Boolean = outputSinkSupportedJs() == 1

private fun outputSinkSupportedJs(): Int =
    js("(typeof AudioContext !== 'undefined' && 'setSinkId' in AudioContext.prototype) ? 1 : 0")

/**
 * True if the browser can route recording playback (`<audio>` elements) to a chosen output sink
 * (`HTMLMediaElement.setSinkId`). This is the API the preview path actually uses
 * ([WebAudioPlayer]) and is supported far more broadly than `AudioContext.setSinkId`, so the
 * recording-playback picker must gate on this rather than [outputSelectionSupported].
 */
fun mediaElementSinkSelectionSupported(): Boolean = mediaElementSinkSupportedJs() == 1

private fun mediaElementSinkSupportedJs(): Int =
    js("(typeof HTMLMediaElement !== 'undefined' && 'setSinkId' in HTMLMediaElement.prototype) ? 1 : 0")

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

private fun enumerateAudioDevicesToGlobals(
    useActiveStream: Int,
    onSuccess: (Int, Int) -> Unit,
    onError: () -> Unit
) {
    js("""
        if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) { onError(); return; }
        function enumerate(after) {
            navigator.mediaDevices.enumerateDevices().then(function(devices) {
                window.__micDevices = devices
                    .filter(function(d) { return d.kind === 'audioinput'; })
                    .map(function(d, idx) {
                        return { deviceId: d.deviceId, label: d.label || ('Microphone ' + (idx + 1)) };
                    });
                window.__outDevices = devices
                    .filter(function(d) { return d.kind === 'audiooutput'; })
                    .map(function(d, idx) {
                        return { deviceId: d.deviceId, label: d.label || ('Output ' + (idx + 1)) };
                    });
                if (after) after();
                onSuccess(window.__micDevices.length, window.__outDevices.length);
            }).catch(function(e) { if (after) after(); onError(); });
        }
        // Best-effort: enumerate while a capture stream is live (see listAudioDevices). If a capture
        // is already running we benefit from it directly; otherwise, when explicitly requested, open
        // a brief default stream across the enumerate call and stop it. Never disturb active capture.
        var activeStream = window.__recStream || window.__mdInfStream;
        if (activeStream || useActiveStream !== 1 || !navigator.mediaDevices.getUserMedia) {
            enumerate(null);
        } else {
            navigator.mediaDevices.getUserMedia({ audio: true, video: false }).then(function(stream) {
                enumerate(function() { stream.getTracks().forEach(function(t) { t.stop(); }); });
            }).catch(function() { enumerate(null); });
        }
    """)
}

private fun addDeviceChangeListener(onChange: () -> Unit) {
    js("""
        if (navigator.mediaDevices && navigator.mediaDevices.addEventListener) {
            navigator.mediaDevices.addEventListener('devicechange', function() { onChange(); });
        }
    """)
}

/** True if the browser supports the Audio Output Devices API (`selectAudioOutput`). */
fun selectAudioOutputSupported(): Boolean = selectAudioOutputSupportedJs() == 1

private fun selectAudioOutputSupportedJs(): Int =
    js("(navigator.mediaDevices && typeof navigator.mediaDevices.selectAudioOutput === 'function') ? 1 : 0")

/**
 * Permission-and-selection flow for the recording-playback output device, mirroring
 * [WebPassthroughMonitor.chooseOutputSink] for the `<audio>` element path.
 *
 * - [deviceId] non-empty: calls `selectAudioOutput({deviceId})` to confirm/grant permission,
 *   then calls [onSelected] with the permitted id and [onLabel] with its human-readable label.
 * - [deviceId] empty: calls `selectAudioOutput()` with no args, showing the browser's full
 *   output chooser. Same callbacks on success.
 * - If `selectAudioOutput` is unavailable (desktop Chrome doesn't need the permission step for
 *   `setSinkId`), calls [onSelected] with [deviceId] and [onLabel] with an empty string directly.
 * User cancellation is a no-op (neither callback fires).
 */
fun selectPlaybackOutputDevice(
    deviceId: String,
    onSelected: (String) -> Unit,
    onLabel: (String) -> Unit,
) {
    selectPlaybackOutputDeviceJs(deviceId, onSelected, onLabel)
}

private fun selectPlaybackOutputDeviceJs(
    deviceId: String,
    onSelected: (String) -> Unit,
    onLabel: (String) -> Unit,
) {
    js("""
        if (navigator.mediaDevices && navigator.mediaDevices.selectAudioOutput) {
            var opts = deviceId ? { deviceId: deviceId } : undefined;
            navigator.mediaDevices.selectAudioOutput(opts)
                .then(function(device) { onSelected(device.deviceId); onLabel(device.label || ''); })
                .catch(function() { /* user cancelled or permission denied — no-op */ });
        } else {
            onSelected(deviceId);
            onLabel('');
        }
    """)
}

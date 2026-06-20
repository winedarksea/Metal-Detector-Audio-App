package com.metaldetectoraudioapp.web.audio

/**
 * Shared `getUserMedia` entry point for both web capture paths (inference + recording), so the
 * device-constraint and fallback logic lives in exactly one place instead of being duplicated.
 *
 * Mirrors Android's *soft* `AudioRecord.preferredDevice` behaviour: it tries hard for the selected
 * device (`deviceId: { exact }`), but if the browser can't open it (a common failure for USB audio
 * dongles on Android Chrome) it transparently retries on the system default so capture still works —
 * then reports the fallback so the UI can revert the picker (see [MicSelectionState.fellBackToDefault]).
 * A permission denial is never retried; it is surfaced as a real error.
 *
 * Kotlin/Wasm interop note: callbacks may only take primitive/`String` params, so the helper resolves
 * the stream into the JS global named by [streamGlobalName] and signals completion via [onStream];
 * each caller then builds its own Web Audio graph from `window[streamGlobalName]`.
 */
internal fun getUserMediaWithFallback(
    streamGlobalName: String,
    onStream: () -> Unit,
    onFellBackToDefault: () -> Unit,
    onError: (String) -> Unit,
) {
    js(
        """
        function store(stream, isRetry) {
            window[streamGlobalName] = stream;
            if (isRetry) { onFellBackToDefault(); onStream(); return; }
            // Verify the track came from the requested device.
            // Android Chrome can silently fulfil deviceId:{exact} with the wrong (default) device.
            if (window.__micDeviceId) {
                var tracks = stream.getAudioTracks();
                if (tracks.length > 0 && tracks[0].getSettings) {
                    var actualId = tracks[0].getSettings().deviceId || '';
                    if (actualId && actualId !== window.__micDeviceId) {
                        onFellBackToDefault();
                        onStream();
                        return;
                    }
                }
            }
            onStream();
        }
        function attempt(constraints, isRetry) {
            navigator.mediaDevices.getUserMedia({ audio: constraints, video: false })
                .then(function(stream) { store(stream, isRetry); })
                .catch(function(e) {
                    var name = (e && e.name) ? e.name : '';
                    var deviceUnavailable =
                        name === 'OverconstrainedError' ||
                        name === 'NotFoundError' ||
                        name === 'NotReadableError';
                    if (!isRetry && window.__micDeviceId && deviceUnavailable) {
                        // Requested device couldn't be opened — fall back to the system default.
                        attempt(baseConstraints(), true);
                    } else {
                        var msg = (e && e.message) ? e.message : String(e);
                        onError(name ? (msg + ' (' + name + ')') : msg);
                    }
                });
        }
        function baseConstraints() {
            return {
                echoCancellation: false,
                noiseSuppression: false,
                autoGainControl: false,
                channelCount: 1
            };
        }
        if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
            onError('This browser does not support microphone capture.');
        } else {
            var first = baseConstraints();
            if (window.__micDeviceId) first.deviceId = { exact: window.__micDeviceId };
            attempt(first, false);
        }
    """
    )
}

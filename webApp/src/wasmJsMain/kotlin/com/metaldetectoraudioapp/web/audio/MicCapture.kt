package com.metaldetectoraudioapp.web.audio

/**
 * Shared `getUserMedia` entry point for both web capture paths (inference + recording), so the
 * device-constraint and verification logic lives in exactly one place instead of being duplicated.
 *
 * If the user selected a non-default microphone, capture is strict: the browser must return a track
 * whose `getSettings().deviceId` matches the requested `deviceId`. Android Chrome can otherwise
 * silently return the built-in mic for a USB-looking input, which would corrupt field recordings.
 *
 * Kotlin/Wasm interop note: callbacks may only take primitive/`String` params, so the helper resolves
 * the stream into the JS global named by [streamGlobalName] and signals completion via [onStream];
 * each caller then builds its own Web Audio graph from `window[streamGlobalName]`.
 */
internal fun getUserMediaStrictlyVerified(
    streamGlobalName: String,
    onStream: () -> Unit,
    onDeviceVerified: (String, String) -> Unit,
    onDeviceRejected: (String, String) -> Unit,
    onError: (String) -> Unit,
) {
    js(
        """
        function stopStream(stream) {
            stream.getTracks().forEach(function(t) { t.stop(); });
            if (window[streamGlobalName] === stream) window[streamGlobalName] = null;
        }
        function store(stream) {
            var requestedId = window.__micDeviceId || '';
            var tracks = stream.getAudioTracks();
            var actualId = '';
            if (tracks.length > 0 && tracks[0].getSettings) {
                actualId = tracks[0].getSettings().deviceId || '';
            }
            if (requestedId && (!actualId || actualId !== requestedId)) {
                stopStream(stream);
                onDeviceRejected(requestedId, actualId);
                onError(
                    actualId
                        ? 'Selected microphone could not be verified. Browser returned a different input.'
                        : 'Selected microphone could not be verified. Browser did not report the captured input.'
                );
                return;
            }
            window[streamGlobalName] = stream;
            onDeviceVerified(requestedId, actualId);
            onStream();
        }
        function attempt(constraints) {
            navigator.mediaDevices.getUserMedia({ audio: constraints, video: false })
                .then(function(stream) { store(stream); })
                .catch(function(e) {
                    var name = (e && e.name) ? e.name : '';
                    var msg = (e && e.message) ? e.message : String(e);
                    onError(name ? (msg + ' (' + name + ')') : msg);
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
            attempt(first);
        }
    """
    )
}

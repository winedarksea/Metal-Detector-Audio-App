package com.metaldetectoraudioapp.app.recording

// Long can't cross the Kotlin/Wasm ↔ JS boundary, so the epoch is passed as a Double.
actual fun formatRecordingTimestamp(epochMs: Long): String =
    formatLocalTimestampJs(epochMs.toDouble())

private fun formatLocalTimestampJs(epochMillis: Double): String = js(
    """
    (function () {
        var d = new Date(epochMillis);
        function p(n, w) { n = String(n); while (n.length < w) n = '0' + n; return n; }
        return p(d.getFullYear(), 4) + p(d.getMonth() + 1, 2) + p(d.getDate(), 2) + '_' +
               p(d.getHours(), 2) + p(d.getMinutes(), 2) + p(d.getSeconds(), 2) + '_' +
               p(d.getMilliseconds(), 3);
    })()
    """
)

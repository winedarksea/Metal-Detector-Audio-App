package com.metaldetectoraudioapp.web.platform

import com.metaldetectoraudioapp.app.platform.FilePicker
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class WebFilePicker : FilePicker {
    override suspend fun pickFile(mimeTypes: List<String>): ByteArray? =
        suspendCancellableCoroutine { cont ->
            val acceptStr = mimeTypes.joinToString(",")
            openFilePicker(
                accept = acceptStr,
                onCancelled = { if (cont.isActive) cont.resume(null) },
                // count=-1 signals cancelled; count≥0 means data in window.__fileBuf
                onLoaded = { count ->
                    if (cont.isActive) {
                        if (count < 0) cont.resume(null)
                        else cont.resume(readFileBuf(count))
                    }
                }
            )
        }
}

private fun readFileBuf(count: Int): ByteArray {
    val out = ByteArray(count)
    for (i in 0 until count) out[i] = readFileBufByte(i)
    return out
}

private fun readFileBufByte(i: Int): Byte = js("window.__fileBuf[i] << 24 >> 24")

private fun openFilePicker(accept: String, onCancelled: () -> Unit, onLoaded: (Int) -> Unit) {
    js("""
        var input = document.createElement('input');
        input.type = 'file';
        if (accept) input.accept = accept;
        input.style.display = 'none';
        document.body.appendChild(input);
        input.onchange = function() {
            var file = input.files && input.files[0];
            document.body.removeChild(input);
            if (!file) { onLoaded(-1); return; }
            var reader = new FileReader();
            reader.onload = function(e) {
                window.__fileBuf = new Int8Array(e.target.result);
                onLoaded(window.__fileBuf.length);
            };
            reader.onerror = function() { onLoaded(-1); };
            reader.readAsArrayBuffer(file);
        };
        input.click();
    """)
}

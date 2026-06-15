package com.metaldetectoraudioapp.web.platform

interface WebPhotoCaptureProvider {
    fun capturePhoto(onResult: (WebPhotoCaptureResult) -> Unit)
}

sealed interface WebPhotoCaptureResult {
    data class Captured(
        val bytes: ByteArray,
        val extension: String = "jpg",
    ) : WebPhotoCaptureResult

    data object Cancelled : WebPhotoCaptureResult

    data class Failed(val message: String) : WebPhotoCaptureResult
}

class BrowserWebPhotoCaptureProvider : WebPhotoCaptureProvider {
    override fun capturePhoto(onResult: (WebPhotoCaptureResult) -> Unit) {
        openPhotoPicker { status, byteCount, message ->
            val result = when (status) {
                PHOTO_CAPTURE_STATUS_CAPTURED -> {
                    WebPhotoCaptureResult.Captured(readCapturedPhotoBytes(byteCount))
                }
                PHOTO_CAPTURE_STATUS_CANCELLED -> WebPhotoCaptureResult.Cancelled
                else -> WebPhotoCaptureResult.Failed(
                    message.ifBlank { "Unable to process the selected photo" }
                )
            }
            onResult(result)
        }
    }
}

private const val PHOTO_CAPTURE_STATUS_CAPTURED = 1
private const val PHOTO_CAPTURE_STATUS_CANCELLED = 0

private fun readCapturedPhotoBytes(byteCount: Int): ByteArray {
    val bytes = ByteArray(byteCount)
    for (index in 0 until byteCount) {
        bytes[index] = readCapturedPhotoByte(index)
    }
    clearCapturedPhotoBytes()
    return bytes
}

private fun readCapturedPhotoByte(index: Int): Byte =
    js("window.__capturedPhotoBytes[index] << 24 >> 24")

private fun clearCapturedPhotoBytes(): Unit =
    js("window.__capturedPhotoBytes = null")

/**
 * Uses the native camera/library chooser, then normalizes the selection so large phone photos
 * do not consume excessive IndexedDB space or inflate exported training bundles.
 */
private fun openPhotoPicker(onCompleted: (Int, Int, String) -> Unit) {
    js("""
        var input = document.createElement('input');
        input.type = 'file';
        input.accept = 'image/*';
        input.capture = 'environment';
        input.style.display = 'none';
        document.body.appendChild(input);

        var settled = false;
        function finish(status, count, message) {
            if (settled) return;
            settled = true;
            window.removeEventListener('focus', handleWindowFocus);
            if (input.parentNode) input.parentNode.removeChild(input);
            onCompleted(status, count, message || '');
        }

        function handleWindowFocus() {
            // Older browsers do not fire `cancel`; wait for a possible `change` event first.
            window.setTimeout(function() {
                if (!settled && (!input.files || input.files.length === 0)) finish(0, 0, '');
            }, 700);
        }

        input.oncancel = function() { finish(0, 0, ''); };
        input.onchange = function() {
            var file = input.files && input.files[0];
            if (!file) { finish(0, 0, ''); return; }

            var objectUrl = URL.createObjectURL(file);
            var image = new Image();
            image.onload = function() {
                try {
                    var maximumLongEdge = 1600;
                    var sourceWidth = image.naturalWidth || image.width;
                    var sourceHeight = image.naturalHeight || image.height;
                    if (!sourceWidth || !sourceHeight) throw new Error('The selected image has no dimensions');

                    var scale = Math.min(1, maximumLongEdge / Math.max(sourceWidth, sourceHeight));
                    var outputWidth = Math.max(1, Math.round(sourceWidth * scale));
                    var outputHeight = Math.max(1, Math.round(sourceHeight * scale));
                    var canvas = document.createElement('canvas');
                    canvas.width = outputWidth;
                    canvas.height = outputHeight;
                    var context = canvas.getContext('2d');
                    if (!context) throw new Error('Image conversion is unavailable');
                    context.drawImage(image, 0, 0, outputWidth, outputHeight);

                    canvas.toBlob(function(blob) {
                        URL.revokeObjectURL(objectUrl);
                        if (!blob) { finish(-1, 0, 'Unable to encode the selected image'); return; }
                        blob.arrayBuffer().then(function(buffer) {
                            window.__capturedPhotoBytes = new Int8Array(buffer);
                            finish(1, window.__capturedPhotoBytes.length, '');
                        }).catch(function(error) {
                            finish(-1, 0, error && error.message ? error.message : String(error));
                        });
                    }, 'image/jpeg', 0.9);
                } catch (error) {
                    URL.revokeObjectURL(objectUrl);
                    finish(-1, 0, error && error.message ? error.message : String(error));
                }
            };
            image.onerror = function() {
                URL.revokeObjectURL(objectUrl);
                finish(-1, 0, 'The selected file could not be decoded as an image');
            };
            image.src = objectUrl;
        };

        window.addEventListener('focus', handleWindowFocus);
        input.click();
    """)
}

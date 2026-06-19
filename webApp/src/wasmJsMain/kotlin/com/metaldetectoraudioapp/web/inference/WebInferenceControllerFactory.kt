package com.metaldetectoraudioapp.web.inference

import com.metaldetectoraudioapp.app.audio.pipeline.SharedAudioPipeline
import com.metaldetectoraudioapp.app.inference.InferenceController
import com.metaldetectoraudioapp.app.inference.ModelMetadataJson
import com.metaldetectoraudioapp.web.audio.WebMicrophoneInputSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object WebInferenceControllerFactory {

    suspend fun create(): InferenceController {
        val metadataResourceNames = listOf(
            "starter_model_metadata.json",
            "starter_model_no_mixed_metadata.json",
        )
        val availableModels = metadataResourceNames.map { resourceName ->
            ModelMetadataJson.parse(fetchText("/app/$resourceName"), resourceName)
        }
        val metadata = availableModels.firstOrNull { it.modelVariantId == "no_mixed" }
            ?: availableModels.first { it.modelVariantId == "standard" }
        suspend fun createClassifier(
            model: com.metaldetectoraudioapp.app.inference.ModelMetadata
        ): WebOnnxClassifier {
            val onnxFileName = model.artifacts.desktopOnnxFileName
                ?: error("Missing desktop_onnx artifact for ${model.modelVariantId}")
            return WebOnnxClassifier.create(fetchBytes("/app/$onnxFileName"), model)
        }

        val source = WebMicrophoneInputSource(sampleRateHz = metadata.input.sampleRateHz)
        val pipeline = SharedAudioPipeline(
            inputSource = source,
            frameSizeSamples = metadata.input.windowSizeSamples,
            hopSizeSamples = metadata.input.hopSizeSamples,
        )

        return InferenceController(
            modelMetadata = metadata,
            audioPipeline = pipeline,
            initialClassifier = createClassifier(metadata),
            availableModels = availableModels,
            classifierFactory = ::createClassifier,
        )
    }

    private suspend fun fetchText(url: String): String =
        suspendCancellableCoroutine { cont ->
            fetchTextJs(
                url,
                onSuccess = { text -> if (cont.isActive) cont.resume(text) },
                onError   = { msg  -> if (cont.isActive) cont.resumeWithException(RuntimeException(msg)) }
            )
        }

    /**
     * Fetches bytes to `window.__fetchBuf` global, then resumes with a ByteArray copy.
     * The callback only carries an Int (byte count), avoiding the Kotlin/WASM interop restriction
     * on ByteArray as a JS function parameter type.
     */
    private suspend fun fetchBytes(url: String): ByteArray =
        suspendCancellableCoroutine { cont ->
            fetchBytesToGlobal(
                url = url,
                onSuccess = { count ->
                    if (cont.isActive) cont.resume(readFetchGlobalBuf(count))
                },
                onError = { msg ->
                    if (cont.isActive) cont.resumeWithException(RuntimeException(msg))
                }
            )
        }
}

/** Reads `count` bytes from `window.__fetchBuf` (written by [fetchBytesToGlobal]). */
private fun readFetchGlobalBuf(count: Int): ByteArray {
    val out = ByteArray(count)
    for (i in 0 until count) out[i] = readFetchGlobalBufByte(i)
    return out
}

private fun readFetchGlobalBufByte(i: Int): Byte = js("window.__fetchBuf[i] << 24 >> 24")

private fun fetchTextJs(url: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
    js("""
        fetch(url).then(function(r) {
            if (!r.ok) { onError('HTTP ' + r.status); return Promise.reject(); }
            return r.text();
        }).then(function(t) { onSuccess(t); }).catch(function(e) { onError(String(e)); });
    """)
}

private fun fetchBytesToGlobal(url: String, onSuccess: (Int) -> Unit, onError: (String) -> Unit) {
    js("""
        fetch(url).then(function(r) {
            if (!r.ok) { onError('HTTP ' + r.status); return Promise.reject(); }
            return r.arrayBuffer();
        }).then(function(ab) {
            window.__fetchBuf = new Int8Array(ab);
            onSuccess(window.__fetchBuf.length);
        }).catch(function(e) { onError(String(e)); });
    """)
}

package com.metaldetectoraudioapp.web.inference

import com.metaldetectoraudioapp.app.audio.pipeline.MelSpectrogramFeatureExtractor
import com.metaldetectoraudioapp.app.inference.AudioWindowClassifier
import com.metaldetectoraudioapp.app.inference.InferenceAccelerator
import com.metaldetectoraudioapp.app.inference.InferenceResult
import com.metaldetectoraudioapp.app.inference.ModelMetadata
import com.metaldetectoraudioapp.app.util.Clocks
import com.metaldetectoraudioapp.web.platform.Promise
import com.metaldetectoraudioapp.web.platform.await
import com.metaldetectoraudioapp.web.platform.writeBytesToGlobal

/**
 * Runs the CNN classifier via onnxruntime-web (global `ort` loaded by index.html).
 *
 * Bytes and float arrays cannot cross the WASM/JS boundary directly; they are staged in a
 * global JS buffer before each JS call. ORT is single-threaded (numThreads=1) so no COOP/COEP
 * headers are needed.
 */
class WebOnnxClassifier(
    private val session: JsAny,
    private val metadata: ModelMetadata,
) : AudioWindowClassifier {

    // Parameters fixed to training pipeline: 16kHz, 256 frame, 128 hop, 40 mel bins
    private val melExtractor = MelSpectrogramFeatureExtractor()

    override val activeAccelerator: InferenceAccelerator = InferenceAccelerator.CPU

    override suspend fun classifyAudioWindow(samples: FloatArray): InferenceResult {
        val startNs = Clocks.monotonicNanos()

        val melMatrix = melExtractor.extractLogMelSpectrogram(samples)
        val numFrames = melMatrix.size
        val numMels = if (numFrames > 0) melMatrix[0].size else 40

        // Stage the flat float array in a global JS Float32Array
        writeFloatArrayToGlobal(melMatrix, numFrames, numMels)

        val outputRef = runOnnxInference(session, numFrames, numMels).await()
        val scores = FloatArray(3) { i -> getOutputFloat(outputRef, i) }

        val topIdx = scores.indices.maxByOrNull { scores[it] } ?: 0
        val topLabel = (metadata.labels.getOrNull(topIdx) ?: "AMBIENT").uppercase()

        val inferMs = ((Clocks.monotonicNanos() - startNs) / 1_000_000).coerceAtLeast(0)
        return InferenceResult(
            topLabel = topLabel,
            topScore = scores[topIdx],
            perLabelScores = metadata.labels.zip(scores.toList()).toMap(),
            inferenceTimeMs = inferMs,
        )
    }

    override fun close() { /* ORT session lifecycle managed by JS GC */ }

    companion object {
        suspend fun create(onnxBytes: ByteArray, metadata: ModelMetadata): WebOnnxClassifier {
            // Stage onnx bytes in global buf, then create session
            writeBytesToGlobal(onnxBytes)
            val session = createOrtSessionFromGlobal(onnxBytes.size).await()
            return WebOnnxClassifier(session, metadata)
        }
    }
}

private fun writeFloatArrayToGlobal(melMatrix: Array<FloatArray>, numFrames: Int, numMels: Int) {
    initGlobalFloatBuf(numFrames * numMels)
    var idx = 0
    for (frame in melMatrix) {
        for (mel in frame) {
            setGlobalFloatAt(idx, mel)
            idx++
        }
    }
}

private fun initGlobalFloatBuf(size: Int): Unit = js("window.__ktFloatBuf = new Float32Array(size)")
private fun setGlobalFloatAt(i: Int, v: Float): Unit = js("window.__ktFloatBuf[i] = v")

private fun createOrtSessionFromGlobal(byteCount: Int): Promise<JsAny> = js("""
    (function() {
        var src = window.__ktWriteBuf;
        var arr = new Uint8Array(byteCount);
        for (var i = 0; i < byteCount; i++) arr[i] = src[i];
        return ort.InferenceSession.create(arr.buffer, {
            executionProviders: ['wasm'],
            intraOpNumThreads: 1
        });
    })()
""")

private fun runOnnxInference(session: JsAny, numFrames: Int, numMels: Int): Promise<JsAny> = js("""
    (function() {
        var flat = window.__ktFloatBuf;
        var inputName  = session.inputNames[0];
        var outputName = session.outputNames[0];
        var tensor = new ort.Tensor('float32', flat, [1, numFrames, numMels, 1]);
        var feeds = {};
        feeds[inputName] = tensor;
        return session.run(feeds).then(function(output) {
            return output[outputName].data;
        });
    })()
""")

private fun getOutputFloat(arr: JsAny, index: Int): Float = js("arr[index]")

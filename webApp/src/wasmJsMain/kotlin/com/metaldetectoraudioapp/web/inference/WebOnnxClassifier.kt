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

    // The CNN graph has a FIXED input shape [1, timeFrames, melBins, 1]; onnxruntime-web rejects
    // any other frame count. Read the expected dims from metadata (fall back to the 1.0 s / 16 kHz
    // training config) and pad/truncate to them — mirrors DesktopOnnxClassifier.
    private val expectedTimeFrames: Int =
        metadata.artifacts.acceleratorInput.timeFrames ?: DEFAULT_TIME_FRAMES
    private val expectedMelBins: Int =
        metadata.artifacts.acceleratorInput.melBins ?: DEFAULT_MEL_BINS

    override val activeAccelerator: InferenceAccelerator = InferenceAccelerator.CPU

    override suspend fun classifyAudioWindow(samples: FloatArray): InferenceResult {
        val startNs = Clocks.monotonicNanos()

        val melMatrix = melExtractor.extractScaledSpectrogram(samples)
        if (melMatrix.isEmpty()) {
            return InferenceResult(
                topLabel = "AMBIENT",
                topScore = 0f,
                perLabelScores = metadata.labels.associateWith { 0f },
                inferenceTimeMs = 0L,
            )
        }

        // Stage the flat float array (padded/truncated to the model's fixed shape) in a global
        // JS Float32Array.
        writeFloatArrayToGlobal(melMatrix, expectedTimeFrames, expectedMelBins)

        // Second model input: raw log-RMS loudness (standardized in-graph).
        val loudness = melExtractor.computeLoudness(samples)
        val outputRef = runOnnxInference(session, expectedTimeFrames, expectedMelBins, loudness).await()
        val scores = FloatArray(metadata.labels.size) { i -> getOutputFloat(outputRef, i) }

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
        // Defaults match the 1.0 s / 16 kHz training config (16000-sample window -> 124 frames).
        private const val DEFAULT_TIME_FRAMES = 124
        private const val DEFAULT_MEL_BINS = 40

        suspend fun create(onnxBytes: ByteArray, metadata: ModelMetadata): WebOnnxClassifier {
            // Stage onnx bytes in global buf, then create session
            writeBytesToGlobal(onnxBytes)
            val session = createOrtSessionFromGlobal(onnxBytes.size).await()
            return WebOnnxClassifier(session, metadata)
        }
    }
}

/**
 * Stages the log-mel spectrogram as a flat Float32Array of exactly [timeFrames] × [melBins],
 * padding short windows with zeros and truncating long ones so the tensor always matches the
 * CNN's fixed input shape.
 */
private fun writeFloatArrayToGlobal(melMatrix: Array<FloatArray>, timeFrames: Int, melBins: Int) {
    initGlobalFloatBuf(timeFrames * melBins)
    var idx = 0
    for (t in 0 until timeFrames) {
        val frame = if (t < melMatrix.size) melMatrix[t] else null
        for (m in 0 until melBins) {
            val value = if (frame != null && m < frame.size) frame[m] else 0f
            setGlobalFloatAt(idx, value)
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

private fun runOnnxInference(session: JsAny, numFrames: Int, numMels: Int, loudness: Float): Promise<JsAny> = js("""
    (function() {
        var flat = window.__ktFloatBuf;
        var outputName = session.outputNames[0];
        var spectrogram = new ort.Tensor('float32', flat, [1, numFrames, numMels, 1]);
        var loudnessTensor = new ort.Tensor('float32', new Float32Array([loudness]), [1, 1]);
        var feeds = {};
        // Two-input model: match the loudness input by name; everything else is the spectrogram.
        for (var i = 0; i < session.inputNames.length; i++) {
            var name = session.inputNames[i];
            feeds[name] = (name.indexOf('loud') >= 0) ? loudnessTensor : spectrogram;
        }
        return session.run(feeds).then(function(output) {
            return output[outputName].data;
        });
    })()
""")

private fun getOutputFloat(arr: JsAny, index: Int): Float = js("arr[index]")

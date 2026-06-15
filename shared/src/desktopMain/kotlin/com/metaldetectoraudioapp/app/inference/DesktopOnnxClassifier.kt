package com.metaldetectoraudioapp.app.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.metaldetectoraudioapp.app.audio.pipeline.MelSpectrogramFeatureExtractor
import com.metaldetectoraudioapp.app.inference.AppLogger
import java.nio.FloatBuffer

/**
 * Desktop classifier using ONNX Runtime to run the CNN-only model.
 *
 * The TFLite model bakes STFT+mel inside the graph, but ONNX can't represent
 * those ops. Instead, [MelSpectrogramFeatureExtractor] computes the log-mel
 * spectrogram in Kotlin and this classifier feeds it to the CNN portion.
 *
 * Tensor dimensions are read from [ModelMetadata.artifacts.acceleratorInput] so
 * changing the window size in the training script and re-running the export
 * will automatically be reflected here without code changes.
 *
 * ONNX model I/O (two-input, loudness-invariant model):
 *   Input:  "scaled_log_mel_spectrogram" [1, timeFrames, melBins, 1] float32
 *   Input:  "loudness"                   [1, 1]                      float32 (raw log-RMS)
 *   Output: "class_probs"                [1, N]                      float32 (N labels)
 */
class DesktopOnnxClassifier(
    onnxModelBytes: ByteArray,
    private val metadata: ModelMetadata,
) : AudioWindowClassifier {

    override val activeAccelerator: InferenceAccelerator = InferenceAccelerator.CPU

    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val ortSession: OrtSession = ortEnvironment.createSession(onnxModelBytes)
    private val melExtractor = MelSpectrogramFeatureExtractor()

    // Read from metadata so window-size changes propagate automatically; fall
    // back to the values that match the default 1.0 s / 16 kHz training config.
    private val expectedTimeFrames: Int =
        metadata.artifacts.acceleratorInput.timeFrames ?: DEFAULT_TIME_FRAMES
    private val expectedMelBins: Int =
        metadata.artifacts.acceleratorInput.melBins ?: DEFAULT_MEL_BINS

    private val labels: List<String> get() = metadata.labels

    override suspend fun classifyAudioWindow(samples: FloatArray): InferenceResult {
        val startNanos = System.nanoTime()
        val logMel = melExtractor.extractScaledSpectrogram(samples)

        if (logMel.isEmpty()) {
            AppLogger.w(TAG, "Empty spectrogram — window too short (${samples.size} samples)")
            return InferenceResult(
                topLabel = "AMBIENT",
                topScore = 0.0f,
                perLabelScores = labels.associateWith { 0.0f },
                inferenceTimeMs = 0L,
            )
        }

        // Pad or truncate time frames to match model expectation
        val paddedFrames = Array(expectedTimeFrames) { i ->
            if (i < logMel.size) logMel[i] else FloatArray(expectedMelBins)
        }

        // Flatten to [1, 61, 40, 1] layout (row-major)
        val flatSize = expectedTimeFrames * expectedMelBins
        val buffer = FloatBuffer.allocate(flatSize)
        for (frame in paddedFrames) {
            for (bin in 0 until expectedMelBins) {
                buffer.put(if (bin < frame.size) frame[bin] else 0.0f)
            }
        }
        buffer.rewind()

        val inputShape = longArrayOf(1, expectedTimeFrames.toLong(), expectedMelBins.toLong(), 1)
        val inputTensor = OnnxTensor.createTensor(ortEnvironment, buffer, inputShape)

        // Second input: the raw log-RMS loudness scalar (model standardizes it in-graph).
        val loudnessTensor = OnnxTensor.createTensor(
            ortEnvironment,
            FloatBuffer.wrap(floatArrayOf(melExtractor.computeLoudness(samples))),
            longArrayOf(1, 1),
        )

        // Map each session input to the right tensor by rank (spectrogram rank 4, loudness rank 2).
        val feeds = HashMap<String, OnnxTensor>()
        for ((name, info) in ortSession.inputInfo) {
            val rank = (info.info as TensorInfo).shape.size
            feeds[name] = if (rank <= 2) loudnessTensor else inputTensor
        }
        val results = ortSession.run(feeds)

        val rawOutput = results[0].value
        val outputArray: FloatArray = when (rawOutput) {
            is Array<*> -> (rawOutput[0] as? FloatArray)
                ?: throw IllegalStateException(
                    "ONNX output[0][0] is not FloatArray, got ${rawOutput[0]?.javaClass?.simpleName}. " +
                        "Re-export the model with the current opset."
                )
            is FloatArray -> rawOutput
            else -> throw IllegalStateException(
                "Unexpected ONNX output type: ${rawOutput?.javaClass?.simpleName}. " +
                    "Expected Array<FloatArray> or FloatArray."
            )
        }

        inputTensor.close()
        results.close()

        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L

        // Build per-label map
        val perLabel = mutableMapOf<String, Float>()
        for (i in labels.indices) {
            perLabel[labels[i]] = if (i < outputArray.size) outputArray[i] else 0.0f
        }
        val topIndex = outputArray.indices.maxByOrNull { outputArray[it] } ?: 0

        return InferenceResult(
            topLabel = labels.getOrElse(topIndex) { "UNKNOWN" },
            topScore = outputArray.getOrElse(topIndex) { 0.0f },
            perLabelScores = perLabel,
            inferenceTimeMs = elapsedMs,
        )
    }

    override fun close() {
        ortSession.close()
    }

    companion object {
        private const val TAG = "DesktopOnnxClassifier"
        // Default dimensions matching the 1.0 s / 16 kHz training config.
        private const val DEFAULT_TIME_FRAMES = 124
        private const val DEFAULT_MEL_BINS = 40
    }
}

package com.metaldetectoraudioapp.app.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.metaldetectoraudioapp.app.audio.pipeline.AndroidMelSpectrogramFeatureExtractor
import kotlin.math.roundToInt
import kotlin.system.measureNanoTime

class LiteRtCnnClassifier(
    private val modelMetadata: ModelMetadata,
    appContext: Context,
    modelAssetName: String,
) : AudioWindowClassifier {

    private val acceleratorInput = modelMetadata.artifacts.acceleratorInput
    private val acceleratorOutput = modelMetadata.artifacts.acceleratorOutput

    init {
        // Fail fast at construction so AndroidClassifierFactory can fall back to the waveform
        // interpreter instead of crashing the inference coroutine on the first window. The int8
        // model has int8 input/output tensors, so we must (de)quantize using params that only
        // live in the model metadata — LiteRT 2.1.0 does not expose them on the TensorBuffer.
        if (acceleratorInput.dataType == ModelTensorDataType.INT8 && acceleratorInput.quantization == null) {
            throw IllegalStateException(
                "Accelerator model '$modelAssetName' has an int8 input but no quantization params " +
                    "(artifacts.accelerator_input.scale/zero_point) in metadata. Re-export the model."
            )
        }
        if (acceleratorOutput.dataType == ModelTensorDataType.INT8 && acceleratorOutput.quantization == null) {
            throw IllegalStateException(
                "Accelerator model '$modelAssetName' has an int8 output but no quantization params " +
                    "(artifacts.accelerator_output.scale/zero_point) in metadata. Re-export the model."
            )
        }
    }

    private val activeModel = createFirstAvailableModel(appContext, modelAssetName)
    private val melExtractor = AndroidMelSpectrogramFeatureExtractor()

    override val activeAccelerator: InferenceAccelerator = when (activeModel.backend) {
        Accelerator.NPU -> InferenceAccelerator.NPU
        Accelerator.GPU -> InferenceAccelerator.GPU
        else -> InferenceAccelerator.CPU
    }

    override fun classifyAudioWindow(samples: FloatArray): InferenceResult {
        val expectedInput = modelMetadata.artifacts.acceleratorInput

        // Timing covers the full classifier cost: feature extraction + model execution.
        val nanos = measureNanoTime {
            val flattenedInput = melExtractor.extractFlattenedForModel(
                signal = samples,
                expectedTimeFrames = expectedInput.timeFrames ?: DEFAULT_TIME_FRAMES,
                expectedMelBins = expectedInput.melBins ?: DEFAULT_MEL_BINS,
            )
            writeInput(activeModel.inputBuffers[0], flattenedInput)
            activeModel.compiledModel.run(activeModel.inputBuffers, activeModel.outputBuffers)
        }

        val scores = readOutput(activeModel.outputBuffers[0])
        if (scores.size != modelMetadata.labels.size) {
            throw IllegalStateException(
                "LiteRT model output size (${scores.size}) does not match " +
                    "label count (${modelMetadata.labels.size}). " +
                    "Re-export the model with the current metadata labels."
            )
        }
        var topIndex = 0
        var topScore = Float.NEGATIVE_INFINITY
        scores.forEachIndexed { index, value ->
            if (value > topScore) {
                topIndex = index
                topScore = value
            }
        }

        val map = buildMap {
            modelMetadata.labels.forEachIndexed { index, label ->
                put(label, scores[index])
            }
        }

        return InferenceResult(
            topLabel = modelMetadata.labels.getOrElse(topIndex) { "AMBIENT" },
            topScore = topScore,
            perLabelScores = map,
            inferenceTimeMs = nanos / 1_000_000,
        )
    }

    /**
     * Feeds the float feature vector into the model input buffer, quantizing to int8 when the
     * accelerator model expects a quantized input. Matches the TFLite affine scheme used by
     * scripts/mel_cnn_pipeline.run_tflite_predictions: q = round(x / scale + zeroPoint).
     */
    private fun writeInput(buffer: TensorBuffer, features: FloatArray) {
        val quant = acceleratorInput.quantization
        if (acceleratorInput.dataType == ModelTensorDataType.INT8 && quant != null) {
            val quantized = ByteArray(features.size)
            for (i in features.indices) {
                val q = (features[i] / quant.scale + quant.zeroPoint).roundToInt()
                quantized[i] = q.coerceIn(INT8_MIN, INT8_MAX).toByte()
            }
            buffer.writeInt8(quantized)
        } else {
            buffer.writeFloat(features)
        }
    }

    /**
     * Reads the model output, dequantizing int8 logits back to floats:
     * real = (quantized - zeroPoint) * scale.
     */
    private fun readOutput(buffer: TensorBuffer): FloatArray {
        val quant = acceleratorOutput.quantization
        if (acceleratorOutput.dataType == ModelTensorDataType.INT8 && quant != null) {
            val raw = buffer.readInt8()
            return FloatArray(raw.size) { (raw[it].toInt() - quant.zeroPoint) * quant.scale }
        }
        return buffer.readFloat()
    }

    override fun close() {
        activeModel.close()
    }

    private fun createFirstAvailableModel(
        appContext: Context,
        modelAssetName: String,
    ): ActiveModel {
        val failures = ArrayList<Throwable>()

        for (backend in listOf(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU)) {
            try {
                Log.i(TAG, "Attempting LiteRT backend=$backend with assets/$modelAssetName")
                return createModel(appContext, modelAssetName, backend)
            } catch (throwable: Throwable) {
                failures += throwable
                Log.w(TAG, "LiteRT backend=$backend failed: ${throwable.message}")
            }
        }

        throw IllegalStateException(
            "Unable to initialize LiteRT CNN classifier from assets/$modelAssetName with NPU, GPU, or CPU",
            failures.lastOrNull(),
        )
    }

    private fun createModel(
        appContext: Context,
        modelAssetName: String,
        backend: Accelerator,
    ): ActiveModel {
        val environment = if (backend == Accelerator.NPU) {
            Environment.create(BuiltinNpuAcceleratorProvider(appContext))
        } else {
            null
        }

        // Do NOT include CPU as a fallback in the NPU/GPU option sets. The outer
        // createFirstAvailableModel loop handles cross-tier fallback, and including CPU
        // here would cause CompiledModel.create() to silently run on CPU while
        // activeModel.backend (and therefore the UI badge) still reports NPU or GPU.
        val options = when (backend) {
            Accelerator.NPU -> CompiledModel.Options(setOf(Accelerator.NPU)).apply {
                qualcommOptions = CompiledModel.QualcommOptions(
                    htpPerformanceMode = CompiledModel.QualcommOptions.HtpPerformanceMode.HIGH_PERFORMANCE,
                )
            }
            Accelerator.GPU -> CompiledModel.Options(setOf(Accelerator.GPU))
            else -> CompiledModel.Options(Accelerator.CPU)
        }

        val compiledModel = try {
            CompiledModel.create(appContext.assets, modelAssetName, options, environment)
        } catch (throwable: Throwable) {
            environment?.close()
            throw throwable
        }

        return ActiveModel(
            backend = backend,
            compiledModel = compiledModel,
            inputBuffers = compiledModel.createInputBuffers(),
            outputBuffers = compiledModel.createOutputBuffers(),
            environment = environment,
        )
    }

    private data class ActiveModel(
        val backend: Accelerator,
        val compiledModel: CompiledModel,
        val inputBuffers: List<TensorBuffer>,
        val outputBuffers: List<TensorBuffer>,
        val environment: Environment?,
    ) : AutoCloseable {
        override fun close() {
            inputBuffers.forEach { it.close() }
            outputBuffers.forEach { it.close() }
            compiledModel.close()
            environment?.close()
        }
    }

    private companion object {
        const val TAG = "LiteRtCnnClassifier"
        const val DEFAULT_TIME_FRAMES = 61
        const val DEFAULT_MEL_BINS = 40
        const val INT8_MIN = -128
        const val INT8_MAX = 127
    }
}
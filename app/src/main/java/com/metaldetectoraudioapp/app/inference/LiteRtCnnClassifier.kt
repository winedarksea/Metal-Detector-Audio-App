package com.metaldetectoraudioapp.app.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.metaldetectoraudioapp.app.audio.pipeline.AndroidMelSpectrogramFeatureExtractor
import kotlin.system.measureNanoTime

class LiteRtCnnClassifier(
    private val modelMetadata: ModelMetadata,
    appContext: Context,
    modelAssetName: String,
) : AudioWindowClassifier {

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
            activeModel.inputBuffers[0].writeFloat(flattenedInput)
            activeModel.compiledModel.run(activeModel.inputBuffers, activeModel.outputBuffers)
        }

        val scores = activeModel.outputBuffers[0].readFloat()
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
    }
}
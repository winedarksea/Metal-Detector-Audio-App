package com.metaldetectoraudioapp.app.inference

import android.content.Context
import android.util.Log
import com.metaldetectoraudioapp.app.audio.source.MicrophoneAudioInputSource
import com.metaldetectoraudioapp.app.audio.pipeline.SharedAudioPipeline

object InferenceControllerFactory {
    private const val TAG = "InferenceCtrlFactory"

    fun create(appContext: Context, allowFallbackModel: Boolean = false): InferenceController {
        Log.i(TAG, "Creating InferenceController (allowFallback=$allowFallbackModel)")

        val metadataRepository = ModelMetadataRepository(appContext)
        val metadata = runCatching { metadataRepository.load() }
            .onFailure { Log.w(TAG, "Model metadata load failed: ${it.message}") }
            .getOrElse {
                if (!allowFallbackModel) {
                    error("Failed to load model metadata: ${it.message}")
                }
                Log.i(TAG, "Using fallback metadata")
                fallbackMetadata()
            }
        Log.i(TAG, "Metadata loaded: model=${metadata.modelName} v${metadata.modelVersion} sampleRate=${metadata.input.sampleRateHz}")

        val source = MicrophoneAudioInputSource(metadata.input.sampleRateHz)
        Log.i(TAG, "MicrophoneAudioInputSource created")

        val pipeline = SharedAudioPipeline(
            inputSource = source,
            frameSizeSamples = metadata.input.windowSizeSamples,
            hopSizeSamples = metadata.input.hopSizeSamples
        )
        Log.i(TAG, "SharedAudioPipeline created")

        val classifier = runCatching {
            MetalClassifierInterpreter(
                modelMetadata = metadata,
                appContext = appContext
            )
        }
            .onFailure { Log.w(TAG, "TFLite model load failed: ${it.message}") }
            .getOrElse {
                if (!allowFallbackModel) {
                    error("Failed to load starter_model.tflite: ${it.message}")
                }
                Log.i(TAG, "Using FallbackHeuristicClassifier")
                FallbackHeuristicClassifier(metadata.labels)
            }
        Log.i(TAG, "Classifier ready: ${classifier::class.simpleName}")

        return InferenceController(
            modelMetadata = metadata,
            audioPipeline = pipeline,
            classifier = classifier
        )
    }

    private fun fallbackMetadata(): ModelMetadata {
        return ModelMetadata(
            modelName = "fallback",
            modelVersion = "0",
            labels = listOf("TARGET", "JUNK", "AMBIENT"),
            input = ModelInputConfig(
                sampleRateHz = 16_000,
                windowSizeSamples = 8_000,
                hopSizeSamples = 4_000,
                expectsNormalizedAudio = true
            )
        )
    }
}

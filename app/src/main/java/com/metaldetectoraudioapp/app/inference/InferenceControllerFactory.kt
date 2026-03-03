package com.metaldetectoraudioapp.app.inference

import android.content.Context
import com.metaldetectoraudioapp.app.audio.source.MicrophoneAudioInputSource
import com.metaldetectoraudioapp.app.audio.pipeline.SharedAudioPipeline

object InferenceControllerFactory {
    fun create(appContext: Context, allowFallbackModel: Boolean = false): InferenceController {
        val metadataRepository = ModelMetadataRepository(appContext)
        val metadata = runCatching { metadataRepository.load() }
            .getOrElse {
                if (!allowFallbackModel) {
                    error("Failed to load model metadata: ${it.message}")
                }
                fallbackMetadata()
            }

        val source = MicrophoneAudioInputSource(metadata.input.sampleRateHz)
        val pipeline = SharedAudioPipeline(
            inputSource = source,
            frameSizeSamples = metadata.input.windowSizeSamples,
            hopSizeSamples = metadata.input.hopSizeSamples
        )

        val classifier = runCatching {
            MetalClassifierInterpreter(
                modelMetadata = metadata,
                appContext = appContext
            )
        }.getOrElse {
            if (!allowFallbackModel) {
                error("Failed to load starter_model.tflite: ${it.message}")
            }
            FallbackHeuristicClassifier(metadata.labels)
        }

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

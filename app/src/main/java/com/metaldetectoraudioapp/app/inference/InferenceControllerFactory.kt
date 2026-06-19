package com.metaldetectoraudioapp.app.inference

import android.content.Context
import android.util.Log
import com.metaldetectoraudioapp.app.audio.source.MicrophoneAudioInputSource
import com.metaldetectoraudioapp.app.audio.pipeline.SharedAudioPipeline

object InferenceControllerFactory {
    private const val TAG = "InferenceCtrlFactory"

    fun create(appContext: Context): InferenceController {
        Log.i(TAG, "Creating InferenceController")

        val metadataRepository = ModelMetadataRepository(appContext)
        val metadata = metadataRepository.listAvailableMetadata()
            .firstOrNull { it.modelVariantId == "no_mixed" }
            ?: metadataRepository.load()
        Log.i(TAG, "Metadata loaded: model=${metadata.modelName} variant=${metadata.modelVariantId} v${metadata.modelVersion} sampleRate=${metadata.input.sampleRateHz}")

        val source = MicrophoneAudioInputSource(appContext, metadata.input.sampleRateHz)
        Log.i(TAG, "MicrophoneAudioInputSource created")

        val pipeline = SharedAudioPipeline(
            inputSource = source,
            frameSizeSamples = metadata.input.windowSizeSamples,
            hopSizeSamples = metadata.input.hopSizeSamples
        )
        Log.i(TAG, "SharedAudioPipeline created")

        val classifier = AndroidClassifierFactory.create(
            appContext = appContext,
            modelMetadata = metadata,
        )
        Log.i(TAG, "Classifier ready: ${classifier::class.simpleName}")

        return InferenceController(
            modelMetadata = metadata,
            audioPipeline = pipeline,
            initialClassifier = classifier,
            metadataRepository = metadataRepository
        )
    }
}

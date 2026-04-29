package com.metaldetectoraudioapp.app.inference

import android.content.Context
import android.util.Log

object AndroidClassifierFactory {
    private const val TAG = "AndroidClassifierFactory"

    fun create(
        appContext: Context,
        modelMetadata: ModelMetadata,
    ): AudioWindowClassifier {
        val waveformAssetName = modelMetadata.artifacts.waveformTfliteFileName
            ?: modelMetadata.fileName
            ?: "starter_model.tflite"
        val acceleratorAssetName = preferredAcceleratorAssetName(modelMetadata)

        if (acceleratorAssetName != null) {
            runCatching {
                return LiteRtCnnClassifier(
                    modelMetadata = modelMetadata,
                    appContext = appContext,
                    modelAssetName = acceleratorAssetName,
                )
            }.onFailure {
                Log.w(
                    TAG,
                    "LiteRT accelerator model '$acceleratorAssetName' failed, falling back to waveform TFLite: ${it.message}",
                )
            }
        }

        return MetalClassifierInterpreter(
            modelMetadata = modelMetadata,
            appContext = appContext,
            modelAssetName = waveformAssetName,
        )
    }

    private fun preferredAcceleratorAssetName(modelMetadata: ModelMetadata): String? {
        val explicit = modelMetadata.artifacts.acceleratorTfliteFileName
        if (!explicit.isNullOrBlank()) {
            return explicit
        }

        val waveformAssetName = modelMetadata.artifacts.waveformTfliteFileName
            ?: modelMetadata.fileName
            ?: return null
        return waveformAssetName.removeSuffix(".tflite") + "_cnn_int8.tflite"
    }
}
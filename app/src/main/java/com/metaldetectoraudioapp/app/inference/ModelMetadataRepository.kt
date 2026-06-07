package com.metaldetectoraudioapp.app.inference

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ModelMetadataRepository(
    private val appContext: Context
) {
    // WARNING: This matches any asset ending in "_metadata.json" in the root assets dir.
    // If non-model metadata files are added, use a stricter naming convention or manifest.
    fun listAvailableMetadata(): List<ModelMetadata> {
        val assets = appContext.assets.list("") ?: return emptyList()
        return assets.filter { it.endsWith("_metadata.json") }
            .mapNotNull { fileName ->
                runCatching { load(fileName) }.getOrNull()
            }
    }

    fun load(metadataAssetName: String = "starter_model_metadata.json"): ModelMetadata {
        val rawJson = appContext.assets.open(metadataAssetName).bufferedReader().use { it.readText() }
        val json = JSONObject(rawJson)
        val labelsJson = json.optJSONArray("labels") ?: JSONArray()
        val labels = buildList {
            for (index in 0 until labelsJson.length()) {
                add(labelsJson.getString(index))
            }
        }

        val inputJson = json.getJSONObject("input")
        val inferenceJson = json.optJSONObject("inference")
        val trainingJson = json.optJSONObject("training")
        val artifactsJson = json.optJSONObject("artifacts")
        val acceleratorInputJson = artifactsJson?.optJSONObject("accelerator_input")
        val acceleratorOutputJson = artifactsJson?.optJSONObject("accelerator_output")
        
        // Derive model name to distinguish "no mixed" version if flag is set
        val baseName = json.getString("model_name")
        val isNoMixed = trainingJson?.optBoolean("exclude_mixed_records", false) ?: false
        val modelDisplayName = if (isNoMixed) "$baseName (No Mixed)" else baseName

        val waveformFileName = artifactsJson.optStringOrNull("waveform_tflite")
            ?: metadataAssetName.replace("_metadata.json", ".tflite")

        return ModelMetadata(
            modelName = modelDisplayName,
            modelVersion = json.getString("model_version"),
            labels = labels,
            input = ModelInputConfig(
                sampleRateHz = inputJson.getInt("sample_rate_hz"),
                windowSizeSamples = inputJson.getInt("window_size_samples"),
                hopSizeSamples = inputJson.getInt("hop_size_samples"),
                expectsNormalizedAudio = inputJson.optBoolean("expects_normalized_audio", true)
            ),
            recommendedThreshold = inferenceJson?.optDouble("recommended_threshold", 0.55)
                ?.toFloat()
                ?: 0.55f,
            fileName = waveformFileName,
            artifacts = ModelArtifacts(
                waveformTfliteFileName = waveformFileName,
                acceleratorTfliteFileName = artifactsJson.optStringOrNull("accelerator_tflite"),
                acceleratorFloatTfliteFileName = artifactsJson.optStringOrNull("accelerator_float_tflite"),
                desktopOnnxFileName = artifactsJson.optStringOrNull("desktop_onnx"),
                acceleratorInput = ModelArtifactInputConfig(
                    kind = parseInputRepresentation(
                        acceleratorInputJson?.optStringOrNull("kind")
                    ),
                    timeFrames = acceleratorInputJson?.optNullableInt("time_frames"),
                    melBins = acceleratorInputJson?.optNullableInt("mel_bins"),
                    channels = acceleratorInputJson?.optInt("channels", 1) ?: 1,
                    dataType = parseTensorDataType(acceleratorInputJson?.optStringOrNull("dtype")),
                    quantization = parseQuantization(acceleratorInputJson),
                ),
                acceleratorOutput = ModelArtifactOutputConfig(
                    dataType = parseTensorDataType(acceleratorOutputJson?.optStringOrNull("dtype")),
                    quantization = parseQuantization(acceleratorOutputJson),
                ),
            ),
        )
    }

    private fun JSONObject?.optStringOrNull(key: String): String? {
        if (this == null || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (isNull(key)) return null
        return optInt(key)
    }

    private fun parseTensorDataType(rawValue: String?): ModelTensorDataType {
        return when (rawValue?.lowercase()) {
            "int8" -> ModelTensorDataType.INT8
            else -> ModelTensorDataType.FLOAT32
        }
    }

    /**
     * Reads affine quantization params from a tensor descriptor. Returns null unless a non-zero
     * scale is present (a 0.0 scale is how TFLite reports a non-quantized float tensor).
     */
    private fun parseQuantization(tensorJson: JSONObject?): TensorQuantization? {
        if (tensorJson == null) return null
        val scale = tensorJson.optDouble("scale", 0.0).toFloat()
        if (scale == 0.0f) return null
        return TensorQuantization(
            scale = scale,
            zeroPoint = tensorJson.optInt("zero_point", 0),
        )
    }

    private fun parseInputRepresentation(rawValue: String?): ModelInputRepresentation {
        return when (rawValue?.lowercase()) {
            "log_mel",
            "log_mel_spectrogram" -> ModelInputRepresentation.LOG_MEL_SPECTROGRAM
            else -> ModelInputRepresentation.WAVEFORM
        }
    }
}

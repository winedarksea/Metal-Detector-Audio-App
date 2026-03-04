package com.metaldetectoraudioapp.app.inference

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ModelMetadataRepository(
    private val appContext: Context
) {
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
        
        // Derive model name to distinguish "no mixed" version if flag is set
        val baseName = json.getString("model_name")
        val isNoMixed = trainingJson?.optBoolean("exclude_mixed_records", false) ?: false
        val modelDisplayName = if (isNoMixed) "$baseName (No Mixed)" else baseName

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
            fileName = metadataAssetName.replace("_metadata.json", ".tflite")
        )
    }
}

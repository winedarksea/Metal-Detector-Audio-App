package com.metaldetectoraudioapp.app.inference

import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.inputStream

/**
 * Loads model metadata from classpath resources on desktop JVM.
 * The desktopApp build task copies model files into the classpath resources
 * directory so they can be loaded with [ClassLoader.getResourceAsStream].
 */
class DesktopModelMetadataRepository {

    fun load(metadataResourceName: String = "starter_model_metadata.json"): ModelMetadata {
        val classpathMetadata = javaClass.classLoader
            ?.getResourceAsStream(metadataResourceName)
            ?.bufferedReader()
            ?.use { it.readText() }
        val fileMetadata = readMetadataFromProjectModelsDirectory(metadataResourceName)
        val rawJson = classpathMetadata ?: fileMetadata
            ?: error(
                "Model metadata '$metadataResourceName' not found on classpath or in ./models/"
            )

        val json = JSONObject(rawJson)
        val labelsJson = json.optJSONArray("labels") ?: org.json.JSONArray()
        val labels = buildList {
            for (index in 0 until labelsJson.length()) {
                add(labelsJson.getString(index))
            }
        }

        val inputJson = json.getJSONObject("input")
        val inferenceJson = json.optJSONObject("inference")

        return ModelMetadata(
            modelName = json.getString("model_name"),
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
                ?: 0.55f
        )
    }

    private fun readMetadataFromProjectModelsDirectory(fileName: String): String? {
        val projectPath = Path.of(System.getProperty("user.dir"), "models", fileName)
        if (!Files.exists(projectPath)) {
            return null
        }
        return projectPath.inputStream().bufferedReader().use { it.readText() }
    }
}

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
        val artifactsJson = json.optJSONObject("artifacts")
        val acceleratorInputJson = artifactsJson?.optJSONObject("accelerator_input")
        val waveformFileName = artifactsJson.optStringOrNull("waveform_tflite")
            ?: metadataResourceName.replace("_metadata.json", ".tflite")

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
                ?: 0.55f,
            fileName = waveformFileName,
            artifacts = ModelArtifacts(
                waveformTfliteFileName = waveformFileName,
                acceleratorTfliteFileName = artifactsJson.optStringOrNull("accelerator_tflite"),
                acceleratorFloatTfliteFileName = artifactsJson.optStringOrNull("accelerator_float_tflite"),
                desktopOnnxFileName = artifactsJson.optStringOrNull("desktop_onnx"),
                acceleratorInput = ModelArtifactInputConfig(
                    kind = parseInputRepresentation(acceleratorInputJson?.optStringOrNull("kind")),
                    timeFrames = acceleratorInputJson?.optNullableInt("time_frames"),
                    melBins = acceleratorInputJson?.optNullableInt("mel_bins"),
                    channels = acceleratorInputJson?.optInt("channels", 1) ?: 1,
                ),
            ),
        )
    }

    private fun readMetadataFromProjectModelsDirectory(fileName: String): String? {
        val projectPath = Path.of(System.getProperty("user.dir"), "models", fileName)
        if (!Files.exists(projectPath)) {
            return null
        }
        return projectPath.inputStream().bufferedReader().use { it.readText() }
    }

    private fun JSONObject?.optStringOrNull(key: String): String? {
        if (this == null || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (isNull(key)) return null
        return optInt(key)
    }

    private fun parseInputRepresentation(rawValue: String?): ModelInputRepresentation {
        return when (rawValue?.lowercase()) {
            "log_mel",
            "log_mel_spectrogram" -> ModelInputRepresentation.LOG_MEL_SPECTROGRAM
            else -> ModelInputRepresentation.WAVEFORM
        }
    }
}

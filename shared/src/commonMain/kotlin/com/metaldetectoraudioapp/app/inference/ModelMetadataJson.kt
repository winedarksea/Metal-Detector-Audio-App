package com.metaldetectoraudioapp.app.inference

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Multiplatform parser that turns a model-metadata JSON string into [ModelMetadata].
 *
 * Pure kotlinx.serialization so it compiles for every target (desktop/android/web) — this
 * replaces the previous JVM-only `org.json` parsing in `DesktopModelMetadataRepository`.
 * The on-disk wire format is snake_case and the domain classes carry defaults, so we navigate
 * the JSON tree explicitly rather than annotating the domain types.
 */
object ModelMetadataJson {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(rawJson: String, metadataResourceName: String): ModelMetadata {
        val root = json.parseToJsonElement(rawJson).jsonObject

        val labels = root["labels"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()

        val inputObj = root["input"]!!.jsonObject
        val inferenceObj = root["inference"]?.jsonObject
        val artifactsObj = root["artifacts"]?.jsonObject
        val acceleratorInputObj = artifactsObj?.get("accelerator_input")?.jsonObject

        val waveformFileName = artifactsObj.optStringOrNull("waveform_tflite")
            ?: metadataResourceName.replace("_metadata.json", ".tflite")

        return ModelMetadata(
            modelName = root["model_name"]!!.jsonPrimitive.content,
            modelVersion = root["model_version"]!!.jsonPrimitive.content,
            labels = labels,
            input = ModelInputConfig(
                sampleRateHz = inputObj["sample_rate_hz"]!!.jsonPrimitive.int,
                windowSizeSamples = inputObj["window_size_samples"]!!.jsonPrimitive.int,
                hopSizeSamples = inputObj["hop_size_samples"]!!.jsonPrimitive.int,
                expectsNormalizedAudio =
                    inputObj["expects_normalized_audio"]?.jsonPrimitive?.booleanOrNull ?: true,
            ),
            recommendedThreshold =
                inferenceObj?.get("recommended_threshold")?.jsonPrimitive?.floatOrNull ?: 0.55f,
            energyGateRmsThreshold =
                inferenceObj?.get("energy_gate_rms_threshold")?.jsonPrimitive?.floatOrNull ?: 0.015f,
            fileName = waveformFileName,
            artifacts = ModelArtifacts(
                waveformTfliteFileName = waveformFileName,
                acceleratorTfliteFileName = artifactsObj.optStringOrNull("accelerator_tflite"),
                acceleratorFloatTfliteFileName = artifactsObj.optStringOrNull("accelerator_float_tflite"),
                desktopOnnxFileName = artifactsObj.optStringOrNull("desktop_onnx"),
                acceleratorInput = ModelArtifactInputConfig(
                    kind = parseInputRepresentation(acceleratorInputObj.optStringOrNull("kind")),
                    timeFrames = acceleratorInputObj?.get("time_frames")?.jsonPrimitive?.intOrNull,
                    melBins = acceleratorInputObj?.get("mel_bins")?.jsonPrimitive?.intOrNull,
                    channels = acceleratorInputObj?.get("channels")?.jsonPrimitive?.intOrNull ?: 1,
                ),
            ),
        )
    }

    private fun JsonObject?.optStringOrNull(key: String): String? {
        val element = this?.get(key) ?: return null
        return element.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun parseInputRepresentation(rawValue: String?): ModelInputRepresentation =
        when (rawValue?.lowercase()) {
            "log_mel", "log_mel_spectrogram", "scaled_log_mel_spectrogram" ->
                ModelInputRepresentation.LOG_MEL_SPECTROGRAM
            else -> ModelInputRepresentation.WAVEFORM
        }
}

package com.metaldetectoraudioapp.app.inference

data class ModelInputConfig(
    val sampleRateHz: Int,
    val windowSizeSamples: Int,
    val hopSizeSamples: Int,
    val expectsNormalizedAudio: Boolean
)

enum class ModelInputRepresentation {
    WAVEFORM,
    LOG_MEL_SPECTROGRAM,
}

data class ModelArtifactInputConfig(
    val kind: ModelInputRepresentation = ModelInputRepresentation.WAVEFORM,
    val timeFrames: Int? = null,
    val melBins: Int? = null,
    val channels: Int = 1,
)

data class ModelArtifacts(
    val waveformTfliteFileName: String? = null,
    val acceleratorTfliteFileName: String? = null,
    val acceleratorFloatTfliteFileName: String? = null,
    val desktopOnnxFileName: String? = null,
    val acceleratorInput: ModelArtifactInputConfig = ModelArtifactInputConfig(),
)

data class ModelMetadata(
    val modelName: String,
    val modelVersion: String,
    val labels: List<String>,
    val input: ModelInputConfig,
    val recommendedThreshold: Float = 0.55f,
    /** Windows whose raw RMS is below this are reported AMBIENT without running the model
     *  (peak-norm + min-max would otherwise amplify near-silence). Matches training's gate. */
    val energyGateRmsThreshold: Float = 0.015f,
    val fileName: String? = null,
    val artifacts: ModelArtifacts = ModelArtifacts(waveformTfliteFileName = fileName),
    /** Stable identifier derived from the asset file name, not the display name. */
    val assetId: String = (artifacts.waveformTfliteFileName ?: fileName)?.removeSuffix(".tflite") ?: "unknown"
)

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

/** Element dtype of an accelerator model's input/output tensor. */
enum class ModelTensorDataType {
    FLOAT32,
    INT8,
}

/**
 * Affine quantization parameters for an int8 tensor: real = (quantized - zeroPoint) * scale.
 * Mirrors the values baked into the int8 TFLite model and emitted into model metadata by
 * scripts/export_onnx_cnn_only.py. Required to feed/read the int8 accelerator model correctly.
 */
data class TensorQuantization(
    val scale: Float,
    val zeroPoint: Int,
)

data class ModelArtifactInputConfig(
    val kind: ModelInputRepresentation = ModelInputRepresentation.WAVEFORM,
    val timeFrames: Int? = null,
    val melBins: Int? = null,
    val channels: Int = 1,
    val dataType: ModelTensorDataType = ModelTensorDataType.FLOAT32,
    val quantization: TensorQuantization? = null,
)

data class ModelArtifactOutputConfig(
    val dataType: ModelTensorDataType = ModelTensorDataType.FLOAT32,
    val quantization: TensorQuantization? = null,
)

data class ModelArtifacts(
    val waveformTfliteFileName: String? = null,
    val acceleratorTfliteFileName: String? = null,
    val acceleratorFloatTfliteFileName: String? = null,
    val desktopOnnxFileName: String? = null,
    val acceleratorInput: ModelArtifactInputConfig = ModelArtifactInputConfig(),
    val acceleratorOutput: ModelArtifactOutputConfig = ModelArtifactOutputConfig(),
)

data class ModelMetadata(
    val modelName: String,
    val modelVersion: String,
    val labels: List<String>,
    val input: ModelInputConfig,
    val recommendedThreshold: Float = 0.55f,
    val fileName: String? = null,
    val artifacts: ModelArtifacts = ModelArtifacts(waveformTfliteFileName = fileName),
    /** Stable identifier derived from the asset file name, not the display name. */
    val assetId: String = (artifacts.waveformTfliteFileName ?: fileName)?.removeSuffix(".tflite") ?: "unknown"
)

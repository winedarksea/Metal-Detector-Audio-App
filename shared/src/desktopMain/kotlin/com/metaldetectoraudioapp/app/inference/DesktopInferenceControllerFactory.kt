package com.metaldetectoraudioapp.app.inference

import com.metaldetectoraudioapp.app.audio.pipeline.PassthroughSink
import com.metaldetectoraudioapp.app.audio.pipeline.SharedAudioPipeline
import com.metaldetectoraudioapp.app.audio.source.DesktopMicrophoneInputSource
import com.metaldetectoraudioapp.app.inference.AppLogger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.inputStream

/**
 * Wires together the audio pipeline and ONNX classifier for desktop.
 *
 * Model and metadata are loaded from classpath resources (copied there by the
 * desktopApp Gradle task [copyModelAssets]).
 */
object DesktopInferenceControllerFactory {

    private const val TAG = "DesktopInferenceControllerFactory"

    fun create(
        passthroughSink: PassthroughSink? = null,
        allowFallbackModel: Boolean = false,
    ): InferenceController {
        val metadataRepository = DesktopModelMetadataRepository()
        val metadata = runCatching { metadataRepository.load() }
            .getOrElse {
                if (!allowFallbackModel) {
                    error("Failed to load model metadata: ${it.message}")
                }
                AppLogger.w(TAG, "Failed to load metadata, using fallback: ${it.message}")
                fallbackMetadata()
            }

        val source = DesktopMicrophoneInputSource(metadata.input.sampleRateHz)
        val pipeline = SharedAudioPipeline(
            inputSource = source,
            frameSizeSamples = metadata.input.windowSizeSamples,
            hopSizeSamples = metadata.input.hopSizeSamples,
            passthroughSink = passthroughSink,
        )

        val classifier: AudioWindowClassifier = runCatching {
            val onnxBytes = loadOnnxModelBytes()
            DesktopOnnxClassifier(onnxBytes, metadata.labels)
        }.getOrElse { ex ->
            if (!allowFallbackModel) {
                error("ONNX model load failed: ${ex.message}")
            }
            AppLogger.w(TAG, "ONNX classifier failed, using fallback: ${ex.message}")
            FallbackHeuristicClassifier(metadata.labels)
        }

        return InferenceController(
            modelMetadata = metadata,
            audioPipeline = pipeline,
            classifier = classifier,
        )
    }

    private fun loadOnnxModelBytes(): ByteArray {
        val classpathBytes = javaClass.classLoader
            ?.getResourceAsStream("starter_model_cnn.onnx")
            ?.readBytes()
        if (classpathBytes != null) {
            return classpathBytes
        }

        val projectModelPath = Path.of(System.getProperty("user.dir"), "models", "starter_model_cnn.onnx")
        if (Files.exists(projectModelPath)) {
            return projectModelPath.inputStream().use { it.readBytes() }
        }

        error("starter_model_cnn.onnx not found on classpath or in ./models/")
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

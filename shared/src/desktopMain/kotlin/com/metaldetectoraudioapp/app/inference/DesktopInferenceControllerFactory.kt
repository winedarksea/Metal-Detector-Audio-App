package com.metaldetectoraudioapp.app.inference

import com.metaldetectoraudioapp.app.audio.pipeline.PassthroughSink
import com.metaldetectoraudioapp.app.audio.pipeline.SharedAudioPipeline
import com.metaldetectoraudioapp.app.audio.source.DesktopMicrophoneInputSource
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
    ): InferenceController {
        val metadataRepository = DesktopModelMetadataRepository()
        val availableModels = metadataRepository.listAvailableMetadata()
        val metadata = availableModels.firstOrNull { it.modelVariantId == "standard" }
            ?: availableModels.firstOrNull()
            ?: error("No model metadata resources are available")

        val source = DesktopMicrophoneInputSource(metadata.input.sampleRateHz)
        val pipeline = SharedAudioPipeline(
            inputSource = source,
            frameSizeSamples = metadata.input.windowSizeSamples,
            hopSizeSamples = metadata.input.hopSizeSamples,
            passthroughSink = passthroughSink,
        )

        fun createClassifier(model: ModelMetadata): AudioWindowClassifier =
            DesktopOnnxClassifier(
                loadOnnxModelBytes(model.artifacts.desktopOnnxFileName),
                model,
            )

        return InferenceController(
            modelMetadata = metadata,
            audioPipeline = pipeline,
            initialClassifier = createClassifier(metadata),
            availableModels = availableModels,
            classifierFactory = ::createClassifier,
        )
    }

    private fun loadOnnxModelBytes(onnxFileName: String?): ByteArray {
        val resolvedFileName = onnxFileName ?: "starter_model_cnn.onnx"

        val classpathBytes = javaClass.classLoader
            ?.getResourceAsStream(resolvedFileName)
            ?.readBytes()
        if (classpathBytes != null) {
            return classpathBytes
        }

        val projectModelPath = Path.of(System.getProperty("user.dir"), "models", resolvedFileName)
        if (Files.exists(projectModelPath)) {
            return projectModelPath.inputStream().use { it.readBytes() }
        }

        error("$resolvedFileName not found on classpath or in ./models/")
    }
}

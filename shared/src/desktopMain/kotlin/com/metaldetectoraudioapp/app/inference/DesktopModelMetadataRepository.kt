package com.metaldetectoraudioapp.app.inference

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.inputStream

/**
 * Loads model metadata from classpath resources on desktop JVM.
 * The desktopApp build task copies model files into the classpath resources
 * directory so they can be loaded with [ClassLoader.getResourceAsStream].
 *
 * JSON parsing is delegated to the multiplatform [ModelMetadataJson] so the wire format stays
 * shared across desktop/android/web targets.
 */
class DesktopModelMetadataRepository {
    private val metadataResourceNames = listOf(
        "starter_model_metadata.json",
        "starter_model_no_mixed_metadata.json",
    )

    fun listAvailableMetadata(): List<ModelMetadata> =
        metadataResourceNames.mapNotNull { resourceName ->
            runCatching { load(resourceName) }.getOrNull()
        }

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

        return ModelMetadataJson.parse(rawJson, metadataResourceName)
    }

    private fun readMetadataFromProjectModelsDirectory(fileName: String): String? {
        val projectPath = Path.of(System.getProperty("user.dir"), "models", fileName)
        if (!Files.exists(projectPath)) {
            return null
        }
        return projectPath.inputStream().bufferedReader().use { it.readText() }
    }
}

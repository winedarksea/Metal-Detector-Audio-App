package com.metaldetectoraudioapp.app.recording

import java.io.File

/**
 * Desktop [DatasetStore] backed by the filesystem under `<appFilesDirectory>/dataset`.
 *
 * Mirrors the layout the app has always used: WAV files under `dataset/audio`, attachments under
 * `dataset/images`, and `dataset/recordings_metadata.csv`. On first run it migrates a legacy
 * `recordings_metadata.json` into the CSV format so older installs keep their data.
 */
class FileDatasetStore(
    appFilesDirectory: File,
) : DatasetStore {

    private val datasetDirectory = File(appFilesDirectory, "dataset")
    private val audioDirectory = File(datasetDirectory, "audio")
    private val imageDirectory = File(datasetDirectory, "images")
    private val metadataFile = File(datasetDirectory, "recordings_metadata.csv")
    private val legacyMetadataFile = File(datasetDirectory, "recordings_metadata.json")

    /** Absolute path of the dataset directory, surfaced for desktop "open folder" affordances. */
    val datasetDirectoryPath: String = datasetDirectory.absolutePath

    init {
        datasetDirectory.mkdirs()
        audioDirectory.mkdirs()
        imageDirectory.mkdirs()
        if (!metadataFile.exists()) {
            if (!migrateLegacyJsonIfPresent()) {
                metadataFile.writeText(RecordingMetadataCsvCodec.serialize(emptyList()))
            }
        }
    }

    override suspend fun readMetadataCsv(): String =
        runCatching { metadataFile.readText() }.getOrElse { "" }

    override suspend fun writeMetadataCsv(csv: String) {
        metadataFile.writeText(csv)
    }

    override suspend fun saveAudio(fileName: String, bytes: ByteArray) {
        File(audioDirectory, fileName).writeBytes(bytes)
    }

    override suspend fun readAudio(fileName: String): ByteArray? =
        File(audioDirectory, fileName).takeIf { it.exists() }?.readBytes()

    override suspend fun deleteAudio(fileName: String) {
        File(audioDirectory, fileName).delete()
    }

    override suspend fun saveImage(fileName: String, bytes: ByteArray) {
        File(imageDirectory, fileName).writeBytes(bytes)
    }

    override suspend fun readImage(fileName: String): ByteArray? =
        File(imageDirectory, fileName).takeIf { it.exists() }?.readBytes()

    override suspend fun deleteImage(fileName: String) {
        File(imageDirectory, fileName).delete()
    }

    private fun migrateLegacyJsonIfPresent(): Boolean {
        if (!legacyMetadataFile.exists()) return false
        val rawJson = runCatching { legacyMetadataFile.readText() }.getOrNull() ?: return false
        val migrated = RecordingMetadataLegacyJson.parseArray(rawJson)
        if (migrated.isEmpty()) return false
        metadataFile.writeText(RecordingMetadataCsvCodec.serialize(migrated))
        return true
    }
}

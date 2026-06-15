package com.metaldetectoraudioapp.app.export

import com.metaldetectoraudioapp.app.recording.CapturedRecording
import com.metaldetectoraudioapp.app.recording.RecordingLabelDraft
import com.metaldetectoraudioapp.app.recording.RecordingMetadata
import com.metaldetectoraudioapp.app.recording.RecordingMetadataCsvCodec
import com.metaldetectoraudioapp.app.recording.RecordingRepository
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Exports/imports the recording dataset as a ZIP bundle (metadata CSV + split manifest +
 * audio/ + images/). Multiplatform: ZIP work is delegated to a platform [ZipCodec], so the same
 * logic produces downloadable bundles on web and files on desktop.
 */
class DatasetBundleManager(
    private val recordingRepository: RecordingRepository,
    private val zipCodec: ZipCodec,
) {

    suspend fun exportBundle(): ByteArray {
        val recordings = recordingRepository.listRecordings()
        val entries = mutableListOf<ZipFileEntry>()

        entries += ZipFileEntry(
            "metadata/recordings_metadata.csv",
            RecordingMetadataCsvCodec.serialize(recordings).encodeToByteArray(),
        )
        entries += ZipFileEntry(
            "metadata/split_manifest.json",
            buildSplitManifestJson(recordings).encodeToByteArray(),
        )

        for (metadata in recordings) {
            recordingRepository.readAudioBytes(metadata)?.let { audioBytes ->
                entries += ZipFileEntry("audio/${metadata.audioFileName}", audioBytes)
            }
            val imageName = metadata.imageFileName
            if (imageName != null) {
                recordingRepository.readImageBytes(metadata)?.let { imageBytes ->
                    entries += ZipFileEntry("images/$imageName", imageBytes)
                }
            }
        }

        return zipCodec.zip(entries)
    }

    suspend fun importBundle(bundleBytes: ByteArray): Int {
        val entries = zipCodec.unzip(bundleBytes)
        val byPath = entries.associateBy { it.path }

        val metadataCsv = byPath["metadata/recordings_metadata.csv"]
            ?.bytes?.decodeToString()
            ?: return 0
        val importedMetadata = RecordingMetadataCsvCodec.parse(metadataCsv)

        var importedCount = 0
        for (metadata in importedMetadata) {
            val audioBytes = byPath["audio/${metadata.audioFileName}"]?.bytes ?: continue

            val imageName = metadata.imageFileName
            val imageBytes = imageName?.let { byPath["images/$it"]?.bytes }
            val imageExtension = imageName?.substringAfterLast('.', "")?.ifBlank { null }

            val saved = recordingRepository.saveCapturedRecording(
                capturedRecording = CapturedRecording(audioBytes, metadata.durationMs),
                labelDraft = RecordingLabelDraft(
                    objectLabels = metadata.objectLabels,
                    pattern = metadata.pattern,
                    depthInches = metadata.depthInches,
                    notes = metadata.notes,
                    gpsLatitude = metadata.gpsLatitude,
                    gpsLongitude = metadata.gpsLongitude,
                    includeInTraining = metadata.includeInTraining,
                    soilType = metadata.soilType,
                    moisture = metadata.moisture,
                    detectorModel = metadata.detectorModel,
                    searchMode = metadata.searchMode,
                    sensitivity = metadata.sensitivity,
                    recoverySpeed = metadata.recoverySpeed,
                    stabilizer = metadata.stabilizer,
                    imageBytes = imageBytes,
                    imageExtension = imageExtension,
                ),
            )

            recordingRepository.updateRecording(
                saved.copy(
                    createdEpochMs = metadata.createdEpochMs,
                    durationMs = metadata.durationMs,
                )
            )
            importedCount += 1
        }
        return importedCount
    }

    private fun buildSplitManifestJson(recordings: List<RecordingMetadata>): String {
        val trainingIds = recordings.filter { it.includeInTraining }.map { it.recordingId }
        val evalIds = trainingIds
        return buildJsonObject {
            put("strategy", "all_samples_for_train_and_validation")
            putJsonArray("train_recording_ids") { trainingIds.forEach { add(it) } }
            putJsonArray("validation_recording_ids") { evalIds.forEach { add(it) } }
        }.toString()
    }
}

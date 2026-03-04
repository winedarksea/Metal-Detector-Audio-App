package com.metaldetectoraudioapp.app.recording

import org.json.JSONArray
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class RecordingRepository(
    private val appFilesDirectory: File,
) {
    private val datasetDirectory = File(appFilesDirectory, "dataset")
    private val audioDirectory = File(datasetDirectory, "audio")
    private val imageDirectory = File(datasetDirectory, "images")
    private val metadataFile = File(datasetDirectory, "recordings_metadata.csv")
    private val legacyMetadataFile = File(datasetDirectory, "recordings_metadata.json")

    init {
        datasetDirectory.mkdirs()
        audioDirectory.mkdirs()
        imageDirectory.mkdirs()

        if (!metadataFile.exists()) {
            if (!migrateLegacyJsonIfPresent()) {
                persist(emptyList())
            }
        }
    }

    @Synchronized
    fun saveCapturedRecording(
        capturedRecording: CapturedRecording,
        labelDraft: RecordingLabelDraft,
    ): RecordingMetadata {
        val nowMs = System.currentTimeMillis()
        val recordingId = buildRecordingId(nowMs)
        val finalAudioFileName = "$recordingId.wav"
        val finalAudioFile = File(audioDirectory, finalAudioFileName)
        moveTempFile(
            source = capturedRecording.tempAudioFile,
            destination = finalAudioFile,
        )

        val imageFileName = labelDraft.imageTempFile?.let { tempImage ->
            val extension = sanitizeExtension(tempImage.extension, fallback = "jpg")
            val finalImageName = "$recordingId.$extension"
            val finalImageFile = File(imageDirectory, finalImageName)
            moveTempFile(source = tempImage, destination = finalImageFile)
            finalImageName
        }

        val metadata = RecordingMetadata(
            recordingId = recordingId,
            audioFileName = finalAudioFileName,
            targetNames = labelDraft.targetNames,
            classLabel = labelDraft.classLabel,
            pattern = labelDraft.pattern,
            depthInches = labelDraft.depthInches,
            notes = labelDraft.notes,
            gpsLatitude = labelDraft.gpsLatitude,
            gpsLongitude = labelDraft.gpsLongitude,
            mixedFlag = labelDraft.mixedFlag,
            includeInTraining = labelDraft.includeInTraining,
            createdEpochMs = nowMs,
            durationMs = capturedRecording.durationMs,
            soilType = labelDraft.soilType,
            moisture = labelDraft.moisture,
            detectorModel = labelDraft.detectorModel,
            searchMode = labelDraft.searchMode,
            sensitivity = labelDraft.sensitivity,
            recoverySpeed = labelDraft.recoverySpeed,
            stabilizer = labelDraft.stabilizer,
            imageFileName = imageFileName,
        )

        val allRecordings = loadAllMutable()
        allRecordings.add(metadata)
        persist(allRecordings)
        return metadata
    }

    @Synchronized
    fun listRecordings(): List<RecordingMetadata> {
        return loadAllMutable().sortedByDescending { it.createdEpochMs }
    }

    @Synchronized
    fun updateRecording(updatedMetadata: RecordingMetadata) {
        val allRecordings = loadAllMutable()
        val updatedList = allRecordings.map {
            if (it.recordingId == updatedMetadata.recordingId) updatedMetadata else it
        }
        persist(updatedList)
    }

    @Synchronized
    fun deleteRecording(recordingId: String) {
        val allRecordings = loadAllMutable()
        val toRemove = allRecordings.firstOrNull { it.recordingId == recordingId } ?: return
        resolveAudioFile(toRemove)?.delete()
        resolveImageFile(toRemove)?.delete()
        val remaining = allRecordings.filterNot { it.recordingId == recordingId }
        persist(remaining)
    }

    fun resolveAudioFile(metadata: RecordingMetadata): File? {
        val file = File(audioDirectory, metadata.audioFileName)
        return if (file.exists()) file else null
    }

    fun resolveImageFile(metadata: RecordingMetadata): File? {
        val imageName = metadata.imageFileName ?: return null
        val file = File(imageDirectory, imageName)
        return if (file.exists()) file else null
    }

    fun metadataFile(): File = metadataFile

    fun audioDirectory(): File = audioDirectory

    fun imageDirectory(): File = imageDirectory

    private fun loadAllMutable(): MutableList<RecordingMetadata> {
        val csv = runCatching { metadataFile.readText() }.getOrElse { "" }
        return RecordingMetadataCsvCodec.parse(csv).toMutableList()
    }

    private fun persist(recordings: List<RecordingMetadata>) {
        val csv = RecordingMetadataCsvCodec.serialize(recordings)
        metadataFile.writeText(csv)
    }

    private fun moveTempFile(source: File, destination: File) {
        if (!source.renameTo(destination)) {
            source.copyTo(destination, overwrite = true)
            source.delete()
        }
    }

    private fun sanitizeExtension(raw: String, fallback: String): String {
        val cleaned = raw.trim().lowercase().filter { it.isLetterOrDigit() }
        return cleaned.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun migrateLegacyJsonIfPresent(): Boolean {
        if (!legacyMetadataFile.exists()) {
            return false
        }

        val rawJson = runCatching { legacyMetadataFile.readText() }.getOrNull() ?: return false
        val array = runCatching { JSONArray(rawJson) }.getOrNull() ?: return false
        val migrated = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(RecordingMetadata.fromJson(item))
            }
        }
        persist(migrated)
        return true
    }

    private fun buildRecordingId(nowMs: Long): String {
        val timestamp = idFormatter.format(Instant.ofEpochMilli(nowMs))
        val entropy = (System.nanoTime() and 0xFFFF).toString(16).padStart(4, '0')
        return "rec_${timestamp}_$entropy"
    }

    companion object {
        private val idFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
                .withZone(ZoneId.systemDefault())
    }
}

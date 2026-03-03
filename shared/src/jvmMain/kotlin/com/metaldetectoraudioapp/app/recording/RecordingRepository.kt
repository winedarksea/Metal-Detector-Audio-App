package com.metaldetectoraudioapp.app.recording

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class RecordingRepository(
    private val appFilesDirectory: File
) {
    private val datasetDirectory = File(appFilesDirectory, "dataset")
    private val audioDirectory = File(datasetDirectory, "audio")
    private val metadataFile = File(datasetDirectory, "recordings_metadata.json")

    init {
        datasetDirectory.mkdirs()
        audioDirectory.mkdirs()
        if (!metadataFile.exists()) {
            metadataFile.writeText("[]")
        }
    }

    @Synchronized
    fun saveCapturedRecording(
        capturedRecording: CapturedRecording,
        labelDraft: RecordingLabelDraft
    ): RecordingMetadata {
        val recordingId = "rec_${System.currentTimeMillis()}"
        val finalFileName = "$recordingId.wav"
        val finalFile = File(audioDirectory, finalFileName)

        if (!capturedRecording.tempAudioFile.renameTo(finalFile)) {
            capturedRecording.tempAudioFile.copyTo(finalFile, overwrite = true)
            capturedRecording.tempAudioFile.delete()
        }

        val metadata = RecordingMetadata(
            recordingId = recordingId,
            audioFileName = finalFileName,
            targetNames = labelDraft.targetNames,
            classLabel = labelDraft.classLabel,
            pattern = labelDraft.pattern,
            depthInches = labelDraft.depthInches,
            notes = labelDraft.notes,
            gpsLatitude = labelDraft.gpsLatitude,
            gpsLongitude = labelDraft.gpsLongitude,
            mixedFlag = labelDraft.mixedFlag,
            includeInTraining = labelDraft.includeInTraining,
            createdEpochMs = System.currentTimeMillis(),
            durationMs = capturedRecording.durationMs,
            soilType = labelDraft.soilType,
            moisture = labelDraft.moisture,
            detectorModel = labelDraft.detectorModel
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
        val remaining = allRecordings.filterNot { it.recordingId == recordingId }
        persist(remaining)
    }

    fun resolveAudioFile(metadata: RecordingMetadata): File? {
        val file = File(audioDirectory, metadata.audioFileName)
        return if (file.exists()) file else null
    }

    fun metadataFile(): File = metadataFile

    fun audioDirectory(): File = audioDirectory

    private fun loadAllMutable(): MutableList<RecordingMetadata> {
        val json = runCatching { metadataFile.readText() }.getOrElse { "[]" }
        val array = JSONArray(json)
        val result = mutableListOf<RecordingMetadata>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            result.add(RecordingMetadata.fromJson(item))
        }
        return result
    }

    private fun persist(recordings: List<RecordingMetadata>) {
        val array = JSONArray()
        recordings.forEach { array.put(it.toJson()) }
        metadataFile.writeText(array.toString(2))
    }
}

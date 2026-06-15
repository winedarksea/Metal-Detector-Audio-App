package com.metaldetectoraudioapp.app.recording

import com.metaldetectoraudioapp.app.util.Clocks

/**
 * Multiplatform recording dataset repository.
 *
 * Persists recordings as a metadata CSV plus per-recording audio/image blobs through the
 * platform-provided [DatasetStore] (filesystem on desktop, IndexedDB on web). All methods are
 * `suspend` because the underlying store may be asynchronous.
 */
class RecordingRepository(
    private val store: DatasetStore,
) {

    suspend fun saveCapturedRecording(
        capturedRecording: CapturedRecording,
        labelDraft: RecordingLabelDraft,
    ): RecordingMetadata {
        val nowMs = Clocks.epochMillis()
        val recordingId = buildRecordingId(nowMs)
        val finalAudioFileName = "$recordingId.wav"
        store.saveAudio(finalAudioFileName, capturedRecording.wavBytes)

        val imageFileName = labelDraft.imageBytes?.let { imageBytes ->
            val extension = sanitizeExtension(labelDraft.imageExtension ?: "jpg", fallback = "jpg")
            val finalImageName = "$recordingId.$extension"
            store.saveImage(finalImageName, imageBytes)
            finalImageName
        }

        val metadata = RecordingMetadata(
            recordingId = recordingId,
            audioFileName = finalAudioFileName,
            objectLabels = labelDraft.objectLabels.also(::validateRecordingObjectLabels),
            pattern = labelDraft.pattern,
            depthInches = labelDraft.depthInches,
            notes = labelDraft.notes,
            gpsLatitude = labelDraft.gpsLatitude,
            gpsLongitude = labelDraft.gpsLongitude,
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

        val allRecordings = loadAll().toMutableList()
        allRecordings.add(metadata)
        persist(allRecordings)
        return metadata
    }

    suspend fun listRecordings(): List<RecordingMetadata> =
        loadAll().sortedByDescending { it.createdEpochMs }

    suspend fun updateRecording(updatedMetadata: RecordingMetadata) {
        val updatedList = loadAll().map {
            if (it.recordingId == updatedMetadata.recordingId) updatedMetadata else it
        }
        persist(updatedList)
    }

    suspend fun deleteRecording(recordingId: String) {
        val allRecordings = loadAll()
        val toRemove = allRecordings.firstOrNull { it.recordingId == recordingId } ?: return
        store.deleteAudio(toRemove.audioFileName)
        toRemove.imageFileName?.let { store.deleteImage(it) }
        persist(allRecordings.filterNot { it.recordingId == recordingId })
    }

    suspend fun readAudioBytes(metadata: RecordingMetadata): ByteArray? =
        store.readAudio(metadata.audioFileName)

    suspend fun readImageBytes(metadata: RecordingMetadata): ByteArray? =
        metadata.imageFileName?.let { store.readImage(it) }

    private suspend fun loadAll(): List<RecordingMetadata> {
        val csv = runCatching { store.readMetadataCsv() }.getOrElse { "" }
        return RecordingMetadataCsvCodec.parse(csv)
    }

    private suspend fun persist(recordings: List<RecordingMetadata>) {
        store.writeMetadataCsv(RecordingMetadataCsvCodec.serialize(recordings))
    }

    private fun sanitizeExtension(raw: String, fallback: String): String {
        val cleaned = raw.trim().lowercase().filter { it.isLetterOrDigit() }
        return cleaned.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun buildRecordingId(nowMs: Long): String {
        val timestamp = formatRecordingTimestamp(nowMs)
        val entropy = (Clocks.monotonicNanos() and 0xFFFF).toString(16).padStart(4, '0')
        return "rec_${timestamp}_$entropy"
    }
}

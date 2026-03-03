package com.metaldetectoraudioapp.app.recording

import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.SweepPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RecordingRepositoryTest {
    @Test
    fun saveUpdateDelete_roundTripPersistsMetadataAndAudio() {
        val rootDir = Files.createTempDirectory("recording_repo_test").toFile()
        val repository = RecordingRepository(rootDir)

        val tempAudio = rootDir.resolve("capture.wav")
        tempAudio.writeBytes(ByteArray(64) { 1 })

        val saved = repository.saveCapturedRecording(
            capturedRecording = CapturedRecording(tempAudioFile = tempAudio, durationMs = 1_234),
            labelDraft = RecordingLabelDraft(
                targetNames = listOf("quarter"),
                classLabel = ClassLabel.TARGET,
                pattern = SweepPattern.SWING,
                depthInches = "10",
                notes = "clean hit",
                gpsLatitude = 41.12345,
                gpsLongitude = -88.12345,
                mixedFlag = false,
                includeInTraining = true
            )
        )

        val listAfterSave = repository.listRecordings()
        assertEquals(1, listAfterSave.size)
        assertEquals("quarter", listAfterSave.first().targetNames.first())
        assertEquals("clean hit", listAfterSave.first().notes)
        assertEquals(41.12345, listAfterSave.first().gpsLatitude ?: 0.0, 0.000001)
        assertEquals(-88.12345, listAfterSave.first().gpsLongitude ?: 0.0, 0.000001)
        assertTrue(repository.resolveAudioFile(saved)?.exists() == true)

        repository.updateRecording(saved.copy(includeInTraining = false))
        val updated = repository.listRecordings().first()
        assertEquals(false, updated.includeInTraining)

        repository.deleteRecording(saved.recordingId)
        assertTrue(repository.listRecordings().isEmpty())
    }
}

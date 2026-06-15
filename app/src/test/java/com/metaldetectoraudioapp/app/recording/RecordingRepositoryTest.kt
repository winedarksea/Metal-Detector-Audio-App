package com.metaldetectoraudioapp.app.recording

import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.SweepPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
                objectLabels = listOf(
                    RecordingObjectLabel(
                        "coin:quarter:cupronickel-clad-copper",
                        ClassLabel.TARGET,
                    )
                ),
                pattern = SweepPattern.SWING,
                depthInches = "10",
                notes = "clean hit",
                gpsLatitude = 41.12345,
                gpsLongitude = -88.12345,
                includeInTraining = true
            )
        )

        val listAfterSave = repository.listRecordings()
        assertEquals(1, listAfterSave.size)
        assertEquals("coin:quarter:cupronickel-clad-copper", listAfterSave.first().targetNames.first())
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

    @Test
    fun saveCapturedRecording_multipleLabelsShareRecordingIdRowsInCsv() {
        val rootDir = Files.createTempDirectory("recording_repo_csv_test").toFile()
        val repository = RecordingRepository(rootDir)

        val tempAudio = rootDir.resolve("capture.wav")
        tempAudio.writeBytes(ByteArray(96) { 2 })
        val tempImage = rootDir.resolve("capture.jpg")
        tempImage.writeBytes(ByteArray(32) { 3 })

        val saved = repository.saveCapturedRecording(
            capturedRecording = CapturedRecording(tempAudioFile = tempAudio, durationMs = 2_345),
            labelDraft = RecordingLabelDraft(
                objectLabels = listOf(
                    RecordingObjectLabel(
                        "coin:dime:cupronickel-clad-copper",
                        ClassLabel.TARGET,
                    ),
                    RecordingObjectLabel(
                        "trash:foil:aluminum",
                        ClassLabel.JUNK,
                    ),
                ),
                pattern = SweepPattern.WIGGLE,
                depthInches = "7",
                notes = "two targets",
                gpsLatitude = null,
                gpsLongitude = null,
                includeInTraining = false,
                detectorModel = "minelab manticore",
                searchMode = "field",
                sensitivity = "23",
                recoverySpeed = "4",
                stabilizer = "5",
                imageTempFile = tempImage,
            )
        )

        val recordings = repository.listRecordings()
        assertEquals(1, recordings.size)
        assertEquals(2, recordings.first().targetNames.size)
        assertTrue(recordings.first().mixedTargetAndJunk)

        val csvLines = repository.metadataFile().readLines().filter { it.isNotBlank() }
        assertEquals(3, csvLines.size) // header + 2 target rows
        assertTrue(csvLines[1].contains(saved.recordingId))
        assertTrue(csvLines[2].contains(saved.recordingId))
        assertTrue(saved.recordingId.matches(Regex("rec_\\d{8}_\\d{6}_\\d{3}_[0-9a-f]{4}")))

        val imageFile = repository.resolveImageFile(saved)
        assertNotNull(imageFile)
        assertTrue(imageFile?.exists() == true)
    }
}

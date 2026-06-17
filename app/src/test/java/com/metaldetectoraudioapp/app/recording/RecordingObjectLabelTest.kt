package com.metaldetectoraudioapp.app.recording

import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.SweepPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingObjectLabelTest {
    @Test
    fun targetAndJunkDeriveTargetRecordingAndMixedFlag() {
        val labels = listOf(
            RecordingObjectLabel("coin:dime:silver", ClassLabel.TARGET),
            RecordingObjectLabel("trash:foil:aluminum", ClassLabel.JUNK),
        )

        assertEquals(ClassLabel.TARGET, deriveRecordingClassLabel(labels))
        assertTrue(deriveMixedTargetAndJunk(labels))
    }

    @Test
    fun multipleJunkLabelsAreNotMixed() {
        val labels = listOf(
            RecordingObjectLabel("trash:foil:aluminum", ClassLabel.JUNK),
            RecordingObjectLabel("hardware:nail:steel", ClassLabel.JUNK),
        )

        assertEquals(ClassLabel.JUNK, deriveRecordingClassLabel(labels))
        assertFalse(deriveMixedTargetAndJunk(labels))
    }

    @Test(expected = IllegalArgumentException::class)
    fun ambientCannotBeCombinedWithAnotherObject() {
        validateRecordingObjectLabels(
            listOf(
                RecordingObjectLabel("ambient:background:unknown", ClassLabel.AMBIENT),
                RecordingObjectLabel("coin:dime:silver", ClassLabel.TARGET),
            )
        )
    }

    @Test
    fun csvRoundTripPreservesObjectClassesAndDerivedFields() {
        val metadata = RecordingMetadata(
            recordingId = "rec_1",
            audioFileName = "rec_1.wav",
            objectLabels = listOf(
                RecordingObjectLabel("coin:dime:silver", ClassLabel.TARGET),
                RecordingObjectLabel("trash:foil:aluminum", ClassLabel.JUNK),
            ),
            pattern = SweepPattern.SWING,
            depthInches = null,
            notes = null,
            gpsLatitude = null,
            gpsLongitude = null,
            includeInTraining = true,
            createdEpochMs = 1L,
            durationMs = 1_000L,
            detectorModel = "minelab manticore",
            searchMode = "All Metal Mode",
            audioProfile = "multi-tone / full tones",
            sensitivity = "23",
            recoverySpeed = "4",
            stabilizer = "5",
        )

        val csv = RecordingMetadataCsvCodec.serialize(listOf(metadata))
        val parsed = RecordingMetadataCsvCodec.parse(csv).single()

        assertTrue(csv.contains("label_class"))
        assertTrue(csv.contains("mixed_target_and_junk"))
        assertEquals(metadata.objectLabels, parsed.objectLabels)
        assertEquals("multi-tone / full tones", parsed.audioProfile)
        assertEquals("23", parsed.sensitivity)
        assertEquals("4", parsed.recoverySpeed)
        assertEquals("5", parsed.stabilizer)
        assertEquals(ClassLabel.TARGET, parsed.classLabel)
        assertTrue(parsed.mixedTargetAndJunk)
    }

    @Test
    fun legacyCsvMigratesKnownExistingRecordingsToAmbientNonMixed() {
        val legacyCsv =
            "recording_id,audio_file_name,target_name,class_label,pattern,mixed_flag," +
                "include_in_training,created_epoch_ms,duration_ms\n" +
                "rec_1,rec_1.wav,ambient:background:unknown,AMBIENT,SWING,false,true,1,1000\n"

        val parsed = RecordingMetadataCsvCodec.parse(legacyCsv).single()

        assertEquals(ClassLabel.AMBIENT, parsed.classLabel)
        assertNull(parsed.audioProfile)
        assertFalse(parsed.mixedTargetAndJunk)
    }
}

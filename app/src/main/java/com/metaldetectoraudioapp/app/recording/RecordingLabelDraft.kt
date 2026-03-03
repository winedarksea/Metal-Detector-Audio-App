package com.metaldetectoraudioapp.app.recording

import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.SweepPattern

data class RecordingLabelDraft(
    val targetNames: List<String>,
    val classLabel: ClassLabel,
    val pattern: SweepPattern,
    val depthInches: String?,
    val notes: String?,
    val gpsLatitude: Double?,
    val gpsLongitude: Double?,
    val mixedFlag: Boolean,
    val includeInTraining: Boolean
)
